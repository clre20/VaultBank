package clre20.vaultBank.update;

import clre20.vaultBank.VaultBank;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** 版本檢查結果的顯示，以及有新版時管理員登入 3 秒後的提醒。 */
public final class UpdateNotifier implements Listener {

    public static final String PERMISSION = "vaultbank.version";

    private final VaultBank plugin;

    public UpdateNotifier(VaultBank plugin) {
        this.plugin = plugin;
    }

    /** 啟動時在後台檢查一次。 */
    public void checkOnStartup() {
        if (!plugin.config().updateCheckOnStartup()) {
            return;
        }
        plugin.updates().check().thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (r.status()) {
                case NEWER -> plugin.getLogger().warning(plugin.messages().console("update-newer",
                        "latest", r.latest(), "current", r.current(), "url", UpdateChecker.PAGE));
                case LATEST -> plugin.getLogger().info(plugin.messages().console("update-latest"));
                case DEV -> plugin.getLogger().info(plugin.messages().console("update-dev"));
                case FAILED -> plugin.getLogger().info(plugin.messages().console("update-failed",
                        "reason", reason(r)));
            }
        }));
    }

    /** /vaultbank version。 */
    public void showTo(CommandSender sender) {
        plugin.messages().send(sender, "version.current", "version", plugin.getPluginMeta().getVersion());
        plugin.messages().send(sender, "version.checking");
        plugin.updates().check().thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (r.status()) {
                case NEWER -> plugin.messages().send(sender, "version.newer",
                        "latest", r.latest(), "current", r.current(), "url", UpdateChecker.PAGE);
                case LATEST -> plugin.messages().send(sender, "version.latest");
                case DEV -> plugin.messages().send(sender, "version.dev");
                case FAILED -> plugin.messages().send(sender, "version.failed", "reason", reason(r));
            }
        }));
    }

    private String reason(UpdateChecker.Result r) {
        return plugin.messages().plain(r.reasonKey(), "code", r.reasonArg());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.config().updateNotifyOnJoin() || !player.hasPermission(PERMISSION)) {
            return;
        }
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.updates().check().thenAccept(r -> {
            if (r.status() != UpdateChecker.Status.NEWER) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    plugin.messages().send(p, "version.join-notify",
                            "latest", r.latest(), "current", r.current(), "url", UpdateChecker.PAGE);
                }
            });
        }), 60L);
    }
}
