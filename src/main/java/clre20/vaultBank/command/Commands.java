package clre20.vaultBank.command;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.economy.Exchange;
import clre20.vaultBank.config.PluginConfig;
import clre20.vaultBank.gui.CurrencyMenu;
import clre20.vaultBank.gui.ExchangeMenu;
import clre20.vaultBank.economy.Account;
import clre20.vaultBank.economy.Baltop;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.economy.TxType;
import clre20.vaultBank.gui.DepositMenu;
import clre20.vaultBank.gui.LogMenu;
import clre20.vaultBank.gui.MainMenu;
import clre20.vaultBank.gui.WithdrawMenu;
import clre20.vaultBank.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /vaultbank（/vb）以及 /money、/eco、/baltop、/pay。介面能做的事都有對應的子指令。
 */
public final class Commands implements TabExecutor {

    /** 子指令 → 權限（help 依權限只列出能用的）。 */
    private static final Map<String, String> SUBS = new LinkedHashMap<>();

    static {
        SUBS.put("help", null);
        SUBS.put("menu", "vaultbank.use");
        SUBS.put("balance", "vaultbank.balance");
        SUBS.put("pay", "vaultbank.pay");
        SUBS.put("paytoggle", "vaultbank.paytoggle");
        SUBS.put("baltop", "vaultbank.baltop");
        SUBS.put("deposit", "vaultbank.deposit");
        SUBS.put("withdraw", "vaultbank.withdraw");
        SUBS.put("cheque", "vaultbank.cheque");
        SUBS.put("exchange", "vaultbank.exchange");
        SUBS.put("log", "vaultbank.log");
        SUBS.put("eco", "vaultbank.admin.eco");
        SUBS.put("give", "vaultbank.admin.give");
        SUBS.put("audit", "vaultbank.admin.audit");
        SUBS.put("reload", "vaultbank.reload");
        SUBS.put("version", "vaultbank.version");
    }

    private final VaultBank plugin;

    public Commands(VaultBank plugin) {
        this.plugin = plugin;
    }

    private String money(Currency cur, BigDecimal v) {
        return plugin.format(cur, v);
    }

    private Currency primary() {
        return plugin.config().currencies().primary();
    }

    /** 參數是啟用中的幣種（不送訊息）。 */
    private Currency cur(String text) {
        return plugin.actions().findCurrency(text);
    }

