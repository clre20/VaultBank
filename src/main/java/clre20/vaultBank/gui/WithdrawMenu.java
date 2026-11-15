package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.currency.Teller;
import clre20.vaultBank.util.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提取介面（54 格，一次一個幣種）：每一欄是一種面額（由小到大，最多 7 種），上下按鈕加減數量；
 * 「系統自動配」輸入金額後自動用最大面額配好，之後還能自己調整，最後按「確認提取」。
 */
public final class WithdrawMenu extends Menu {

    private static final int[] ROW_DELTA = {10, 1, 0, -1, -10};
    private static final int BACK = 45;
    private static final int CLEAR = 47;
    private static final int AUTO = 48;
    private static final int TOTAL = 49;
    private static final int CONFIRM = 51;

    private final Map<Denomination, Integer> selection = new LinkedHashMap<>();
    private final Currency currency;
    private final List<Denomination> columns;
    private String errorKey;

    public WithdrawMenu(VaultBank plugin, Player viewer, Currency currency) {
        super(plugin, viewer);
        this.currency = currency;
        List<Denomination> asc = new ArrayList<>(currency.denominations());
        Collections.reverse(asc);
        this.columns = asc.subList(0, Math.min(7, asc.size()));
        create(54, "gui.withdraw.title", "currency", plugin.currencyName(currency));
    }

    WithdrawMenu(VaultBank plugin, Player viewer, Currency currency, Map<Denomination, Integer> preset) {
        this(plugin, viewer, currency);
        selection.putAll(preset);
    }

    /** 第幾欄（1～7）是哪個面額；面額少於 7 種時置中。 */
    private Denomination column(int col) {
        int i = col - 1 - (7 - columns.size()) / 2;
        return i >= 0 && i < columns.size() ? columns.get(i) : null;
    }

    private String money(long v) {
        return plugin.format(currency, BigDecimal.valueOf(v));
    }

    @Override
    protected void render() {
        fill(0, 54);
        for (int col = 1; col <= 7; col++) {
            Denomination d = column(col);
            if (d == null) {
                continue;
            }
            boolean on = d.enabled();
            for (int row = 0; row < 5; row++) {
                int slot = row * 9 + col;
                int delta = ROW_DELTA[row];
                if (!on) {
                    inventory.setItem(slot, row == 2
                            ? button(Material.BARRIER, "gui.withdraw.disabled", "value", d.value(), "money", money(d.value()))
                            : filler());
                    continue;
                }
                if (delta == 0) {
                    int n = selection.getOrDefault(d, 0);
                    ItemStack icon = MainMenu.icon(plugin, plugin.appearance().model(d), "gui.withdraw.denomination", this,
                            "value", d.value(), "money", money(d.value()), "count", n,
                            "subtotal", money(d.value() * n));
                    icon.setAmount(Math.max(1, Math.min(n, 64)));
                    inventory.setItem(slot, icon);
                } else {
                    inventory.setItem(slot, button(delta > 0 ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.RED_STAINED_GLASS_PANE,
                            delta > 0 ? "gui.withdraw.add" : "gui.withdraw.remove",
                            "n", Math.abs(delta), "value", d.value(), "money", money(d.value())));
                }
            }
        }
        inventory.setItem(BACK, button(Material.ARROW, "gui.common.back"));
        inventory.setItem(CLEAR, button(Material.BUCKET, "gui.withdraw.clear"));
        inventory.setItem(AUTO, button(Material.WRITABLE_BOOK, "gui.withdraw.auto"));
        Player p = plugin.getServer().getPlayer(viewer);
        BigDecimal balance = p == null ? BigDecimal.ZERO
                : Bank.get(plugin.bank().getOrCreate(p.getUniqueId(), p.getName()), currency);
        long total = Teller.value(selection);
        inventory.setItem(TOTAL, button(Material.GOLD_INGOT, "gui.withdraw.total",
                "amount", money(total), "count", Teller.count(selection),
                "balance", plugin.format(currency, balance), "max", plugin.config().withdrawMaxItems()));
        if (errorKey != null) {
            inventory.setItem(CONFIRM, button(Material.RED_CONCRETE, "gui.withdraw.error",
                    "reason", plugin.messages().plain(errorKey, "balance", plugin.format(currency, balance),
                            "max", plugin.config().withdrawMaxItems(), "currency", plugin.currencyName(currency))));
        } else {
            inventory.setItem(CONFIRM, button(total > 0 ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                    "gui.withdraw.confirm", "amount", money(total)));
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
            if (d == null || delta == 0 || !d.enabled()) {
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
                        Map<Denomination, Integer> plan = amount > 0 ? plugin.teller().plan(currency, amount) : null;
                        if (amount <= 0) {
                            plugin.messages().send(pl, "withdraw.invalid-amount", "input", text);
                        } else if (plan == null) {
                            plugin.messages().send(pl, "withdraw.cannot-split", "amount", money(amount));
                        }
                        WithdrawMenu next = new WithdrawMenu(plugin, pl, currency, plan != null ? plan : selection);
                        if (plan != null && Teller.count(plan) > plugin.config().withdrawMaxItems()) {
                            next.errorKey = "withdraw.too-many";
                        }
                        next.open(pl);
                    },
                    () -> new WithdrawMenu(plugin, player, currency, selection).open(player),
                    "currency", plugin.currencyName(currency));
            case CONFIRM -> {
                if (selection.isEmpty()) {
                    errorKey = "withdraw.empty";
                    render();
                    return;
                }
                Map<Denomination, Integer> snapshot = new LinkedHashMap<>(selection);
                String error = plugin.actions().withdrawError(player, snapshot);
                if (error == null) {
                    plugin.messages().send(player, "withdraw.done",
                            "amount", money(Teller.value(snapshot)),
                            "count", Teller.count(snapshot),
                            "balance", plugin.format(currency, plugin.bank().balance(player.getUniqueId(), currency)));
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
