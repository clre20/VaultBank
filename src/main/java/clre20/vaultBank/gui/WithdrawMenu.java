package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.currency.Teller;
import clre20.vaultBank.util.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * 提取介面（54 格）：每一欄是一種面額（1、5、10、50、100、500、1000），上下按鈕加減數量；
 * 「系統自動配」輸入金額後自動用最大面額配好，之後還能自己調整，最後按「確認提取」。
 */
public final class WithdrawMenu extends Menu {

    private static final int[] ROW_DELTA = {10, 1, 0, -1, -10};
    private static final int BACK = 45;
    private static final int CLEAR = 47;
    private static final int AUTO = 48;
    private static final int TOTAL = 49;
    private static final int CONFIRM = 51;

    private final Map<Denomination, Integer> selection = new EnumMap<>(Denomination.class);
    private String errorKey;

    public WithdrawMenu(VaultBank plugin, Player viewer) {
        super(plugin, viewer);
        create(54, "gui.withdraw.title");
    }

    WithdrawMenu(VaultBank plugin, Player viewer, Map<Denomination, Integer> preset) {
        this(plugin, viewer);
        selection.putAll(preset);
    }

    private static Denomination column(int col) {
        Denomination[] all = Denomination.values();
        int i = col - 1;
        return i >= 0 && i < all.length ? all[i] : null;
    }

    @Override
    protected void render() {
        fill(0, 54);
        for (int col = 1; col <= 7; col++) {
            Denomination d = column(col);
            boolean on = plugin.config().denominationEnabled(d);
            for (int row = 0; row < 5; row++) {
                int slot = row * 9 + col;
                int delta = ROW_DELTA[row];
                if (!on) {
                    inventory.setItem(slot, row == 2
                            ? button(Material.BARRIER, "gui.withdraw.disabled", "value", d.value())
                            : filler());
                    continue;
                }
                if (delta == 0) {
                    int n = selection.getOrDefault(d, 0);
                    ItemStack icon = MainMenu.icon(plugin, d.id(), "gui.withdraw.denomination", this,
                            "value", d.value(), "count", n,
                            "subtotal", plugin.format(BigDecimal.valueOf((long) d.value() * n)));
                    icon.setAmount(Math.max(1, Math.min(n, 64)));
                    inventory.setItem(slot, icon);
                } else {
                    inventory.setItem(slot, button(delta > 0 ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.RED_STAINED_GLASS_PANE,
                            delta > 0 ? "gui.withdraw.add" : "gui.withdraw.remove",
                            "n", Math.abs(delta), "value", d.value()));
                }
            }
        }
        inventory.setItem(BACK, button(Material.ARROW, "gui.common.back"));
        inventory.setItem(CLEAR, button(Material.BUCKET, "gui.withdraw.clear"));
        inventory.setItem(AUTO, button(Material.WRITABLE_BOOK, "gui.withdraw.auto"));
        Player p = plugin.getServer().getPlayer(viewer);
        BigDecimal balance = p == null ? BigDecimal.ZERO : plugin.bank().getOrCreate(p.getUniqueId(), p.getName()).balance();
        long total = Teller.value(selection);
        inventory.setItem(TOTAL, button(Material.GOLD_INGOT, "gui.withdraw.total",
                "amount", plugin.format(BigDecimal.valueOf(total)), "count", Teller.count(selection),
                "balance", plugin.format(balance), "max", plugin.config().withdrawMaxItems()));
        if (errorKey != null) {
            inventory.setItem(CONFIRM, button(Material.RED_CONCRETE, "gui.withdraw.error",
                    "reason", plugin.messages().plain(errorKey, "balance", plugin.format(balance),
                            "max", plugin.config().withdrawMaxItems())));
        } else {
            inventory.setItem(CONFIRM, button(total > 0 ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                    "gui.withdraw.confirm", "amount", plugin.format(BigDecimal.valueOf(total))));
        }
    }

    @Override
    protected void onButton(Player player, int slot) {
        if (!allowed(player, "vaultbank.withdraw")) {
            return;
        }
        errorKey = null;
        int row = slot / 9;
        int col = slot % 9;
        if (row < 5 && col >= 1 && col <= 7) {
            Denomination d = column(col);
            int delta = ROW_DELTA[row];
            if (d == null || delta == 0 || !plugin.config().denominationEnabled(d)) {
                return;
            }
            int max = plugin.config().withdrawMaxItems();
            int current = selection.getOrDefault(d, 0);
            int othersCount = (int) (Teller.count(selection) - current);
            int next = Math.max(0, Math.min(current + delta, max - othersCount));
            if (next == 0) {
                selection.remove(d);
            } else {
                selection.put(d, next);
            }
            render();
            return;
        }
        switch (slot) {
            case BACK -> new MainMenu(plugin, player).open(player);
            case CLEAR -> {
                selection.clear();
                render();
            }
            case AUTO -> plugin.chatInput().ask(player, "input.withdraw-amount",
                    (pl, text) -> {
                        long amount = Money.parseWhole(text, 10_000_000_000L);
                        Map<Denomination, Integer> plan = amount > 0 ? plugin.teller().plan(amount) : null;
                        if (amount <= 0) {
                            plugin.messages().send(pl, "withdraw.invalid-amount", "input", text);
                        } else if (plan == null) {
                            plugin.messages().send(pl, "withdraw.cannot-split", "amount", plugin.format(BigDecimal.valueOf(amount)));
                        }
                        WithdrawMenu next = new WithdrawMenu(plugin, pl, plan != null ? plan : selection);
                        if (plan != null && Teller.count(plan) > plugin.config().withdrawMaxItems()) {
                            next.errorKey = "withdraw.too-many";
                        }
                        next.open(pl);
                    },
                    () -> new WithdrawMenu(plugin, player, selection).open(player));
            case CONFIRM -> {
                if (selection.isEmpty()) {
                    errorKey = "withdraw.empty";
                    render();
                    return;
                }
                Map<Denomination, Integer> snapshot = new EnumMap<>(selection);
                String error = plugin.actions().withdrawError(player, snapshot);
                if (error == null) {
                    plugin.messages().send(player, "withdraw.done",
                            "amount", plugin.format(BigDecimal.valueOf(Teller.value(snapshot))),
                            "count", Teller.count(snapshot),
                            "balance", plugin.format(plugin.bank().balance(player.getUniqueId())));
                    selection.clear();
                } else {
                    errorKey = error;
                }
                render();
            }
            default -> {
            }
        }
    }
}
