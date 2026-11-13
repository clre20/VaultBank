package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 在聊天室輸入文字（金額、玩家名稱）。逾時預設 60 秒；輸入「取消」返回選單。
 * 輸入的訊息不會送到聊天室。
 */
public final class ChatInput implements Listener {

    private record Pending(BiConsumer<Player, String> onInput, Runnable onCancel, BukkitTask timeout) {
    }

    private static final int MAX_LENGTH = 64;

    private final VaultBank plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatInput(VaultBank plugin) {
        this.plugin = plugin;
    }

    /**
     * 開始等待輸入。
     *
     * @param promptKey messages.yml 的提示訊息鍵
     * @param onInput   在主執行緒收到輸入
     * @param onCancel  取消或逾時後（主執行緒），可為 null
     */
    public void ask(Player player, String promptKey, BiConsumer<Player, String> onInput, Runnable onCancel) {
        UUID id = player.getUniqueId();
        cancel(id);
        player.closeInventory();
        // 基岩版玩家在 enforce-secure-profile 開啟時不能聊天，改用 Floodgate 的輸入框
        if (askForm(player, promptKey, onInput, onCancel)) {
            return;
        }
        int seconds = plugin.config().chatInputTimeoutSeconds();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending p = pending.remove(id);
            if (p != null) {
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    plugin.messages().send(online, "input.timeout");
                }
            }
        }, seconds * 20L);
        pending.put(id, new Pending(onInput, onCancel, task));
        plugin.messages().send(player, promptKey, "seconds", seconds, "cancel", plugin.messages().plain("input.cancel-word"));
    }

    /** 基岩版：送出輸入框。回傳 false 表示不是基岩版或送不出去，改用聊天室。 */
    private boolean askForm(Player player, String promptKey, BiConsumer<Player, String> onInput, Runnable onCancel) {
        if (!floodgate()) {
            return false;
        }
        UUID id = player.getUniqueId();
        try {
            if (!FormBridge.isBedrock(id)) {
                return false;
            }
            String key = "input.form." + promptKey.substring(promptKey.lastIndexOf('.') + 1);
            // 箱子介面剛關掉時基岩版不會顯示表單，等介面關好再送
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getPlayer(id) == null) {
                    return;
                }
                boolean sent;
                try {
                    sent = sendForm(id, key, onInput, onCancel);
                } catch (Throwable t) {
                    plugin.getLogger().warning(plugin.messages().console("form-failed", "reason", String.valueOf(t)));
                    sent = false;
                }
                if (!sent && onCancel != null) {
                    onCancel.run();
                }
            }, plugin.config().bedrockOpenDelayTicks());
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning(plugin.messages().console("form-failed", "reason", String.valueOf(t)));
            return false;
        }
    }

    private boolean sendForm(UUID id, String key, BiConsumer<Player, String> onInput, Runnable onCancel) {
        return FormBridge.ask(id,
                plugin.messages().plain("input.form.title"),
                plugin.messages().plain(key + ".label"),
                plugin.messages().plain(key + ".placeholder"),
                text -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) {
                        return;
                    }
                    String input = text.trim();
                    if (input.length() > MAX_LENGTH) {
                        input = input.substring(0, MAX_LENGTH);
                    }
                    if (input.isEmpty()) {
                        plugin.messages().send(p, "input.cancelled");
                        if (onCancel != null) {
                            onCancel.run();
                        }
                        return;
                    }
                    onInput.accept(p, input);
                }),
                () -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (Bukkit.getPlayer(id) != null && onCancel != null) {
                        onCancel.run();
                    }
                }));
    }

    private static boolean floodgate() {
        org.bukkit.plugin.Plugin fg = Bukkit.getPluginManager().getPlugin("floodgate");
        return fg != null && fg.isEnabled();
    }

    public boolean waiting(UUID id) {
        return pending.containsKey(id);
    }

    private void cancel(UUID id) {
        Pending p = pending.remove(id);
        if (p != null) {
            p.timeout().cancel();
        }
    }

    public void clear() {
        for (Pending p : pending.values()) {
            p.timeout().cancel();
        }
        pending.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!pending.containsKey(id)) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        String input = text;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Pending p = pending.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (p == null || player == null) {
                return;
            }
            p.timeout().cancel();
            if (input.equalsIgnoreCase(plugin.messages().plain("input.cancel-word"))) {
                plugin.messages().send(player, "input.cancelled");
                if (p.onCancel() != null) {
                    p.onCancel().run();
                }
                return;
            }
            p.onInput().accept(player, input);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }
}
