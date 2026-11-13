package clre20.vaultBank.pack;

import clre20.httpHub.api.HttpHubService;
import clre20.httpHub.api.Route;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 只有 HttpHub 啟用時才會載入這個類別（沒裝 HttpHub 時不會出現 NoClassDefFoundError）。
 */
final class HubBridge {

    private final Plugin plugin;
    private Route route;

    HubBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 放上材質包；HttpHub 沒有運作時回傳 false。 */
    boolean serve(byte[] zip) {
        HttpHubService hub = HttpHubService.get();
        if (hub == null) {
            return false;
        }
        route = hub.serve(plugin, "pack.zip", zip, "application/zip");
        return true;
    }

    String url(Player player) {
        HttpHubService hub = HttpHubService.get();
        if (hub == null || route == null) {
            return null;
        }
        return hub.url(player, route);
    }

    void unregister() {
        HttpHubService hub = HttpHubService.get();
        if (hub != null) {
            hub.unregisterAll(plugin);
        }
        route = null;
    }
}
