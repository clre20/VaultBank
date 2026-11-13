package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.economy.TxType;
import clre20.vaultBank.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** 交易紀錄（54 格，每頁 45 筆）。資料在背景查詢，查完才開啟。 */
public final class LogMenu extends Menu {

    private static final int PER_PAGE = 45;
    private static final int BACK = 45;
    private static final int PREV = 48;
    private static final int INFO = 49;
    private static final int NEXT = 50;

    private final UUID target;
    private final String targetName;
    private final int page;
    private final int total;
    private final List<Database.LogRow> rows;
    private final boolean self;

    private LogMenu(VaultBank plugin, Player viewer, UUID target, String targetName, int page, int total,
                    List<Database.LogRow> rows, boolean self) {
        super(plugin, viewer);
        this.target = target;
        this.targetName = targetName;
        this.page = page;
        this.total = total;
        this.rows = rows;
        this.self = self;
        create(54, "gui.log.title", "player", targetName);
    }

    /** 查詢後開啟。self 為 true 時「返回」回主選單，否則直接關閉。 */
    public static void open(VaultBank plugin, Player viewer, UUID target, String targetName, int page, boolean self) {
        if (plugin.tooFast(viewer)) {
            return;
        }
        long since = System.currentTimeMillis() - plugin.config().logRetentionDays() * 86_400_000L;
        int p = Math.max(0, page);
        // 先把排隊中的紀錄寫進去，剛發生的交易才看得到
        plugin.bank().flushAsync();
        Database db = plugin.database();
        db.call(() -> {
            int count = db.countLogs(target.toString(), since);
            int pages = Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
            int fixed = Math.min(p, pages - 1);
            List<Database.LogRow> rows = db.queryLogs(target.toString(), since, fixed * PER_PAGE, PER_PAGE);
            return new Object[]{count, fixed, rows};
        }).thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Database.LogRow> rows = (List<Database.LogRow>) res[2];
            new LogMenu(plugin, viewer, target, targetName, (int) res[1], (int) res[0], rows, self).open(viewer);
        }));
    }

    @Override
    protected void render() {
        inventory.clear();
        fill(45, 54);
        SimpleDateFormat fmt = dateFormat(plugin);
        for (int i = 0; i < rows.size() && i < PER_PAGE; i++) {
            Database.LogRow r = rows.get(i);
            BigDecimal amount = parse(r.amount());
            TxType type = TxType.parse(r.type());
            String typeName = type == null ? r.type() : plugin.messages().plain("log.types." + type.key());
            inventory.setItem(i, button(amount.signum() >= 0 ? Material.LIME_DYE : Material.RED_DYE, "gui.log.entry",
                    "type", typeName,
                    "amount", (amount.signum() > 0 ? "+" : "") + plugin.format(amount),
                    "balance", r.balance() == null ? "-" : plugin.format(parse(r.balance())),
                    "time", fmt.format(new Date(r.time())),
                    "other", r.otherName() == null ? "-" : r.otherName(),
                    "detail", r.detail() == null ? "-" : r.detail()));
        }
        int pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
        inventory.setItem(BACK, button(self ? Material.ARROW : Material.BARRIER, self ? "gui.common.back" : "gui.common.close"));
        if (page > 0) {
            inventory.setItem(PREV, button(Material.ARROW, "gui.common.prev"));
        }
        if (page + 1 < pages) {
            inventory.setItem(NEXT, button(Material.ARROW, "gui.common.next"));
        }
        inventory.setItem(INFO, button(Material.PAPER, "gui.log.info", "page", page + 1, "pages", pages,
                "count", total, "days", plugin.config().logRetentionDays()));
    }

    static SimpleDateFormat dateFormat(VaultBank plugin) {
        try {
            return new SimpleDateFormat(plugin.messages().raw("log.date-format"));
        } catch (IllegalArgumentException e) {
            return new SimpleDateFormat("MM-dd HH:mm");
        }
    }

    private static BigDecimal parse(String s) {
        try {
            return new BigDecimal(s);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    protected void onButton(Player player, int slot) {
        boolean others = !player.getUniqueId().equals(target);
        if (!allowed(player, others ? "vaultbank.log.others" : "vaultbank.log")) {
            return;
        }
        switch (slot) {
            case BACK -> {
                if (self) {
                    new MainMenu(plugin, player).open(player);
                } else {
                    player.closeInventory();
                }
            }
            case PREV -> {
                if (page > 0) {
                    open(plugin, player, target, targetName, page - 1, self);
                }
            }
            case NEXT -> open(plugin, player, target, targetName, page + 1, self);
            default -> {
            }
        }
    }

    /** 給指令用：顯示在聊天室。 */
    public static void sendChat(VaultBank plugin, org.bukkit.command.CommandSender sender, UUID target,
                                String targetName, int page) {
        if (plugin.tooFast(sender)) {
            return;
        }
        long since = System.currentTimeMillis() - plugin.config().logRetentionDays() * 86_400_000L;
        int size = plugin.config().logPageSize();
        int p = Math.max(0, page);
        // 先把排隊中的紀錄寫進去，剛發生的交易才看得到
        plugin.bank().flushAsync();
        Database db = plugin.database();
        db.call(() -> {
            int count = db.countLogs(target.toString(), since);
            int pages = Math.max(1, (count + size - 1) / size);
            int fixed = Math.min(p, pages - 1);
            return new Object[]{count, fixed, pages, db.queryLogs(target.toString(), since, fixed * size, size)};
        }).thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
            @SuppressWarnings("unchecked")
            List<Database.LogRow> rows = (List<Database.LogRow>) res[3];
            plugin.messages().line(sender, "log.header", "player", targetName, "page", (int) res[1] + 1,
                    "pages", res[2], "count", res[0]);
            if (rows.isEmpty()) {
                plugin.messages().line(sender, "log.empty");
                return;
            }
            SimpleDateFormat fmt = dateFormat(plugin);
            for (Database.LogRow r : rows) {
                BigDecimal amount = parse(r.amount());
                TxType type = TxType.parse(r.type());
                plugin.messages().line(sender, "log.line",
                        "time", fmt.format(new Date(r.time())),
                        "type", type == null ? r.type() : plugin.messages().plain("log.types." + type.key()),
                        "amount", (amount.signum() > 0 ? "+" : "") + plugin.format(amount),
                        "balance", r.balance() == null ? "-" : plugin.format(parse(r.balance())),
                        "other", r.otherName() == null ? "-" : r.otherName(),
                        "detail", r.detail() == null ? "-" : r.detail());
            }
        }));
    }
}
