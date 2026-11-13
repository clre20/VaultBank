package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.storage.Database;
import clre20.vaultBank.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 所有帳戶都放在記憶體裡；改動時只標記髒資料，由定時批次寫入 SQLite。
 * 可以在任何執行緒呼叫（其他插件可能從非同步執行緒透過 Vault 扣款），每個帳戶以自己為鎖。
 */
public final class Bank {

    public enum Result { OK, NO_ACCOUNT, INSUFFICIENT, MAX_BALANCE, INVALID }

    /** 轉帳結果：實際扣掉的金額（含手續費）與手續費。 */
    public record Transfer(Result result, BigDecimal fee) {
    }

    private static final int MAX_QUEUED_LOGS = 200_000;

    private final VaultBank plugin;
    private final Database db;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, UUID> names = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Database.LogRow> logQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedLogs = new AtomicInteger();
    private volatile boolean logOverflowWarned;

    public Bank(VaultBank plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    // ---------- 載入 ----------

    /** 在 I/O 執行緒上呼叫。 */
    void loadRows(List<Database.AccountRow> rows) {
        for (Database.AccountRow row : rows) {
            UUID id;
            try {
                id = UUID.fromString(row.uuid());
            } catch (IllegalArgumentException e) {
                continue;
            }
            BigDecimal balance;
            try {
                balance = scale(new BigDecimal(row.balance()));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("帳戶 " + row.uuid() + " 的餘額格式錯誤（" + row.balance() + "），以 0 載入");
                balance = scale(BigDecimal.ZERO);
                dirty.add(id);
            }
            Account a = new Account(id, row.name(), balance, row.accepting());
            accounts.put(id, a);
            // 同名的帳戶（例如改名或離線 UUID）以餘額較高的為準；玩家登入後會自動指回正確的帳戶
            names.merge(row.name().toLowerCase(Locale.ROOT), id, (oldId, newId) -> {
                Account o = accounts.get(oldId);
                return o != null && o.balance().compareTo(a.balance()) >= 0 ? oldId : newId;
            });
        }
    }

    public void load() throws Exception {
        List<Database.AccountRow> rows = db.call(db::loadAccounts).get();
        loadRows(rows);
    }

    /** 匯入用：帳戶不存在時建立（回傳 false 表示已存在而略過）。 */
    boolean importAccount(UUID id, String name, BigDecimal balance, boolean accepting) {
        Account a = new Account(id, name, scale(balance), accepting);
        if (accounts.putIfAbsent(id, a) != null) {
            return false;
        }
        names.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
        dirty.add(id);
        log(id, TxType.IMPORT, null, null, a.balance(), a.balance(), "TheosisEconomy");
        return true;
    }

    // ---------- 查詢 ----------

    public int scaleDigits() {
        return plugin.config().decimalPlaces();
    }

    public BigDecimal scale(BigDecimal v) {
        return v.setScale(scaleDigits(), RoundingMode.HALF_UP);
    }

    public Account account(UUID id) {
        return id == null ? null : accounts.get(id);
    }

    /** 依名稱找帳戶（不分大小寫，只查自己的資料，不連 Mojang）。 */
    public Account byName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) {
            return null;
        }
        UUID id = names.get(name.toLowerCase(Locale.ROOT));
        return id == null ? null : accounts.get(id);
    }

    public Collection<Account> all() {
        return Collections.unmodifiableCollection(accounts.values());
    }

    public int size() {
        return accounts.size();
    }

    public List<String> knownNames(String prefix, int limit) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.name().toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(a.name());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    public BigDecimal balance(UUID id) {
        Account a = account(id);
        return a == null ? null : a.balance();
    }

    // ---------- 建立帳戶 ----------

    /** 玩家登入或第一次使用時建立帳戶；名稱改了就更新（這個名稱以後指向這個帳戶）。 */
    public Account getOrCreate(UUID id, String name) {
        return getOrCreate(id, name, true);
    }

    /**
     * 其他插件透過 Vault 用 OfflinePlayer 存提時建立帳戶。名稱可能不可靠（例如離線玩家的猜測 UUID），
     * 所以不會搶走已經屬於別的帳戶的名稱，也不會改既有帳戶的名稱。
     */
    public Account getOrCreateWeak(UUID id, String name) {
        return getOrCreate(id, name, false);
    }

    private Account getOrCreate(UUID id, String name, boolean authoritative) {
        String safeName = name == null || name.isEmpty() ? id.toString() : name;
        Account a = accounts.get(id);
        if (a == null) {
            Account created = new Account(id, safeName, scale(plugin.config().defaultBalance()), true);
            a = accounts.putIfAbsent(id, created);
            if (a == null) {
                String key = safeName.toLowerCase(Locale.ROOT);
                if (authoritative) {
                    names.put(key, id);
                } else {
                    names.putIfAbsent(key, id);
                }
                dirty.add(id);
                return created;
            }
        }
        if (authoritative && name != null && !name.isEmpty() && !a.name().equals(name)) {
            names.remove(a.name().toLowerCase(Locale.ROOT), id);
            a.name(name);
            dirty.add(id);
        }
        if (authoritative && name != null && !name.isEmpty()) {
            names.put(name.toLowerCase(Locale.ROOT), id);
        }
        return a;
    }

    // ---------- 改動 ----------

    /**
     * 存入。amount 必須大於 0。
     *
     * @param other     對方 UUID（轉帳等），可為 null
     * @param otherName 對方名稱或來源插件名稱，可為 null
     */
    public Result deposit(UUID id, BigDecimal amount, TxType type, UUID other, String otherName, String detail) {
        if (amount == null || amount.signum() <= 0) {
            return Result.INVALID;
        }
        Account a = account(id);
        if (a == null) {
            return Result.NO_ACCOUNT;
        }
        BigDecimal v = scale(amount);
        if (v.signum() <= 0) {
            return Result.INVALID;
        }
        BigDecimal after;
        synchronized (a) {
            after = a.balance().add(v);
            if (after.compareTo(plugin.config().maxBalance()) > 0) {
                return Result.MAX_BALANCE;
            }
            a.balance(after);
        }
        dirty.add(id);
        log(id, type, other, otherName, v, after, detail);
        return Result.OK;
    }

    public Result withdraw(UUID id, BigDecimal amount, TxType type, UUID other, String otherName, String detail) {
        if (amount == null || amount.signum() <= 0) {
            return Result.INVALID;
        }
        Account a = account(id);
        if (a == null) {
            return Result.NO_ACCOUNT;
        }
        BigDecimal v = scale(amount);
        if (v.signum() <= 0) {
            return Result.INVALID;
        }
        BigDecimal after;
        synchronized (a) {
            if (a.balance().compareTo(v) < 0) {
                return Result.INSUFFICIENT;
            }
            after = a.balance().subtract(v);
            a.balance(after);
        }
        dirty.add(id);
        log(id, type, other, otherName, v.negate(), after, detail);
        return Result.OK;
    }

    /** 直接設定餘額（管理員）。 */
    public Result set(UUID id, BigDecimal amount, TxType type, String actor) {
        if (amount == null || amount.signum() < 0) {
            return Result.INVALID;
        }
        Account a = account(id);
        if (a == null) {
            return Result.NO_ACCOUNT;
        }
        BigDecimal v = scale(amount);
        if (v.compareTo(plugin.config().maxBalance()) > 0) {
            return Result.MAX_BALANCE;
        }
        BigDecimal diff;
        synchronized (a) {
            diff = v.subtract(a.balance());
            a.balance(v);
        }
        dirty.add(id);
        log(id, type, null, actor, diff, v, null);
        return Result.OK;
    }

    /**
     * 轉帳：from 付 amount＋手續費，to 收 amount。兩個帳戶依 UUID 順序上鎖，避免互相等待。
     */
    public Transfer transfer(UUID from, UUID to, BigDecimal amount, BigDecimal feePercent) {
        Account a = account(from);
        Account b = account(to);
        if (a == null || b == null) {
            return new Transfer(Result.NO_ACCOUNT, BigDecimal.ZERO);
        }
        BigDecimal v = scale(amount);
        if (v.signum() <= 0 || from.equals(to)) {
            return new Transfer(Result.INVALID, BigDecimal.ZERO);
        }
        BigDecimal fee = scale(v.multiply(feePercent).divide(BigDecimal.valueOf(100), scaleDigits(), RoundingMode.HALF_UP));
        BigDecimal cost = v.add(fee);
        Account first = from.compareTo(to) < 0 ? a : b;
        Account second = first == a ? b : a;
        BigDecimal afterA;
        BigDecimal afterB;
        synchronized (first) {
            synchronized (second) {
                if (a.balance().compareTo(cost) < 0) {
                    return new Transfer(Result.INSUFFICIENT, fee);
                }
                afterB = b.balance().add(v);
                if (afterB.compareTo(plugin.config().maxBalance()) > 0) {
                    return new Transfer(Result.MAX_BALANCE, fee);
                }
                afterA = a.balance().subtract(cost);
                a.balance(afterA);
                b.balance(afterB);
            }
        }
        dirty.add(from);
        dirty.add(to);
        String feeText = fee.signum() > 0 ? "fee=" + Money.plain(fee) : null;
        log(from, TxType.PAY_OUT, to, b.name(), cost.negate(), afterA, feeText);
        log(to, TxType.PAY_IN, from, a.name(), v, afterB, null);
        return new Transfer(Result.OK, fee);
    }

    public void setAccepting(UUID id, boolean accepting) {
        Account a = account(id);
        if (a != null && a.accepting() != accepting) {
            a.accepting(accepting);
            dirty.add(id);
        }
    }

    // ---------- 交易紀錄 ----------

    public void log(UUID id, TxType type, UUID other, String otherName, BigDecimal amount, BigDecimal after, String detail) {
        if (!plugin.config().logEnabled()) {
            return;
        }
        if ((type == TxType.PLUGIN_IN || type == TxType.PLUGIN_OUT) && !plugin.config().logPluginTransactions()) {
            return;
        }
        if (queuedLogs.get() >= MAX_QUEUED_LOGS) {
            if (!logOverflowWarned) {
                logOverflowWarned = true;
                plugin.getLogger().warning("交易紀錄排隊太多，暫時不記錄新的紀錄（資料庫可能寫入失敗）");
            }
            return;
        }
        queuedLogs.incrementAndGet();
        logQueue.add(new Database.LogRow(System.currentTimeMillis(), type.name(), id.toString(),
                other == null ? null : other.toString(), otherName, Money.plain(amount),
                after == null ? null : Money.plain(after), detail));
    }

    // ---------- 存檔 ----------

    /** 把髒資料與排隊的紀錄排進 I/O 執行緒寫入。 */
    public void flushAsync() {
        if (dirty.isEmpty() && logQueue.isEmpty()) {
            return;
        }
        db.call(() -> {
            writeNow();
            return null;
        });
    }

    /** 在 I/O 執行緒上執行：先移除髒標記再讀值，讀完之後的改動會再次被標記。 */
    private void writeNow() throws Exception {
        List<Database.AccountRow> rows = new ArrayList<>();
        for (UUID id : dirty.toArray(new UUID[0])) {
            dirty.remove(id);
            Account a = accounts.get(id);
            if (a != null) {
                rows.add(new Database.AccountRow(id.toString(), a.name(), a.balance().toPlainString(), a.accepting()));
            }
        }
        try {
            db.saveAccounts(rows);
        } catch (Exception e) {
            for (Database.AccountRow r : rows) {
                dirty.add(UUID.fromString(r.uuid()));
            }
            throw e;
        }
        List<Database.LogRow> logs = new ArrayList<>();
        Database.LogRow r;
        while (logs.size() < 5000 && (r = logQueue.poll()) != null) {
            logs.add(r);
        }
        queuedLogs.addAndGet(-logs.size());
        if (!logs.isEmpty()) {
            db.insertLogs(logs);
            logOverflowWarned = false;
        }
        if (!logQueue.isEmpty()) {
            db.call(() -> {
                writeNow();
                return null;
            });
        }
    }

    /** 關服用：寫入並等待完成。 */
    public void flushSync() {
        try {
            db.call(() -> {
                writeNow();
                while (!logQueue.isEmpty()) {
                    writeNow();
                }
                return null;
            }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("關服存檔失敗：" + e);
        }
    }
}
