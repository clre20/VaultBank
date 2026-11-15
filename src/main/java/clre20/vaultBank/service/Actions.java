package clre20.vaultBank.service;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.currency.CurrencyItems;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.currency.Teller;
import clre20.vaultBank.economy.Account;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.economy.Exchange;
import clre20.vaultBank.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 玩家操作（指令與介面共用同一套邏輯與訊息）。只在主執行緒呼叫。
 * 所有操作都指定幣種；功能開關（轉帳、支票、實體貨幣）在這裡與 Teller 檢查。
 */
public final class Actions {

    private final VaultBank plugin;

    public Actions(VaultBank plugin) {
        this.plugin = plugin;
    }

    private String money(Currency cur, BigDecimal v) {
        return plugin.format(cur, v);
    }

    private String name(Currency cur) {
        return plugin.currencyName(cur);
    }

    // ---------- 幣種 ----------

    /** 啟用中的幣種 id，給錯誤訊息與補全用。 */
    public List<String> currencyIds() {
        List<String> out = new ArrayList<>();
        for (Currency c : plugin.config().currencies().allEnabled()) {
            out.add(c.id());
        }
        return out;
    }

    /** 解析幣種參數（id 或顯示名稱，不分大小寫）；找不到或停用時送出訊息並回傳 null。 */
    public Currency parseCurrency(CommandSender sender, String text) {
        Currency c = findCurrency(text);
        if (c == null) {
            if (disabledCurrency(sender, text)) {
                return null;
            }
            plugin.messages().send(sender, "error.unknown-currency", "input", text,
                    "list", String.join("、", currencyIds()));
        }
        return c;
    }

    /** 是設定裡有但停用的幣種時送出「停用」訊息並回傳 true。 */
    public boolean disabledCurrency(CommandSender sender, String text) {
        Currency off = text == null ? null : plugin.config().currencies().get(text.trim());
        if (off != null && !off.enabled()) {
            plugin.messages().send(sender, "error.currency-disabled", "currency", name(off));
            return true;
        }
        return false;
    }

    /** 只找不送訊息。 */
    public Currency findCurrency(String text) {
        if (text == null) {
            return null;
        }
        Currency c = plugin.config().currencies().enabled(text);
        if (c != null) {
            return c;
        }
        for (Currency x : plugin.config().currencies().allEnabled()) {
            if (plugin.currencyName(x).equalsIgnoreCase(text.trim())) {
                return x;
            }
        }
        return null;
    }

    /**
     * 換成目前設定中的同一個幣種（/vb reload 後舊的物件可能還留在介面或輸入框的回呼裡）。
     * 已停用或移除時送出訊息並回傳 null。
     */
    private Currency fresh(CommandSender sender, Currency c) {
        Currency now = c == null ? null : plugin.config().currencies().enabled(c.id());
        if (now == null) {
            plugin.messages().send(sender, "error.currency-disabled", "currency", c == null ? "?" : name(c));
        }
        return now;
    }

    public Currency primary() {
        return plugin.config().currencies().primary();
    }

    /** 小數位數太多：整數幣種說「只能用整數」。 */
    public void decimalsError(CommandSender to, Currency cur) {
        if (cur.scale() == 0) {
            plugin.messages().send(to, "error.whole-number", "currency", name(cur));
        } else {
            plugin.messages().send(to, "error.too-many-decimals", "digits", cur.scale(), "currency", name(cur));
        }
    }

    // ---------- 轉帳 ----------

