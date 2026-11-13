package clre20.vaultBank.service;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.CurrencyItems;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.currency.Teller;
import clre20.vaultBank.economy.Account;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 玩家操作（指令與介面共用同一套邏輯與訊息）。只在主執行緒呼叫。
 */
public final class Actions {

    private final VaultBank plugin;

    public Actions(VaultBank plugin) {
        this.plugin = plugin;
    }

    private String money(BigDecimal v) {
        return plugin.format(v);
    }

    // ---------- 轉帳 ----------

    public boolean pay(Player from, String targetName, String amountText) {
        Bank bank = plugin.bank();
        Account target = bank.byName(targetName);
        if (target == null) {
            plugin.messages().send(from, "error.unknown-player", "player", targetName);
            return false;
        }
        if (target.id().equals(from.getUniqueId())) {
            plugin.messages().send(from, "pay.self");
            return false;
        }
        BigDecimal amount = Money.parse(amountText);
        if (amount == null || amount.signum() <= 0) {
            plugin.messages().send(from, "error.invalid-amount", "input", amountText);
            return false;
        }
        if (!Money.fitsScale(amount, plugin.config().decimalPlaces())) {
            plugin.messages().send(from, "error.too-many-decimals", "digits", plugin.config().decimalPlaces());
            return false;
        }
        if (amount.compareTo(plugin.config().payMinAmount()) < 0) {
            plugin.messages().send(from, "pay.below-min", "min", money(plugin.config().payMinAmount()));
            return false;
        }
        if (!target.accepting()) {
            plugin.messages().send(from, "pay.not-accepting", "player", target.name());
            return false;
        }
        bank.getOrCreate(from.getUniqueId(), from.getName());
        Bank.Transfer t = bank.transfer(from.getUniqueId(), target.id(), amount, plugin.config().payFeePercent());
        switch (t.result()) {
            case OK -> {
                if (t.fee().signum() > 0) {
                    plugin.messages().send(from, "pay.sent-fee", "player", target.name(), "amount", money(amount),
                            "fee", money(t.fee()));
                } else {
                    plugin.messages().send(from, "pay.sent", "player", target.name(), "amount", money(amount));
                }
                Player online = Bukkit.getPlayer(target.id());
                if (online != null) {
                    plugin.messages().send(online, "pay.received", "player", from.getName(), "amount", money(amount));
                }
                return true;
            }
            case INSUFFICIENT -> plugin.messages().send(from, "error.insufficient",
                    "balance", money(bank.balance(from.getUniqueId())));
            case MAX_BALANCE -> plugin.messages().send(from, "pay.target-max", "player", target.name());
            default -> plugin.messages().send(from, "error.failed");
        }
        return false;
    }

    public void togglePay(Player player) {
        Account a = plugin.bank().getOrCreate(player.getUniqueId(), player.getName());
        boolean now = !a.accepting();
        plugin.bank().setAccepting(player.getUniqueId(), now);
        plugin.messages().send(player, now ? "pay.toggle-on" : "pay.toggle-off");
    }

    // ---------- 支票 ----------

    public boolean cheque(Player player, String amountText) {
        if (plugin.tooFast(player)) {
            return false;
        }
        long amount = Money.parseWhole(amountText, 1_000_000_000_000L);
        if (amount <= 0) {
            plugin.messages().send(player, "cheque.invalid-amount", "input", amountText);
            return false;
        }
        Teller.ChequeResult r = plugin.teller().issueCheque(player, amount);
        BigDecimal fee = plugin.teller().chequeFee(amount);
        switch (r) {
            case OK -> {
                plugin.persistAfterWithdraw(player);
                if (fee.signum() > 0) {
                    plugin.messages().send(player, "cheque.issued-fee", "amount", money(BigDecimal.valueOf(amount)),
                            "fee", money(fee));
                } else {
                    plugin.messages().send(player, "cheque.issued", "amount", money(BigDecimal.valueOf(amount)));
                }
                return true;
            }
            case BELOW_MIN -> plugin.messages().send(player, "cheque.below-min",
                    "min", money(BigDecimal.valueOf(plugin.config().chequeMinAmount())));
            case ABOVE_MAX -> plugin.messages().send(player, "cheque.above-max",
                    "max", money(BigDecimal.valueOf(plugin.config().chequeMaxAmount())));
            case NO_SPACE -> plugin.messages().send(player, "error.no-space");
            case INSUFFICIENT -> plugin.messages().send(player, "error.insufficient",
                    "balance", money(plugin.bank().balance(player.getUniqueId())));
            default -> plugin.messages().send(player, "error.failed");
        }
        return false;
    }

    // ---------- 提取 ----------