    // ---------- 執行 ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.economyActive()) {
            plugin.messages().send(sender, "error.inactive");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "money" -> balance(sender, args, 0);
            case "eco" -> eco(sender, args, 0);
            case "baltop" -> baltop(sender, args, 0);
            case "pay" -> pay(sender, args, 0);
            default -> main(sender, args);
        }
        return true;
    }

    private void main(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                if (check(sender, "vaultbank.use")) {
                    new MainMenu(plugin, p).open(p);
                }
            } else {
                help(sender);
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "menu" -> {
                Player p = player(sender);
                if (p != null && check(sender, "vaultbank.use")) {
                    new MainMenu(plugin, p).open(p);
                }
            }
            case "balance", "bal", "money" -> balance(sender, args, 1);
            case "pay" -> pay(sender, args, 1);
            case "paytoggle" -> {
                Player p = player(sender);
                if (p != null && check(sender, "vaultbank.paytoggle")) {
                    plugin.actions().togglePay(p);
                }
            }
            case "baltop", "top" -> baltop(sender, args, 1);
            case "deposit" -> deposit(sender, args);
            case "withdraw" -> withdraw(sender, args);
            case "cheque", "check" -> cheque(sender, args);
            case "exchange", "rate", "rates" -> exchange(sender, args, sub);
            case "log" -> log(sender, args);
            case "eco" -> eco(sender, args, 1);
            case "give" -> give(sender, args);
            case "audit" -> audit(sender, args);
            case "reload" -> {
                if (check(sender, "vaultbank.reload")) {
                    plugin.reload();
                    plugin.messages().send(sender, "reload.done", "config", plugin.config().lastFilled(),
                            "messages", plugin.messages().lastFilled());
                }
            }
            case "version" -> {
                if (check(sender, "vaultbank.version")) {
                    plugin.updateNotifier().showTo(sender);
                }
            }
            default -> plugin.messages().send(sender, "error.unknown-command");
        }
    }

    private void help(CommandSender sender) {
        plugin.messages().line(sender, "help.header", "version", plugin.getPluginMeta().getVersion());
        for (Map.Entry<String, String> e : SUBS.entrySet()) {
            if (e.getValue() == null || sender.hasPermission(e.getValue())) {
                plugin.messages().line(sender, "help." + e.getKey());
            }
        }
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "no-permission");
        return false;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        plugin.messages().send(sender, "error.player-only");
        return null;
    }

    /** 找帳戶：先查自己的資料，再看線上玩家（不連 Mojang）。 */
    private Account target(CommandSender sender, String name) {
        Account a = plugin.bank().byName(name);
        if (a == null) {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                a = plugin.bank().getOrCreate(online.getUniqueId(), online.getName());
            }
        }
        if (a == null) {
            plugin.messages().send(sender, "error.unknown-player", "player", name);
        }
        return a;
    }

    private void balance(CommandSender sender, String[] args, int at) {
        if (args.length > at) {
            if (!check(sender, "vaultbank.balance.others")) {
                return;
            }
            Account a = target(sender, args[at]);
            if (a != null) {
                showBalances(sender, a, false);
            }
            return;
        }
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.balance")) {
            return;
        }
        showBalances(sender, plugin.bank().getOrCreate(p.getUniqueId(), p.getName()), true);
    }

    /** 只有一種幣種時一行；多種時每個幣種一行。 */
    private void showBalances(CommandSender sender, Account a, boolean self) {
        List<Currency> list = plugin.config().currencies().allEnabled();
        if (list.size() == 1) {
            Currency c = list.get(0);
            plugin.messages().send(sender, self ? "balance.self-ranked" : "balance.other-ranked", "player", a.name(),
                    "balance", money(c, Bank.get(a, c)), "rank", rankText(a.id(), c));
            return;
        }
        plugin.messages().send(sender, self ? "balance.header-self" : "balance.header-other", "player", a.name());
        for (Currency c : list) {
            plugin.messages().line(sender, c.features().baltop() ? "balance.line" : "balance.line-no-rank",
                    "currency", plugin.currencyName(c), "balance", money(c, Bank.get(a, c)), "rank", rankText(a.id(), c));
        }
    }

    private String rankText(java.util.UUID id, Currency c) {
        int rank = c.features().baltop() ? plugin.baltop().rankOf(id, c.id()) : 0;
        return rank > 0 ? plugin.messages().plain("balance.rank", "rank", rank) : plugin.messages().plain("balance.no-rank");
    }

    private void pay(CommandSender sender, String[] args, int at) {
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.pay")) {
            return;
        }
        if (args.length != at + 2 && args.length != at + 3) {
            plugin.messages().send(sender, "usage.pay");
            return;
        }
        Currency c = args.length == at + 3 ? plugin.actions().parseCurrency(sender, args[at + 2]) : primary();
        if (c != null) {
            plugin.actions().pay(p, args[at], args[at + 1], c);
        }
    }

    /** /baltop [幣種] [頁]。 */
    private void baltop(CommandSender sender, String[] args, int at) {
        if (!check(sender, "vaultbank.baltop")) {
            return;
        }
        Currency c = primary();
        int i = at;
        if (args.length > i && Money.parseWhole(args[i], 100_000) <= 0) {
            c = plugin.actions().parseCurrency(sender, args[i]);
            if (c == null) {
                return;
            }
            i++;
        }
        int page = 1;
        if (args.length > i) {
            long v = Money.parseWhole(args[i], 100_000);
            if (v <= 0 || args.length > i + 1) {
                plugin.messages().send(sender, "usage.baltop");
                return;
            }
            page = (int) v;
        }
        if (!c.features().baltop()) {
            plugin.messages().send(sender, "baltop.disabled", "currency", plugin.currencyName(c));
            return;
        }
        Baltop.Snapshot snap = plugin.baltop().snapshot(c.id());
        List<Baltop.Entry> list = snap.entries();
        int size = plugin.config().baltopPageLength();
        int pages = Math.max(1, (list.size() + size - 1) / size);
        page = Math.min(page, pages);
        plugin.messages().line(sender, "baltop.header", "page", page, "pages", pages, "total", money(c, snap.total()),
                "currency", plugin.currencyName(c));
        for (int n = (page - 1) * size; n < Math.min(list.size(), page * size); n++) {
            Baltop.Entry e = list.get(n);
            boolean you = sender instanceof Player p && p.getUniqueId().equals(e.id());
            plugin.messages().line(sender, you ? "baltop.entry-you" : "baltop.entry",
                    "rank", n + 1, "player", e.name(), "balance", money(c, e.balance()));
        }
        if (list.isEmpty()) {
            plugin.messages().line(sender, "baltop.empty");
        }
    }

    private void deposit(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.deposit")) {
            return;
        }
        if (args.length == 1) {
            new DepositMenu(plugin, p).open(p);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "hand" -> plugin.actions().depositHand(p);
            case "all" -> plugin.actions().depositAll(p);
            default -> plugin.messages().send(sender, "usage.deposit");
        }
    }

    /**
     * /vb withdraw                       開介面（多個幣種時先選幣種）
     * /vb withdraw &lt;幣種&gt;              開該幣種的介面
     * /vb withdraw &lt;金額&gt; [幣種]        系統自動配
     * /vb withdraw &lt;面額&gt; &lt;數量&gt; [幣種]  指定面額
     */
    private void withdraw(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.withdraw")) {
            return;
        }
        switch (args.length) {
            case 1 -> CurrencyMenu.open(plugin, p, CurrencyMenu.Purpose.WITHDRAW);
            case 2 -> {
                Currency c = cur(args[1]);
                if (c == null && plugin.actions().disabledCurrency(sender, args[1])) {
                    return;
                }
                if (c != null) {
                    if (!c.features().physical()) {
                        plugin.messages().send(p, "withdraw.currency-disabled", "currency", plugin.currencyName(c));
                    } else {
                        new WithdrawMenu(plugin, p, c).open(p);
                    }
                } else {
                    plugin.actions().withdrawAmount(p, args[1], primary());
                }
            }
            case 3 -> {
                Currency c = cur(args[2]);
                if (c == null && plugin.actions().disabledCurrency(sender, args[2])) {
                    return;
                }
                if (c != null) {
                    plugin.actions().withdrawAmount(p, args[1], c);
                } else {
                    plugin.actions().withdrawDenomination(p, args[1], args[2], primary());
                }
            }
            case 4 -> {
                Currency c = plugin.actions().parseCurrency(sender, args[3]);
                if (c != null) {
                    plugin.actions().withdrawDenomination(p, args[1], args[2], c);
                }
            }
            default -> plugin.messages().send(sender, "usage.withdraw");
        }
    }

    /** /vb cheque [金額] [幣種]、/vb cheque &lt;幣種&gt;。 */
    private void cheque(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.cheque")) {
            return;
        }
        switch (args.length) {
            case 1 -> CurrencyMenu.open(plugin, p, CurrencyMenu.Purpose.CHEQUE);
            case 2 -> {
                Currency c = cur(args[1]);
                if (c == null && plugin.actions().disabledCurrency(sender, args[1])) {
                    return;
                }
                if (c != null) {
                    askCheque(p, c);
                } else {
                    plugin.actions().cheque(p, args[1], primary());
                }
            }
            case 3 -> {
                Currency c = plugin.actions().parseCurrency(sender, args[2]);
                if (c != null) {
                    plugin.actions().cheque(p, args[1], c);
                }
            }
            default -> plugin.messages().send(sender, "usage.cheque");
        }
    }

    private void askCheque(Player p, Currency c) {
        if (!c.features().cheque()) {
            plugin.messages().send(p, "cheque.disabled", "currency", plugin.currencyName(c));
            return;
        }
        plugin.chatInput().ask(p, "input.cheque-amount", (pl, text) -> {
            if (pl.hasPermission("vaultbank.cheque")) {
                plugin.actions().cheque(pl, text, c);
            }
        }, null, "currency", plugin.currencyName(c));
    }

    /** /vb exchange、/vb exchange &lt;付出&gt; &lt;換到&gt; &lt;金額&gt;、/vb exchange rates（或 /vb rates）。 */
    private void exchange(CommandSender sender, String[] args, String sub) {
        if (!check(sender, "vaultbank.exchange")) {
            return;
        }
        if (!plugin.exchange().enabled()) {
            plugin.messages().send(sender, "exchange.disabled");
            return;
        }
        if (!sub.equals("exchange") || (args.length == 2 && args[1].equalsIgnoreCase("rates"))) {
            rates(sender);
            return;
        }
        if (args.length == 1) {
            Player p = player(sender);
            if (p != null) {
                new ExchangeMenu(plugin, p).open(p);
            }
            return;
        }
        if (args.length != 4) {
            plugin.messages().send(sender, "usage.exchange");
            return;
        }
        Player p = player(sender);
        if (p == null) {
            return;
        }
        Currency pay = plugin.actions().parseCurrency(sender, args[1]);
        Currency get = pay == null ? null : plugin.actions().parseCurrency(sender, args[2]);
        if (get != null) {
            plugin.actions().exchange(p, pay, get, args[3]);
        }
    }

    private void rates(CommandSender sender) {
        plugin.messages().line(sender, "exchange.rates-header",
                "fee", plugin.config().exchangeFeePercent().stripTrailingZeros().toPlainString());
        for (PluginConfig.ExchangePair pair : plugin.exchange().pairs()) {
            Currency from = plugin.currency(pair.from());
            Currency to = plugin.currency(pair.to());
            BigDecimal r = plugin.exchange().rate(pair);
            String key = pair.floating() ? "exchange.rate-floating" : "exchange.rate-fixed";
            plugin.messages().line(sender, pair.allowReverse() ? key : key + "-one-way",
                    "from", plugin.currencyName(from), "to", plugin.currencyName(to),
                    "rate", clre20.vaultBank.service.Actions.rateText(r),
                    "inverse", clre20.vaultBank.service.Actions.rateText(BigDecimal.ONE.divide(r, java.math.MathContext.DECIMAL64)),
                    "base", clre20.vaultBank.service.Actions.rateText(pair.rate()));
        }
    }

    private void log(CommandSender sender, String[] args) {
        if (!check(sender, "vaultbank.log")) {
            return;
        }
        // /vb log、/vb log <頁>、/vb log <玩家> [頁]
        if (args.length == 1 || (args.length == 2 && Money.parseWhole(args[1], 100_000) > 0)) {
            Player p = player(sender);
            if (p == null) {
                return;
            }
            int page = args.length == 2 ? (int) Money.parseWhole(args[1], 100_000) - 1 : 0;
            if (args.length == 1) {
                LogMenu.open(plugin, p, p.getUniqueId(), p.getName(), 0, false);
            } else {
                LogMenu.sendChat(plugin, sender, p.getUniqueId(), p.getName(), page);
            }
            return;
        }
        if (!check(sender, "vaultbank.log.others")) {
            return;
        }
        Account a = target(sender, args[1]);
        if (a == null) {
            return;
        }
        int page = 0;
        if (args.length >= 3) {
            long v = Money.parseWhole(args[2], 100_000);
            if (v <= 0) {
                plugin.messages().send(sender, "usage.log");
                return;
            }
            page = (int) v - 1;
        }
        if (sender instanceof Player p && args.length == 2) {
            LogMenu.open(plugin, p, a.id(), a.name(), 0, false);
        } else {
            LogMenu.sendChat(plugin, sender, a.id(), a.name(), page);
        }
    }

    /** /eco &lt;give|take|set&gt; &lt;玩家&gt; &lt;金額&gt; [幣種]、/eco reset &lt;玩家&gt; [幣種]。 */
    private void eco(CommandSender sender, String[] args, int at) {
        if (!check(sender, "vaultbank.admin.eco")) {
            return;
        }
        if (args.length < at + 2) {
            plugin.messages().send(sender, "usage.eco");
            return;
        }
        String action = args[at].toLowerCase(Locale.ROOT);
        Account a = target(sender, args[at + 1]);
        if (a == null) {
            return;
        }
        String actor = sender.getName();
        Bank bank = plugin.bank();
        if (action.equals("reset")) {
            if (args.length > at + 3) {
                plugin.messages().send(sender, "usage.eco");
                return;
            }
            Currency c = args.length == at + 3 ? plugin.actions().parseCurrency(sender, args[at + 2]) : primary();
            if (c == null) {
                return;
            }
            bank.set(a.id(), c, c.defaultBalance(), TxType.ADMIN_RESET, actor);
            plugin.messages().send(sender, "eco.reset", "player", a.name(), "balance", money(c, Bank.get(a, c)));
            return;
        }
        if (args.length != at + 3 && args.length != at + 4) {
            plugin.messages().send(sender, "usage.eco");
            return;
        }
        Currency c = args.length == at + 4 ? plugin.actions().parseCurrency(sender, args[at + 3]) : primary();
        if (c == null) {
            return;
        }
        BigDecimal amount = Money.parse(args[at + 2]);
        if (amount == null || (amount.signum() <= 0 && !action.equals("set"))) {
            plugin.messages().send(sender, "error.invalid-amount", "input", args[at + 2]);
            return;
        }
        if (!Money.fitsScale(amount, c.scale())) {
            plugin.actions().decimalsError(sender, c);
            return;
        }
        Bank.Result r = switch (action) {
            case "give" -> bank.deposit(a.id(), c, amount, TxType.ADMIN_GIVE, null, actor, null);
            case "take" -> bank.withdraw(a.id(), c, amount, TxType.ADMIN_TAKE, null, actor, null);
            case "set" -> bank.set(a.id(), c, amount, TxType.ADMIN_SET, actor);
            default -> null;
        };
        if (r == null) {
            plugin.messages().send(sender, "usage.eco");
            return;
        }
        switch (r) {
            case OK -> plugin.messages().send(sender, "eco." + action, "player", a.name(), "amount", money(c, amount),
                    "balance", money(c, Bank.get(a, c)));
            case INSUFFICIENT -> plugin.messages().send(sender, "eco.insufficient", "player", a.name(),
                    "balance", money(c, Bank.get(a, c)));
            case MAX_BALANCE -> plugin.messages().send(sender, "eco.max", "max", money(c, c.maxBalance()));
            default -> plugin.messages().send(sender, "error.failed");
        }
    }

    /** /vb give &lt;玩家&gt; &lt;面額&gt; [數量]；面額是元的數字，或任何幣種的面額名稱（例如 penguin_1）。 */
    private void give(CommandSender sender, String[] args) {
        if (!check(sender, "vaultbank.admin.give")) {
            return;
        }
        if (args.length < 3 || args.length > 4) {
            plugin.messages().send(sender, "usage.give");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "error.not-online", "player", args[1]);
            return;
        }
        Denomination d = plugin.actions().parseDenomination(primary(), args[2]);
        if (d == null) {
            plugin.messages().send(sender, "withdraw.unknown-denomination", "input", args[2],
                    "list", String.join("、", denominationIds()));
            return;
        }
        int count = 1;
        if (args.length == 4) {
            long v = Money.parseWhole(args[3], 2304);
            if (v <= 0) {
                plugin.messages().send(sender, "withdraw.invalid-count", "input", args[3], "max", 2304);
                return;
            }
            count = (int) v;
        }
        plugin.teller().mint(target, d, count);
        plugin.messages().send(sender, "give.done", "player", target.getName(), "value", d.value(), "count", count,
                "money", money(d.currency(), BigDecimal.valueOf(d.value())), "currency", plugin.currencyName(d.currency()));
    }

    /** /vb audit [幣種]：流通量、未兌現支票、帳戶總額（支票統計在背景查詢）。 */
    private void audit(CommandSender sender, String[] args) {
        if (!check(sender, "vaultbank.admin.audit")) {
            return;
        }
        List<Currency> list;
        if (args.length >= 2) {
            Currency c = plugin.actions().parseCurrency(sender, args[1]);
            if (c == null) {
                return;
            }
            list = List.of(c);
        } else {
            list = plugin.config().currencies().allEnabled();
        }
        plugin.cheques().flushAsync();
        plugin.database().call(plugin.database()::chequeStatsByCurrency).thenAccept(stats ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.messages().line(sender, "audit.header");
                    for (Currency c : list) {
                        plugin.messages().line(sender, "audit.currency", "currency", plugin.currencyName(c));
                        long outstandingValue = 0;
                        for (Denomination d : c.denominations()) {
                            long minted = plugin.circulation().minted(d);
                            long redeemed = plugin.circulation().redeemed(d);
                            long out = minted - redeemed;
                            outstandingValue += out * d.value();
                            plugin.messages().line(sender, out < 0 ? "audit.line-bad" : "audit.line", "value", d.value(),
                                    "money", money(c, BigDecimal.valueOf(d.value())),
                                    "minted", minted, "redeemed", redeemed, "outstanding", out);
                        }
                        if (!c.denominations().isEmpty()) {
                            plugin.messages().line(sender, "audit.money-total", "amount", money(c, BigDecimal.valueOf(outstandingValue)));
                        }
                        long[] cs = stats.getOrDefault(c.id(), new long[]{0, 0});
                        plugin.messages().line(sender, "audit.cheques", "count", cs[0], "amount", money(c, BigDecimal.valueOf(cs[1])));
                        BigDecimal total = BigDecimal.ZERO;
                        for (Account a : plugin.bank().all()) {
                            total = total.add(Bank.get(a, c));
                        }
                        plugin.messages().line(sender, "audit.accounts", "count", plugin.bank().size(), "amount", money(c, total));
                    }
                }));
    }

    // ---------- 補全 ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "money" -> args.length == 1 && sender.hasPermission("vaultbank.balance.others")
                    ? players(args[0]) : List.of();
            case "eco" -> ecoTab(sender, args, 0);
            case "baltop" -> args.length == 1 && sender.hasPermission("vaultbank.baltop") ? filter(currencies(), args[0]) : List.of();
            case "pay" -> payTab(sender, args, 0);
            default -> mainTab(sender, args);
        };
    }

    private List<String> mainTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> e : SUBS.entrySet()) {
                if ((e.getValue() == null || sender.hasPermission(e.getValue())) && e.getKey().startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(e.getKey());
                }
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String perm = SUBS.get(sub);
        if (perm != null && !sender.hasPermission(perm)) {
            return List.of();
        }
        return switch (sub) {
            case "balance" -> args.length == 2 && sender.hasPermission("vaultbank.balance.others") ? players(args[1]) : List.of();
            case "pay" -> payTab(sender, args, 1);
            case "baltop" -> args.length == 2 ? filter(currencies(), args[1]) : List.of();
            case "deposit" -> args.length == 2 ? filter(List.of("hand", "all"), args[1]) : List.of();
            case "withdraw" -> withdrawTab(args);
            case "cheque" -> args.length == 2 ? filter(currencies(), args[1])
                    : args.length == 3 && cur(args[1]) == null ? filter(currencies(), args[2]) : List.of();
            case "exchange" -> switch (args.length) {
                case 2 -> {
                    List<String> o = new ArrayList<>(currencies());
                    o.add(0, "rates");
                    yield filter(o, args[1]);
                }
                case 3 -> filter(currencies(), args[2]);
                default -> List.of();
            };
            case "log" -> args.length == 2 && sender.hasPermission("vaultbank.log.others") ? players(args[1]) : List.of();
            case "eco" -> ecoTab(sender, args, 1);
            case "give" -> switch (args.length) {
                case 2 -> online(args[1]);
                case 3 -> filter(denominationIds(), args[2]);
                default -> List.of();
            };
            case "audit" -> args.length == 2 ? filter(currencies(), args[1]) : List.of();
            default -> List.of();
        };
    }

    /** withdraw [幣種|金額|面額] [幣種|數量] [幣種]。 */
    private List<String> withdrawTab(String[] args) {
        return switch (args.length) {
            case 2 -> {
                List<String> o = new ArrayList<>(currencies());
                o.addAll(denominations(primary()));
                yield filter(o, args[1]);
            }
            case 3 -> cur(args[1]) != null ? List.of() : filter(currencies(), args[2]);
            case 4 -> cur(args[2]) != null ? List.of() : filter(currencies(), args[3]);
            default -> List.of();
        };
    }

    private List<String> payTab(CommandSender sender, String[] args, int at) {
        if (!sender.hasPermission("vaultbank.pay")) {
            return List.of();
        }
        if (args.length == at + 1) {
            return players(args[at]);
        }
        if (args.length == at + 3) {
            List<String> out = new ArrayList<>();
            for (Currency c : plugin.config().currencies().allEnabled()) {
                if (c.features().pay()) {
                    out.add(c.id());
                }
            }
            return filter(out, args[at + 2]);
        }
        return List.of();
    }

    private List<String> ecoTab(CommandSender sender, String[] args, int at) {
        if (!sender.hasPermission("vaultbank.admin.eco")) {
            return List.of();
        }
        if (args.length == at + 1) {
            return filter(List.of("give", "take", "set", "reset"), args[at]);
        }
        if (args.length == at + 2) {
            return players(args[at + 1]);
        }
        boolean reset = args[at].equalsIgnoreCase("reset");
        if ((reset && args.length == at + 3) || (!reset && args.length == at + 4)) {
            return filter(currencies(), args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> currencies() {
        return plugin.actions().currencyIds();
    }

    private List<String> denominations(Currency c) {
        List<String> out = new ArrayList<>();
        for (Denomination d : c.denominations()) {
            if (d.enabled()) {
                out.add(String.valueOf(d.value()));
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** 給 give：元用數字，其他幣種用面額名稱。 */
    private List<String> denominationIds() {
        List<String> out = new ArrayList<>(denominations(primary()));
        for (Currency c : plugin.config().currencies().allEnabled()) {
            if (!c.primary()) {
                for (Denomination d : c.denominations()) {
                    out.add(d.id());
                }
            }
        }
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    private static List<String> online(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return filter(names, prefix);
    }

    /** 線上玩家；輸入 2 個字以上時也列出有帳戶的離線玩家（最多 30 個）。 */
    private List<String> players(String prefix) {
        List<String> out = online(prefix);
        if (prefix.length() >= 2) {
            for (String n : plugin.bank().knownNames(prefix, 30)) {
                if (!out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}
