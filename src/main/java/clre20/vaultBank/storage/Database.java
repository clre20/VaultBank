package clre20.vaultBank.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite（Paper 內建驅動）。所有資料庫操作都在同一條 I/O 執行緒上執行，連線只在那條執行緒使用。
 */
public final class Database {

    public record AccountRow(String uuid, String name, String balance, boolean accepting) {
    }

    public record LogRow(long time, String type, String uuid, String other, String otherName,
                         String amount, String balance, String detail) {
    }

    public record ChequeRow(String serial, long amount, String issuer, String issuerName, long issued) {
    }

    public record Redemption(String serial, long time, String redeemer) {
    }

    private final File file;
    private final Logger logger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VaultBank-IO");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public Database(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** 開啟並建立資料表（等到完成才回傳）。 */
    public void open() throws Exception {
        call(() -> {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, "
                        + "balance TEXT NOT NULL, accepting INTEGER NOT NULL DEFAULT 1)");
                s.execute("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, "
                        + "type TEXT NOT NULL, uuid TEXT NOT NULL, other TEXT, other_name TEXT, amount TEXT NOT NULL, "
                        + "balance TEXT, detail TEXT)");
                s.execute("CREATE INDEX IF NOT EXISTS logs_uuid_time ON logs (uuid, time)");
                s.execute("CREATE INDEX IF NOT EXISTS logs_time ON logs (time)");
                s.execute("CREATE TABLE IF NOT EXISTS cheques (serial TEXT PRIMARY KEY, amount INTEGER NOT NULL, "
                        + "issuer TEXT NOT NULL, issuer_name TEXT, issued INTEGER NOT NULL, redeemed INTEGER, redeemer TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS circulation (denom TEXT PRIMARY KEY, minted INTEGER NOT NULL, "
                        + "redeemed INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            }
            return null;
        }).get(30, TimeUnit.SECONDS);
    }

    /** 送出所有排隊中的工作並關閉連線，最多等 30 秒。 */
    public void close() {
        try {
            io.submit(() -> {
                try {
                    if (connection != null) {
                        connection.close();
                    }
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "關閉資料庫失敗", e);
                }
            });
            io.shutdown();
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("資料庫關閉逾時，可能有資料沒寫完");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public interface SqlTask<T> {
        T run() throws Exception;
    }

    /** 在 I/O 執行緒上執行。 */
    public <T> CompletableFuture<T> call(SqlTask<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    f.complete(task.run());
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "資料庫操作失敗", t);
                    f.completeExceptionally(t);
                }
            });
        } catch (Exception rejected) {
            f.completeExceptionally(rejected);
        }
        return f;
    }

    // ---------- 帳戶 ----------

    public List<AccountRow> loadAccounts() throws SQLException {
        List<AccountRow> out = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT uuid, name, balance, accepting FROM accounts")) {
            while (rs.next()) {
                out.add(new AccountRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) != 0));
            }
        }
        return out;
    }

    public void saveAccounts(List<AccountRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (uuid, name, balance, accepting) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, balance = excluded.balance, "
                        + "accepting = excluded.accepting")) {
            for (AccountRow r : rows) {
                ps.setString(1, r.uuid());
                ps.setString(2, r.name());
                ps.setString(3, r.balance());
                ps.setInt(4, r.accepting() ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    // ---------- 交易紀錄 ----------

    public void insertLogs(List<LogRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO logs (time, type, uuid, other, other_name, amount, balance, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (LogRow r : rows) {
                ps.setLong(1, r.time());
                ps.setString(2, r.type());
                ps.setString(3, r.uuid());
                ps.setString(4, r.other());
                ps.setString(5, r.otherName());
                ps.setString(6, r.amount());
                ps.setString(7, r.balance());
                ps.setString(8, r.detail());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    public List<LogRow> queryLogs(String uuid, long since, int offset, int limit) throws SQLException {
        List<LogRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT time, type, uuid, other, other_name, amount, balance, detail FROM logs "
                        + "WHERE uuid = ? AND time >= ? ORDER BY time DESC, id DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, uuid);
            ps.setLong(2, since);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LogRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
                }
            }
        }
        return out;
    }

    public int countLogs(String uuid, long since) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM logs WHERE uuid = ? AND time >= ?")) {
            ps.setString(1, uuid);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 刪除過期紀錄與過期且已兌現的支票，回傳刪除的紀錄筆數。 */
    public int cleanup(long cutoff) throws SQLException {
        int removed;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM logs WHERE time < ?")) {
            ps.setLong(1, cutoff);
            removed = ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM cheques WHERE redeemed IS NOT NULL AND redeemed < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        }
        return removed;
    }

    // ---------- 支票 ----------

    public void insertCheques(List<ChequeRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO cheques (serial, amount, issuer, issuer_name, issued) VALUES (?, ?, ?, ?, ?)")) {
            for (ChequeRow r : rows) {
                ps.setString(1, r.serial());
                ps.setLong(2, r.amount());
                ps.setString(3, r.issuer());
                ps.setString(4, r.issuerName());
                ps.setLong(5, r.issued());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    public void markRedeemed(List<Redemption> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cheques SET redeemed = ?, redeemer = ? WHERE serial = ? AND redeemed IS NULL")) {
            for (Redemption r : rows) {
                ps.setLong(1, r.time());
                ps.setString(2, r.redeemer());
                ps.setString(3, r.serial());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /** 尚未兌現的支票：序號 → 金額。 */
    public Map<String, Long> loadOutstandingCheques() throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT serial, amount FROM cheques WHERE redeemed IS NULL")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    public long[] chequeStats() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM cheques WHERE redeemed IS NULL")) {
            return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, 0};
        }
    }

    // ---------- 流通量 ----------

    public Map<String, long[]> loadCirculation() throws SQLException {
        Map<String, long[]> out = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT denom, minted, redeemed FROM circulation")) {
            while (rs.next()) {
                out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
            }
        }
        return out;
    }

    public void saveCirculation(Map<String, long[]> values) throws SQLException {
        if (values.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO circulation (denom, minted, redeemed) VALUES (?, ?, ?) "
                        + "ON CONFLICT(denom) DO UPDATE SET minted = excluded.minted, redeemed = excluded.redeemed")) {
            for (Map.Entry<String, long[]> e : values.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setLong(2, e.getValue()[0]);
                ps.setLong(3, e.getValue()[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---------- meta ----------

    public String meta(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** 方便在 I/O 執行緒上做事但不需要回傳值。 */
    public void run(Consumer<Database> task) {
        call(() -> {
            task.accept(this);
            return null;
        });
    }
}
