package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 匯入 ExcellentEconomy 的貨幣到某個幣種（config currencies.&lt;幣種&gt;.import.excellent-economy）。
 * <ol>
 *   <li>有 plugins/ExcellentEconomy/data.db（SQLite，預設儲存方式）時直接唯讀讀取：表格 *excellenteconomy_users，
 *       欄位名稱是該貨幣 currencies/&lt;id&gt;.yml 的 Column_Name（預設就是貨幣 id）。不管 ExcellentEconomy 有沒有在執行都可以。</li>
 *   <li>沒有 SQLite 檔（例如用 MySQL）但 ExcellentEconomy 正在執行時，用它的 API 逐一查詢
 *       VaultBank 帳戶與伺服器上所有玩過的玩家。</li>
 * </ol>
 * 只讀不改原資料；幣種第一次啟用時匯入一次，完成後在資料庫記錄（加到現有餘額上，不會覆蓋）。
 * 匯入在 VaultBank 的 I/O 執行緒進行，不卡主執行緒。
 */
public final class ExcellentImporter {

    private static final String META = "import.excellent-economy.";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final String API = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";

    private ExcellentImporter() {
    }

    /** onEnable 與 /vb reload 時呼叫（主執行緒）。 */
    public static void runIfNeeded(VaultBank plugin) {
        for (Currency c : plugin.config().currencies().allEnabled()) {
            String source = c.importExcellent();
            if (c.primary() || source.isEmpty()) {
                continue;
            }
            if (!SAFE.matcher(source).matches()) {
                plugin.getLogger().warning("config.yml currencies." + c.id() + ".import.excellent-economy 只能用 a-z、0-9、_");
                continue;
            }
            start(plugin, c, source);
        }
    }

    private static void start(VaultBank plugin, Currency cur, String source) {
        Database db = plugin.database();
        File folder = new File(plugin.getDataFolder().getParentFile(), "ExcellentEconomy");
        File file = new File(folder, "data.db");
        // API 要在主執行緒準備（找服務與玩家清單），查詢本身在背景
        Object api = file.isFile() ? null : api();
        List<UUID> players = api == null ? List.of() : knownPlayers(plugin);
        db.call(() -> {
            if (db.meta(META + cur.id()) != null) {
                return null;
            }
            Map<UUID, Object[]> rows;
            if (file.isFile()) {
                rows = readSqlite(file, column(folder, source));
            } else if (api != null) {
                rows = readApi(api, source, players);
            } else {
                plugin.getLogger().warning(plugin.messages().console("excellent-import-missing",
                        "currency", plugin.currencyName(cur), "source", source));
                return null;
            }
            BigDecimal total = BigDecimal.ZERO;
            int imported = 0;
            List<String> capped = new ArrayList<>();
            for (Map.Entry<UUID, Object[]> e : rows.entrySet()) {
                BigDecimal v = ((BigDecimal) e.getValue()[1]).setScale(cur.scale(), RoundingMode.DOWN);
                if (v.signum() <= 0) {
                    continue;
                }
                String name = (String) e.getValue()[0];
                BigDecimal added = plugin.bank().importBalance(e.getKey(), name, cur, v, "ExcellentEconomy:" + source);
                if (added.signum() > 0) {
                    imported++;
                    total = total.add(added);
                }
                if (added.compareTo(v) < 0) {
                    capped.add(name + "（" + v.toPlainString() + "）");
                }
            }
            if (!capped.isEmpty()) {
                plugin.getLogger().warning(plugin.messages().console("excellent-import-capped", "count", capped.size(),
                        "currency", plugin.currencyName(cur), "max", plugin.format(cur, cur.maxBalance()),
                        "players", String.join("、", capped.subList(0, Math.min(20, capped.size())))));
            }
            db.setMeta(META + cur.id(), String.valueOf(System.currentTimeMillis()));
            plugin.getLogger().info(plugin.messages().console("excellent-import-done", "source", source,
                    "currency", plugin.currencyName(cur), "imported", imported, "total", plugin.format(cur, total)));
            return null;
        }).exceptionally(t -> {
            plugin.getLogger().warning(plugin.messages().console("excellent-import-failed", "reason", String.valueOf(t)));
            return null;
        });
    }

