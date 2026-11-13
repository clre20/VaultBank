package clre20.vaultBank.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/** 把事件轉給對應的介面；不是本插件的介面時第一步就返回。 */
public final class MenuListener implements Listener {

    private static Menu menu(InventoryHolder holder) {
        return holder instanceof Menu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Menu m = menu(event.getView().getTopInventory().getHolder(false));
        if (m != null) {
            m.dispatchClick(event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        Menu m = menu(event.getView().getTopInventory().getHolder(false));
        if (m != null) {
            m.dispatchDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu m = menu(event.getInventory().getHolder(false));
        if (m != null && event.getPlayer() instanceof Player p) {
            m.dispatchClose(p, event.getReason());
        }
    }

    /** 插件停用前關掉所有開著的介面（存入介面會把物品還給玩家）。 */
    public static void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (menu(p.getOpenInventory().getTopInventory().getHolder(false)) != null) {
                p.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            }
        }
    }
}