    /** 指令：自動配。 */
    public boolean withdrawAmount(Player player, String amountText) {
        long amount = Money.parseWhole(amountText, 10_000_000_000L);
        if (amount <= 0) {
            plugin.messages().send(player, "withdraw.invalid-amount", "input", amountText);
            return false;
        }
        Map<Denomination, Integer> plan = plugin.teller().plan(amount);
        if (plan == null) {
            plugin.messages().send(player, "withdraw.cannot-split", "amount", money(BigDecimal.valueOf(amount)));
            return false;
        }
        return withdraw(player, plan);
    }

    /** 指令：指定面額與數量。 */
    public boolean withdrawDenomination(Player player, String valueText, String countText) {
        Denomination d = parseDenomination(valueText);
        if (d == null) {
            plugin.messages().send(player, "withdraw.unknown-denomination", "input", valueText);
            return false;
        }
        long count = Money.parseWhole(countText, plugin.config().withdrawMaxItems());
        if (count <= 0) {
            plugin.messages().send(player, "withdraw.invalid-count", "input", countText,
                    "max", plugin.config().withdrawMaxItems());
            return false;
        }
        return withdraw(player, Map.of(d, (int) count));
    }

    public Denomination parseDenomination(String text) {
        long v = Money.parseWhole(text, 1000);
        return v <= 0 ? null : Denomination.byValue((int) v);
    }

    /** 介面與指令共用；回傳 null 表示成功，否則是錯誤訊息鍵。 */
    public String withdrawError(Player player, Map<Denomination, Integer> selection) {
        if (plugin.cooling(player)) {
            return "error.too-fast";
        }
        Teller.WithdrawResult r = plugin.teller().withdraw(player, selection);
        if (r == Teller.WithdrawResult.OK) {
            plugin.persistAfterWithdraw(player);
        }
        return switch (r) {
            case OK -> null;
            case EMPTY -> "withdraw.empty";
            case DISABLED -> "withdraw.disabled";
            case TOO_MANY -> "withdraw.too-many";
            case NO_SPACE -> "error.no-space";
            case INSUFFICIENT -> "error.insufficient";
            case FAILED -> "error.failed";
        };
    }

    public boolean withdraw(Player player, Map<Denomination, Integer> selection) {
        String error = withdrawError(player, selection);
        if (error == null) {
            plugin.messages().send(player, "withdraw.done",
                    "amount", money(BigDecimal.valueOf(Teller.value(selection))),
                    "count", Teller.count(selection),
                    "balance", money(plugin.bank().balance(player.getUniqueId())));
            return true;
        }
        plugin.messages().send(player, error,
                "balance", money(plugin.bank().balance(player.getUniqueId()) == null ? BigDecimal.ZERO
                        : plugin.bank().balance(player.getUniqueId())),
                "max", plugin.config().withdrawMaxItems());
        return false;
    }

    // ---------- 存入 ----------

    /** 指令：存入手上那一疊。 */
    public void depositHand(Player player) {
        if (plugin.tooFast(player)) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (!plugin.items().isOurs(hand)) {
            plugin.messages().send(player, "deposit.not-currency");
            return;
        }
        inv.setItemInMainHand(null);
        report(player, plugin.teller().deposit(player, List.of(hand)));
    }

    /** 指令：存入背包裡所有的貨幣與支票（主背包與副手）。 */
    public void depositAll(Player player) {
        if (plugin.tooFast(player)) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        CurrencyItems ci = plugin.items();
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (ci.isOurs(storage[i])) {
                taken.add(storage[i]);
                inv.setItem(i, null);
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (ci.isOurs(off)) {
            taken.add(off);
            inv.setItemInOffHand(null);
        }
        if (taken.isEmpty()) {
            plugin.messages().send(player, "deposit.nothing");
            return;
        }
        report(player, plugin.teller().deposit(player, taken));
    }

    /** 顯示存入結果並把退回的物品還給玩家。 */
    public void report(Player player, Teller.DepositResult r) {
        Teller.giveBack(player, r.returned());
        if (r.credited().signum() > 0) {
            plugin.persistAfterDeposit(player);
        }
        if (r.overMax()) {
            plugin.messages().send(player, "deposit.over-max", "max", money(plugin.config().maxBalance()));
            return;
        }
        if (r.credited().signum() > 0) {
            plugin.messages().send(player, "deposit.done", "amount", money(r.credited()), "count", r.accepted(),
                    "balance", money(plugin.bank().balance(player.getUniqueId())));
        }
        if (r.forged() > 0) {
            plugin.messages().send(player, plugin.config().confiscateInvalid() ? "deposit.forged-confiscated"
                    : "deposit.forged", "count", r.forged());
        }
        if (r.usedCheques() > 0) {
            plugin.messages().send(player, plugin.config().confiscateInvalid() ? "deposit.used-cheque-confiscated"
                    : "deposit.used-cheque", "count", r.usedCheques());
        }
        if (r.credited().signum() <= 0 && r.forged() == 0 && r.usedCheques() == 0) {
            plugin.messages().send(player, "deposit.nothing");
        }
    }
}
