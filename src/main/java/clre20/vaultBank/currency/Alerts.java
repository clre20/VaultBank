package clre20.vaultBank.currency;

import clre20.vaultBank.VaultBank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** 偽造、重複兌現、流通量異常的警告：寫後台，並通知線上有 vaultbank.admin.alert 權限的人。 */
public final class Alerts {

    public static final String PERMISSION = "vaultbank.admin.alert";

    private final VaultBank plugin;

    public Alerts(VaultBank plugin) {
        this.plugin = plugin;
    }

    public void forged(Player player, int count) {
        notify("forged", "player", player.getName(), "count", count);
    }

    public void usedCheque(Player player, String serial) {
        notify("used-cheque", "player", player.getName(), "serial", serial == null ? "?" : serial);
    }

    public void overRedeemed(Denomination d, long minted, long redeemed) {
        notify("over-redeemed", "value", d.value(), "minted", minted, "redeemed", redeemed);
    }

    private void notify(String key, Object... vars) {
        plugin.getLogger().warning(plugin.messages().console("alert-" + key, vars));
        if (!plugin.config().alertAdmins()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERMISSION)) {
                plugin.messages().send(p, "alert." + key, vars);
            }
        }
    }
}
