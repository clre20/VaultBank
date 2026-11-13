package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.CurrencyItems;
import clre20.vaultBank.currency.Teller;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 存入介面（54 格）：上面 45 格放貨幣與支票（照原版拿放，但只收本插件的物品），最下面一列是按鈕（只吃左鍵）。
 * 按「確認存入」時在同一個 tick 內把物品拿走並入帳；關閉介面時把還留著的物品還給玩家。
 */
public final class DepositMenu extends Menu {

    private static final int AREA = 45;
    private static final int BACK = 45;
    private static final int HELP = 47;
    private static final int TOTAL = 49;
    private static final int CONFIRM = 53;

    private boolean refreshQueued;

    public DepositMenu(VaultBank plugin, Player viewer) {
        super(plugin, viewer);
        create(54, "gui.deposit.title");
    }

    @Override
    protected void render() {
        fill(AREA, 54);
        inventory.setItem(BACK, button(Material.ARROW, "gui.common.back"));
        inventory.setItem(HELP, button(Material.BOOK, "gui.deposit.help"));
        renderTotal();
    }

    private void renderTotal() {
        CurrencyItems ci = plugin.items();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        int invalid = 0;
        for (int i = 0; i < AREA; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            CurrencyItems.Identified id = ci.identify(item);
            switch (id.kind()) {
                case MONEY -> {
                    total = total.add(BigDecimal.valueOf((long) id.denomination().value() * item.getAmount()));
                    count += item.getAmount();
                }
                case CHEQUE -> {
                    if (plugin.cheques().isOutstanding(id.serial(), id.amount())) {
                        total = total.add(BigDecimal.valueOf(id.amount()));
                        count++;
                    } else {
                        invalid += item.getAmount();
                    }
                }
                default -> invalid += item.getAmount();
            }
        }
        inventory.setItem(TOTAL, button(Material.GOLD_INGOT, "gui.deposit.total",
                "amount", plugin.format(total), "count", count, "invalid", invalid));
        inventory.setItem(CONFIRM, button(total.signum() > 0 ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                "gui.deposit.confirm", "amount", plugin.format(total)));
    }

    /** 物品變動後下一 tick 更新總額（同一 tick 多次變動只更新一次）。 */
    private void queueRefresh() {
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshQueued = false;
            renderTotal();
        });
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < 54;
        if (top && raw >= AREA) {
            // 按鈕列
            event.setCancelled(true);
            if (event.getClick().isLeftClick() && !event.isShiftClick() && event.getWhoClicked() instanceof Player p) {
                onButton(p, raw);
            }
            return;
        }
        InventoryAction action = event.getAction();
        CurrencyItems ci = plugin.items();
        if (top) {
            switch (action) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY, DROP_ALL_SLOT,
                     DROP_ONE_SLOT, NOTHING -> {
                    // 拿出來一定可以
                }
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                    if (!ci.isOurs(event.getCursor())) {
                        event.setCancelled(true);
                        return;
                    }
                }
                case HOTBAR_SWAP -> {
                    ItemStack incoming = event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                            ? event.getWhoClicked().getInventory().getItemInOffHand()
                            : event.getHotbarButton() >= 0
                            ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;
                    if (incoming != null && !incoming.getType().isAir() && !ci.isOurs(incoming)) {
                        event.setCancelled(true);
                        return;
                    }
                }
                default -> {
                    event.setCancelled(true);
                    return;
                }
            }
        } else {
            // 玩家自己的背包：只擋「Shift 把非貨幣丟進介面」與「收集到游標」
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && !ci.isOurs(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
            if (action == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
                return;
            }
        }
        queueRefresh();
    }

    @Override
    protected void onDrag(InventoryDragEvent event) {
        boolean touchesTop = false;
        for (int raw : event.getRawSlots()) {
            if (raw < 54) {
                if (raw >= AREA) {
                    event.setCancelled(true);
                    return;
                }
                touchesTop = true;
            }
        }
        if (touchesTop && !plugin.items().isOurs(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        queueRefresh();
    }

    @Override
    protected void onButton(Player player, int slot) {
        switch (slot) {
            case BACK -> new MainMenu(plugin, player).open(player);
            case CONFIRM -> {
                if (!allowed(player, "vaultbank.deposit") || plugin.tooFast(player)) {
                    return;
                }
                List<ItemStack> items = takeAll();
                if (items.isEmpty()) {
                    plugin.messages().send(player, "deposit.nothing");
                    return;
                }
                Teller.DepositResult r = plugin.teller().deposit(player, items);
                plugin.actions().report(player, r);
                renderTotal();
            }
            default -> {
            }
        }
    }

    /** 把存入區的物品全部拿走（同一 tick 內，不會被關閉事件重複退回）。 */
    private List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < AREA; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) {
                out.add(item.clone());
                inventory.setItem(i, null);
            }
        }
        return out;
    }

    @Override
    protected void onClose(Player player, InventoryCloseEvent.Reason reason) {
        List<ItemStack> left = takeAll();
        Teller.giveBack(player, left);
    }
}