    /** 貨幣 id 對應的資料庫欄位（currencies/&lt;id&gt;.yml 的 Column_Name，沒有時就是 id）。 */
    private static String column(File folder, String source) {
        File yml = new File(folder, "currencies/" + source + ".yml");
        if (yml.isFile() && yml.length() < 256 * 1024) {
            String c = YamlConfiguration.loadConfiguration(yml).getString("Column_Name", source);
            if (c != null && SAFE.matcher(c).matches()) {
                return c;
            }
        }
        return source;
    }

    /** 唯讀開啟 SQLite；欄位名稱先和表格實際的欄位比對，才放進 SQL。 */
    private static Map<UUID, Object[]> readSqlite(File file, String column) throws Exception {
        Map<UUID, Object[]> out = new LinkedHashMap<>();
        java.util.Properties props = new java.util.Properties();
        props.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY：不建立、不修改
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), props);
             Statement s = c.createStatement()) {
            String table = null;
            try (ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (rs.next()) {
                    String n = rs.getString(1);
                    if (n != null && n.toLowerCase(Locale.ROOT).endsWith("excellenteconomy_users") && SAFE.matcher(n).matches()) {
                        table = n;
                        break;
                    }
                }
            }
            if (table == null) {
                throw new IllegalStateException("data.db 裡找不到 excellenteconomy_users 表格");
            }
            Set<String> columns = new HashSet<>();
            try (ResultSet rs = s.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                }
            }
            if (!columns.contains(column)) {
                throw new IllegalStateException("找不到貨幣欄位 " + column + "（現有：" + columns + "）");
            }
            try (ResultSet rs = s.executeQuery("SELECT uuid, name, \"" + column + "\" FROM \"" + table + "\"")) {
                while (rs.next()) {
                    UUID id;
                    try {
                        id = UUID.fromString(rs.getString(1));
                    } catch (RuntimeException e) {
                        continue;
                    }
                    String name = rs.getString(2);
                    BigDecimal v = BigDecimal.valueOf(rs.getDouble(3));
                    out.put(id, new Object[]{name == null || name.isBlank() ? id.toString().substring(0, 8) : name, v});
                }
            }
        }
        return out;
    }

    // ---------- API（MySQL 等） ----------

    private static Object api() {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("ExcellentEconomy");
        if (p == null || !p.isEnabled()) {
            return null;
        }
        for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
            if (c.getName().equals(API)) {
                RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(c);
                return rsp == null ? null : rsp.getProvider();
            }
        }
        return null;
    }

    private static List<UUID> knownPlayers(VaultBank plugin) {
        Set<UUID> ids = new HashSet<>();
        for (Account a : plugin.bank().all()) {
            ids.add(a.id());
        }
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            ids.add(op.getUniqueId());
        }
        return new ArrayList<>(ids);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object[]> readApi(Object api, String source, List<UUID> players) throws Exception {
        Method bal = api.getClass().getMethod("getBalanceAsync", UUID.class, String.class);
        Map<UUID, Object[]> out = new LinkedHashMap<>();
        for (UUID id : players) {
            // 一次查一個，避免同時對 ExcellentEconomy 的資料庫送出大量查詢
            CompletableFuture<Double> f = (CompletableFuture<Double>) bal.invoke(api, id, source);
            Double d = f.get(10, TimeUnit.SECONDS);
            if (d != null && d > 0) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                out.put(id, new Object[]{op.getName() != null ? op.getName() : id.toString().substring(0, 8), BigDecimal.valueOf(d)});
            }
        }
        return out;
    }
}
