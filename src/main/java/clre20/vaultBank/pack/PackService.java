package clre20.vaultBank.pack;

import clre20.vaultBank.VaultBank;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 材質包：Java 版經 HttpHub（或 config 指定的網址）以獨立的包發送，不會取代其他插件的材質包；
 * 基岩版的 .mcpack 與對照檔放進 Geyser 的資料夾（Geyser 重啟後生效）。
 */
public final class PackService implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("VaultBank-java-pack".getBytes(StandardCharsets.UTF_8));

    private final VaultBank plugin;
    private HubBridge hub;
    private volatile PackBuilder.Built built;
    private String lastSentSha1;
    /** 每個玩家最後收到的材質包網址（只為了下載失敗時寫進後台，結束狀態或離線時移除）。 */
    private final Map<UUID, String> sentUrls = new ConcurrentHashMap<>();
    private Method floodgateCheck;
    private Object floodgateApi;

    public PackService(VaultBank plugin) {
        this.plugin = plugin;
        initFloodgate();
    }

    /** 重新產生材質包（背景執行），完成後在主執行緒發布。 */
    public void rebuild() {
        File geyserDir = geyserFolder();
        File dataDir = plugin.getDataFolder();
        plugin.database().call(() -> {
            PackBuilder.Built b = new PackBuilder(plugin).build();
            File out = new File(dataDir, "pack");
            if (!out.isDirectory() && !out.mkdirs()) {
                throw new IOException("無法建立 " + out);
            }
            writeIfChanged(new File(out, "VaultBank-java.zip"), b.javaZip());
            writeIfChanged(new File(out, "VaultBank.mcpack"), b.bedrockPack());
            writeIfChanged(new File(out, "VaultBank-geyser-mappings.json"), b.bedrockMappings().getBytes(StandardCharsets.UTF_8));
            boolean bedrockChanged = false;
            if (plugin.config().bedrockPackEnabled() && geyserDir != null) {
                bedrockChanged |= writeIfChanged(new File(geyserDir, "packs/VaultBank.mcpack"), b.bedrockPack());
                bedrockChanged |= writeIfChanged(new File(geyserDir, "custom_mappings/VaultBank.json"),
                        b.bedrockMappings().getBytes(StandardCharsets.UTF_8));
            }
            return new Object[]{b, bedrockChanged};
        }).thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
            PackBuilder.Built b = (PackBuilder.Built) res[0];
            boolean bedrockChanged = (boolean) res[1];
            publish(b);
            if (plugin.config().bedrockPackEnabled()) {
                if (geyserDir == null) {
                    if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null) {
                        plugin.getLogger().info(plugin.messages().console("pack-bedrock-no-geyser"));
                    }
                } else if (bedrockChanged) {
                    plugin.getLogger().warning(plugin.messages().console("pack-bedrock-installed",
                            "folder", geyserDir.getPath()));
                }
            }
        })).exceptionally(t -> {
            plugin.getLogger().warning(plugin.messages().console("pack-build-failed", "reason", String.valueOf(t.getCause() != null ? t.getCause() : t)));
            return null;
        });
    }

    private void publish(PackBuilder.Built b) {
        this.built = b;
        if (!plugin.config().javaPackEnabled()) {
            unregister();
            return;
        }
        Plugin hubPlugin = Bukkit.getPluginManager().getPlugin("HttpHub");
        if (hubPlugin != null && hubPlugin.isEnabled()) {
            if (hub == null) {
                hub = new HubBridge(plugin);
            }
            if (!hub.serve(b.javaZip())) {
                hub = null;
            }
        } else {
            hub = null;
        }
        if (hub == null && plugin.config().javaPackUrl().isEmpty()) {
            plugin.getLogger().warning(plugin.messages().console("pack-java-no-host"));
            return;
        }
        plugin.getLogger().info(plugin.messages().console("pack-java-ready", "sha1", b.javaSha1()));
        if (lastSentSha1 != null && !lastSentSha1.equals(b.javaSha1())) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                send(p);
            }
        }
        lastSentSha1 = b.javaSha1();
    }

    public void unregister() {
        if (hub != null) {
            hub.unregister();
            hub = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().javaPackEnabled() || built == null) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                send(p);
            }
        }, plugin.config().javaPackDelayTicks());
    }

    private void send(Player player) {
        PackBuilder.Built b = built;
        if (b == null || isBedrock(player.getUniqueId())) {
            return;
        }
        String url = hub != null ? hub.url(player) : null;
        if (url == null) {
            url = plugin.config().javaPackUrl();
        }
        if (url == null || url.isEmpty()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(plugin.messages().console("pack-bad-url", "url", url));
            return;
        }
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, uri, b.javaSha1());
        ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .replace(false)
                .required(plugin.config().javaPackRequired())
                .prompt(plugin.messages().component("pack.prompt"))
                .build();
        sentUrls.put(player.getUniqueId(), url);
        player.sendResourcePacks(request);
    }

    /** 只處理 VaultBank 自己的材質包；下載或載入失敗時在後台寫出網址，方便找出原因。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.ACCEPTED
                || status == PlayerResourcePackStatusEvent.Status.DOWNLOADED) {
            return;
        }
        Player player = event.getPlayer();
        String url = sentUrls.remove(player.getUniqueId());
        switch (status) {
            case SUCCESSFULLY_LOADED -> {
            }
            case DECLINED -> plugin.getLogger().info(plugin.messages().console("pack-declined",
                    "player", player.getName()));
            case DISCARDED -> {
            }
            default -> {
                plugin.getLogger().warning(plugin.messages().console("pack-failed",
                        "player", player.getName(), "status", status.name(), "url", url == null ? "?" : url));
                plugin.messages().send(player, "pack.failed");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sentUrls.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Geyser ----------

    private File geyserFolder() {
        String custom = plugin.config().geyserFolder();
        if (!custom.isEmpty()) {
            File f = new File(custom);
            return f.isDirectory() ? f : null;
        }
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        return geyser != null ? geyser.getDataFolder() : null;
    }

    private void initFloodgate() {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateApi = api.getMethod("getInstance").invoke(null);
            floodgateCheck = api.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) {
            floodgateApi = null;
            floodgateCheck = null;
        }
    }

    /** 基岩版玩家（經 Floodgate）不發 Java 材質包。 */
    public boolean isBedrock(UUID id) {
        if (floodgateCheck == null) {
            return false;
        }
        try {
            return (boolean) floodgateCheck.invoke(floodgateApi, id);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 內容不同才寫（寫到暫存檔再替換），回傳是否有寫。 */
    private static boolean writeIfChanged(File file, byte[] data) throws IOException {
        if (file.isFile() && file.length() == data.length && Arrays.equals(Files.readAllBytes(file.toPath()), data)) {
            return false;
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("無法建立 " + parent);
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }
}
