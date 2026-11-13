package clre20.vaultBank.command;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Denomination;
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

    private String money(BigDecimal v) {
        return plugin.format(v);
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
            case "log" -> log(sender, args);
            case "eco" -> eco(sender, args, 1);
            case "give" -> give(sender, args);
            case "audit" -> audit(sender);
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
                plugin.messages().send(sender, "balance.other-ranked", "player", a.name(), "balance", money(a.balance()),
                        "rank", rankText(a.id()));
            }
            return;
        }
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.balance")) {
            return;
        }
        Account a = plugin.bank().getOrCreate(p.getUniqueId(), p.getName());
        plugin.messages().send(sender, "balance.self-ranked", "balance", money(a.balance()), "rank", rankText(a.id()));
    }

    private String rankText(java.util.UUID id) {
        int rank = plugin.baltop().rankOf(id);
        return rank > 0 ? plugin.messages().plain("balance.rank", "rank", rank) : plugin.messages().plain("balance.no-rank");
    }

    private void pay(CommandSender sender, String[] args, int at) {
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.pay")) {
            return;
        }
        if (args.length != at + 2) {
            plugin.messages().send(sender, "usage.pay");
            return;
        }
        plugin.actions().pay(p, args[at], args[at + 1]);
    }

    private void baltop(CommandSender sender, String[] args, int at) {
        if (!check(sender, "vaultbank.baltop")) {
            return;
        }
        int page = 1;
        if (args.length > at) {
            long v = Money.parseWhole(args[at], 100_000);
            if (v <= 0) {
                plugin.messages().send(sender, "usage.baltop");
                return;
            }
            page = (int) v;
        }
        Baltop.Snapshot snap = plugin.baltop().snapshot();
        List<Baltop.Entry> list = snap.entries();
        int size = plugin.config().baltopPageLength();
        int pages = Math.max(1, (list.size() + size - 1) / size);
        page = Math.min(page, pages);
        plugin.messages().line(sender, "baltop.header", "page", page, "pages", pages, "total", money(snap.total()));
        for (int i = (page - 1) * size; i < Math.min(list.size(), page * size); i++) {
            Baltop.Entry e = list.get(i);
            boolean you = sender instanceof Player p && p.getUniqueId().equals(e.id());
            plugin.messages().line(sender, you ? "baltop.entry-you" : "baltop.entry",
                    "rank", i + 1, "player", e.name(), "balance", money(e.balance()));
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

    private void withdraw(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.withdraw")) {
            return;
        }
        switch (args.length) {
            case 1 -> new WithdrawMenu(plugin, p).open(p);
            case 2 -> plugin.actions().withdrawAmount(p, args[1]);
            case 3 -> plugin.actions().withdrawDenomination(p, args[1], args[2]);
            default -> plugin.messages().send(sender, "usage.withdraw");
        }
    }

    private void cheque(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null || !check(sender, "vaultbank.cheque")) {
            return;
        }
        if (args.length == 2) {
            plugin.actions().cheque(p, args[1]);
            return;
        }
        if (args.length == 1) {
            plugin.chatInput().ask(p, "input.cheque-amount", (pl, text) -> {
                if (pl.hasPermission("vaultbank.cheque")) {
                    plugin.actions().cheque(pl, text);
                }
            }, null);
            return;
        }
        plugin.messages().send(sender, "usage.cheque");
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
            bank.set(a.id(), plugin.config().defaultBalance(), TxType.ADMIN_RESET, actor);
            plugin.messages().send(sender, "eco.reset", "player", a.name(), "balance", money(a.balance()));
            return;
        }
        if (args.length != at + 3) {
            plugin.messages().send(sender, "usage.eco");
            return;
        }
        BigDecimal amount = Money.parse(args[at + 2]);
        if (amount == null || (amount.signum() <= 0 && !action.equals("set"))) {
            plugin.messages().send(sender, "error.invalid-amount", "input", args[at + 2]);
            return;
        }
        if (!Money.fitsScale(amount, plugin.config().decimalPlaces())) {
            plugin.messages().send(sender, "error.too-many-decimals", "digits", plugin.config().decimalPlaces());
            return;
        }
        Bank.Result r = switch (action) {
            case "give" -> bank.deposit(a.id(), amount, TxType.ADMIN_GIVE, null, actor, null);
            case "take" -> bank.withdraw(a.id(), amount, TxType.ADMIN_TAKE, null, actor, null);
            case "set" -> bank.set(a.id(), amount, TxType.ADMIN_SET, actor);
            default -> null;
        };
        if (r == null) {
            plugin.messages().send(sender, "usage.eco");
            return;
        }
        switch (r) {
            case OK -> plugin.messages().send(sender, "eco." + action, "player", a.name(), "amount", money(amount),
                    "balance", money(a.balance()));
            case INSUFFICIENT -> plugin.messages().send(sender, "eco.insufficient", "player", a.name(),
                    "balance", money(a.balance()));
            case MAX_BALANCE -> plugin.messages().send(sender, "eco.max", "max", money(plugin.config().maxBalance()));
            default -> plugin.messages().send(sender, "error.failed");
        }
    }

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
        Denomination d = plugin.actions().parseDenomination(args[2]);
        if (d == null) {
            plugin.messages().send(sender, "withdraw.unknown-denomination", "input", args[2]);
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
        plugin.messages().send(sender, "give.done", "player", target.getName(), "value", d.value(), "count", count);
    }

    private void audit(CommandSender sender) {
        if (!check(sender, "vaultbank.admin.audit")) {
            return;
        }
        plugin.messages().line(sender, "audit.header");
        long outstandingValue = 0;
        for (Denomination d : Denomination.values()) {
            long minted = plugin.circulation().minted(d);
            long redeemed = plugin.circulation().redeemed(d);
            long out = minted - redeemed;
            outstandingValue += out * d.value();
            plugin.messages().line(sender, out < 0 ? "audit.line-bad" : "audit.line", "value", d.value(),
                    "minted", minted, "redeemed", redeemed, "outstanding", out);
        }
        plugin.messages().line(sender, "audit.money-total", "amount", money(BigDecimal.valueOf(outstandingValue)));
        plugin.messages().line(sender, "audit.cheques", "count", plugin.cheques().outstandingCount(),
                "amount", money(BigDecimal.valueOf(plugin.cheques().outstandingTotal())));
        BigDecimal total = BigDecimal.ZERO;
        for (Account a : plugin.bank().all()) {
            total = total.add(a.balance());
        }
        plugin.messages().line(sender, "audit.accounts", "count", plugin.bank().size(), "amount", money(total));
    }

    // ---------- 補全 ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "money" -> args.length == 1 && sender.hasPermission("vaultbank.balance.others")
                    ? players(args[0]) : List.of();
            case "eco" -> ecoTab(sender, args, 0);
            case "baltop" -> List.of();
            case "pay" -> args.length == 1 && sender.hasPermission("vaultbank.pay") ? players(args[0]) : List.of();
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
            case "pay" -> args.length == 2 ? players(args[1]) : List.of();
            case "deposit" -> args.length == 2 ? filter(List.of("hand", "all"), args[1]) : List.of();
            case "withdraw" -> args.length == 2 ? filter(denominations(), args[1]) : List.of();
            case "log" -> args.length == 2 && sender.hasPermission("vaultbank.log.others") ? players(args[1]) : List.of();
            case "eco" -> ecoTab(sender, args, 1);
            case "give" -> switch (args.length) {
                case 2 -> online(args[1]);
                case 3 -> filter(denominations(), args[2]);
                default -> List.of();
            };
            default -> List.of();
        };
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
        return List.of();
    }

    private List<String> denominations() {
        List<String> out = new ArrayList<>();
        for (Denomination d : Denomination.values()) {
            if (plugin.config().denominationEnabled(d)) {
                out.add(String.valueOf(d.value()));
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
