package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * VaultBank 的箱子介面。InventoryHolder 本身就是插件簽署：其他插件可用
 * {@code holder instanceof clre20.vaultBank.gui.Menu} 或 {@link #owner()} 辨認；按鈕物品另有 PDC 標籤 vaultbank:gui。
 * 預設所有點擊都取消，只有左鍵點按鈕會動作。
 */
public abstract class Menu implements InventoryHolder {

    protected final VaultBank plugin;
    protected final UUID viewer;
    protected Inventory inventory;

    protected Menu(VaultBank plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer.getUniqueId();
    }

    /** 這個介面屬於哪個插件。 */
    public final VaultBank owner() {
        return plugin;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected Inventory create(int size, String titleKey, Object... vars) {
        inventory = Bukkit.createInventory(this, size, plugin.messages().component(titleKey, vars));
        return inventory;
    }

    /** 建立並開啟（下一 tick 執行，避免在點擊事件中直接換介面）。 */
    public void open(Player player) {
        UUID id = player.getUniqueId();
        // 下一 tick 才換介面（不在點擊事件中關閉或開啟介面）
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                return;
            }
            if (plugin.packs() != null && plugin.packs().isBedrock(id)
                    && p.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                // 基岩版（Geyser）用假的箱子方塊顯示介面：直接從一個介面換到另一個（尤其大小不同時）
                // 用戶端會把新介面立刻關掉，所以先關閉舊的，等幾 tick 再開新的
                p.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> show(id), plugin.config().bedrockOpenDelayTicks());
                return;
            }
            show(id);
        });
    }

    private void show(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            return;
        }
        render();
        p.openInventory(inventory);
    }

    /** 重新畫出所有格子。 */
    protected abstract void render();

    /** 左鍵點了上方介面的某一格（已取消事件、已確認是左鍵）。 */
    protected abstract void onButton(Player player, int slot);

    /** 需要讓物品照原版拿放的介面覆寫這個（例如存入介面）。預設全部取消，只處理左鍵按鈕。 */
    protected void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || !event.isLeftClick() || event.isShiftClick()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            onButton(player, event.getRawSlot());
        }
    }

    protected void onDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }

    protected void onClose(Player player, InventoryCloseEvent.Reason reason) {
    }

    // ---------- 按鈕物品 ----------

    protected ItemStack button(Material material, String key, Object... vars) {
        return decorate(new ItemStack(material), key, vars);
    }

    /** 套用 messages 的 name 與 lore，並加上 PDC 標籤。 */
    protected ItemStack decorate(ItemStack item, String key, Object... vars) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().item(key + ".name", vars));
        List<Component> lore = plugin.messages().itemLore(key + ".lore", vars);
        meta.lore(lore.isEmpty() ? null : lore);
        meta.addItemFlags(ItemFlag.values());
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "gui"), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    protected ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        meta.setHideTooltip(true);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "gui"), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    protected void fill(int from, int to) {
        ItemStack f = filler();
        for (int i = from; i < to; i++) {
            inventory.setItem(i, f);
        }
    }

    /** 每次點擊都重新檢查權限，介面開著時被拔權限也不能操作。 */
    protected boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(player, "no-permission");
        player.closeInventory();
        return false;
    }

    void dispatchClick(InventoryClickEvent event) {
        onClick(event);
    }

    void dispatchDrag(InventoryDragEvent event) {
        onDrag(event);
    }

    void dispatchClose(Player player, InventoryCloseEvent.Reason reason) {
        onClose(player, reason);
    }
}