    public boolean pay(Player from, String targetName, String amountText, Currency cur) {
        cur = fresh(from, cur);
        if (cur == null) {
            return false;
        }
        if (!cur.features().pay()) {
            plugin.messages().send(from, "pay.disabled", "currency", name(cur));
            return false;
        }
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
        if (!Money.fitsScale(amount, cur.scale())) {
            decimalsError(from, cur);
            return false;
        }
        if (amount.compareTo(cur.payMin()) < 0) {
            plugin.messages().send(from, "pay.below-min", "min", money(cur, cur.payMin()));
            return false;
        }
        if (!target.accepting()) {
            plugin.messages().send(from, "pay.not-accepting", "player", target.name());
            return false;
        }
        bank.getOrCreate(from.getUniqueId(), from.getName());
        Bank.Transfer t = bank.transfer(from.getUniqueId(), target.id(), cur, amount, cur.payFeePercent());
        switch (t.result()) {
            case OK -> {
                if (t.fee().signum() > 0) {
                    plugin.messages().send(from, "pay.sent-fee", "player", target.name(), "amount", money(cur, amount),
                            "fee", money(cur, t.fee()));
                } else {
                    plugin.messages().send(from, "pay.sent", "player", target.name(), "amount", money(cur, amount));
                }
                Player online = Bukkit.getPlayer(target.id());
                if (online != null) {
                    plugin.messages().send(online, "pay.received", "player", from.getName(), "amount", money(cur, amount));
                }
                return true;
            }
            case INSUFFICIENT -> plugin.messages().send(from, "error.insufficient",
                    "balance", money(cur, bank.balance(from.getUniqueId(), cur)));
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

    public boolean cheque(Player player, String amountText, Currency cur) {
        cur = fresh(player, cur);
        if (cur == null) {
            return false;
        }
        if (!cur.features().cheque()) {
            plugin.messages().send(player, "cheque.disabled", "currency", name(cur));
            return false;
        }
        if (plugin.tooFast(player)) {
            return false;
        }
        long amount = Money.parseWhole(amountText, 1_000_000_000_000L);
        if (amount <= 0) {
            plugin.messages().send(player, "cheque.invalid-amount", "input", amountText);
            return false;
        }
        Teller.ChequeResult r = plugin.teller().issueCheque(player, cur, amount);
        BigDecimal fee = plugin.teller().chequeFee(cur, amount);
        switch (r) {
            case OK -> {
                plugin.persistAfterWithdraw(player);
                if (fee.signum() > 0) {
                    plugin.messages().send(player, "cheque.issued-fee", "amount", money(cur, BigDecimal.valueOf(amount)),
                            "fee", money(cur, fee));
                } else {
                    plugin.messages().send(player, "cheque.issued", "amount", money(cur, BigDecimal.valueOf(amount)));
                }
                return true;
            }
            case DISABLED -> plugin.messages().send(player, "cheque.disabled", "currency", name(cur));
            case BELOW_MIN -> plugin.messages().send(player, "cheque.below-min",
                    "min", money(cur, BigDecimal.valueOf(cur.chequeMin())));
            case ABOVE_MAX -> plugin.messages().send(player, "cheque.above-max",
                    "max", money(cur, BigDecimal.valueOf(cur.chequeMax())));
            case NO_SPACE -> plugin.messages().send(player, "error.no-space");
            case INSUFFICIENT -> plugin.messages().send(player, "error.insufficient",
                    "balance", money(cur, plugin.bank().balance(player.getUniqueId(), cur)));
            default -> plugin.messages().send(player, "error.failed");
        }
        return false;
    }

    // ---------- 提取 ----------

    /** 指令：自動配。 */
    public boolean withdrawAmount(Player player, String amountText, Currency cur) {
        cur = fresh(player, cur);
        if (cur == null) {
            return false;
        }
        if (!cur.features().physical()) {
            plugin.messages().send(player, "withdraw.currency-disabled", "currency", name(cur));
            return false;
        }
        long amount = Money.parseWhole(amountText, 10_000_000_000L);
        if (amount <= 0) {
            plugin.messages().send(player, "withdraw.invalid-amount", "input", amountText);
            return false;
        }
        Map<Denomination, Integer> plan = plugin.teller().plan(cur, amount);
        if (plan == null) {
            plugin.messages().send(player, "withdraw.cannot-split", "amount", money(cur, BigDecimal.valueOf(amount)));
            return false;
        }
        return withdraw(player, plan);
    }

    /** 指令：指定面額與數量。 */
    public boolean withdrawDenomination(Player player, String denomText, String countText, Currency cur) {
        cur = fresh(player, cur);
        if (cur == null) {
            return false;
        }
        Denomination d = parseDenomination(cur, denomText);
        if (d == null) {
            plugin.messages().send(player, "withdraw.unknown-denomination", "input", denomText,
                    "list", denominationList(cur));
            return false;
        }
        if (!d.currency().features().physical()) {
            plugin.messages().send(player, "withdraw.currency-disabled", "currency", name(d.currency()));
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

    /** 面額：面額 id（例如 penguin_1，任何幣種）或數字（指定幣種的面額）。 */
    public Denomination parseDenomination(Currency cur, String text) {
        if (text == null) {
            return null;
        }
        Denomination byId = plugin.config().currencies().denomination(text.toLowerCase(java.util.Locale.ROOT));
        if (byId != null) {
            return byId.currency().enabled() ? byId : null;
        }
        long v = Money.parseWhole(text, 1_000_000_000L);
        return v <= 0 ? null : cur.byValue(v);
    }

    /** 「1、5、10…」這種面額清單（只列可提取的）。 */
    public String denominationList(Currency cur) {
        StringJoiner j = new StringJoiner("、");
        List<Denomination> list = new ArrayList<>(cur.denominations());
        java.util.Collections.reverse(list);
        for (Denomination d : list) {
            if (d.enabled()) {
                j.add(String.valueOf(d.value()));
            }
        }
        return j.toString();
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
            case CURRENCY_DISABLED -> "withdraw.currency-disabled";
            case TOO_MANY -> "withdraw.too-many";
            case NO_SPACE -> "error.no-space";
            case INSUFFICIENT -> "error.insufficient";
            case FAILED -> "error.failed";
        };
    }

    public boolean withdraw(Player player, Map<Denomination, Integer> selection) {
        Currency cur = Teller.currencyOf(selection);
        if (cur == null) {
            cur = primary();
        }
        String error = withdrawError(player, selection);
        if (error == null) {
            plugin.messages().send(player, "withdraw.done",
                    "amount", money(cur, BigDecimal.valueOf(Teller.value(selection))),
                    "count", Teller.count(selection),
                    "balance", money(cur, plugin.bank().balance(player.getUniqueId(), cur)));
            return true;
        }
        plugin.messages().send(player, error, errorVars(player, cur));
        return false;
    }

    /** 錯誤訊息常用的變數。 */
    public Object[] errorVars(Player player, Currency cur) {
        BigDecimal bal = plugin.bank().balance(player.getUniqueId(), cur);
        return new Object[]{"balance", money(cur, bal == null ? BigDecimal.ZERO : bal),
                "max", plugin.config().withdrawMaxItems(), "currency", name(cur)};
    }

    // ---------- 兌換 ----------

    /** 指令：直接兌換。 */
    public boolean exchange(Player player, Currency pay, Currency get, String amountText) {
        if (!plugin.exchange().enabled()) {
            plugin.messages().send(player, "exchange.disabled");
            return false;
        }
        if (plugin.tooFast(player)) {
            return false;
        }
        BigDecimal amount = Money.parse(amountText);
        if (amount == null || amount.signum() <= 0) {
            plugin.messages().send(player, "error.invalid-amount", "input", amountText);
            return false;
        }
        plugin.bank().getOrCreate(player.getUniqueId(), player.getName());
        Exchange.Quote q = plugin.exchange().execute(player.getUniqueId(), pay, get, amount, null);
        if (q.status() == Exchange.Status.TOO_MANY_DECIMALS) {
            decimalsError(player, pay);
            return false;
        }
        if (q.pay() == null) {
            plugin.messages().send(player, exchangeErrorKey(q.status()), "from", name(pay), "to", name(get));
            return false;
        }
        return reportExchange(player, q);
    }

    /** 顯示兌換結果；成功回傳 true。 */
    public boolean reportExchange(Player player, Exchange.Quote q) {
        if (q.ok()) {
            if (q.fee().signum() > 0) {
                plugin.messages().send(player, "exchange.done-fee", quoteVars(q));
            } else {
                plugin.messages().send(player, "exchange.done", quoteVars(q));
            }
            return true;
        }
        plugin.messages().send(player, exchangeErrorKey(q.status()), q.pay() == null ? new Object[0] : quoteVars(q,
                "balance", money(q.pay(), plugin.bank().balance(player.getUniqueId(), q.pay()))));
        return false;
    }

    public static String exchangeErrorKey(Exchange.Status s) {
        return switch (s) {
            case DISABLED -> "exchange.disabled";
            case NO_PAIR -> "exchange.no-pair";
            case ONE_WAY -> "exchange.one-way";
            case TOO_MANY_DECIMALS -> "error.too-many-decimals";
            case TOO_SMALL -> "exchange.too-small";
            case OUT_OF_RANGE -> "exchange.out-of-range";
            case INSUFFICIENT -> "error.insufficient";
            case MAX_BALANCE -> "exchange.max";
            case CHANGED -> "exchange.changed";
            case INVALID -> "exchange.invalid-amount";
            default -> "error.failed";
        };
    }

    /** 報價的訊息變數：pay、get、fee、from、to、rate、digits。 */
    public Object[] quoteVars(Exchange.Quote q, Object... extra) {
        List<Object> v = new ArrayList<>(List.of(
                "pay", q.amountIn() == null ? "-" : money(q.pay(), q.amountIn()),
                "get", q.amountOut() == null ? "-" : money(q.get(), q.amountOut()),
                "fee", q.fee() == null ? "-" : money(q.get(), q.fee()),
                "from", name(q.pay()), "to", name(q.get()),
                "rate", q.rate() == null ? "-" : rateText(q.rate()),
                "digits", q.pay().scale()));
        v.addAll(List.of(extra));
        return v.toArray();
    }

    /** 匯率顯示：最多 6 位有效小數。 */
    public static String rateText(BigDecimal r) {
        BigDecimal v = r.round(new java.math.MathContext(8)).stripTrailingZeros();
        if (v.scale() > 6) {
            v = v.setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        }
        return v.toPlainString();
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
        if (r.any()) {
            plugin.persistAfterDeposit(player);
        }
        for (Map.Entry<Currency, BigDecimal> e : r.credited().entrySet()) {
            Currency cur = e.getKey();
            plugin.messages().send(player, "deposit.done", "amount", money(cur, e.getValue()),
                    "count", r.counts().getOrDefault(cur, 0),
                    "balance", money(cur, plugin.bank().balance(player.getUniqueId(), cur)));
        }
        for (Currency cur : r.overMax()) {
            plugin.messages().send(player, "deposit.over-max", "max", money(cur, cur.maxBalance()), "currency", name(cur));
        }
        if (r.forged() > 0) {
            plugin.messages().send(player, plugin.config().confiscateInvalid() ? "deposit.forged-confiscated"
                    : "deposit.forged", "count", r.forged());
        }
        if (r.usedCheques() > 0) {
            plugin.messages().send(player, plugin.config().confiscateInvalid() ? "deposit.used-cheque-confiscated"
                    : "deposit.used-cheque", "count", r.usedCheques());
        }
        if (r.disabled() > 0) {
            plugin.messages().send(player, "deposit.disabled", "count", r.disabled());
        }
        if (!r.any() && r.forged() == 0 && r.usedCheques() == 0 && r.overMax().isEmpty() && r.disabled() == 0) {
            plugin.messages().send(player, "deposit.nothing");
        }
    }
}
