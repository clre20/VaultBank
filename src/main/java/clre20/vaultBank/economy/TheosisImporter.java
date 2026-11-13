package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.storage.Database;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 第一次啟動時匯入 TheosisEconomy 的帳戶（plugins/TheosisEconomy/player-accounts/<uuid>.json）。
 * 只讀不改原資料；已存在的帳戶略過；完成後在資料庫記錄，之後不再匯入。
 */
public final class TheosisImporter {

    private static final String META_KEY = "import.theosis-economy";
    private static final Pattern FILE = Pattern.compile("[0-9a-fA-F-]{36}\\.json");
    private static final long MAX_FILE_SIZE = 64 * 1024;

    private TheosisImporter() {
    }

    public static void runIfNeeded(VaultBank plugin, Database db, Bank bank) {
        if (!plugin.config().importTheosis()) {
            return;
        }
        File dir = new File(plugin.getDataFolder().getParentFile(), "TheosisEconomy/player-accounts");
        if (!dir.isDirectory()) {
            return;
        }
        try {
            if (db.call(() -> db.meta(META_KEY)).get() != null) {
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("無法確認 TheosisEconomy 匯入狀態，這次不匯入：" + e);
            return;
        }
        File[] files = dir.listFiles((d, name) -> FILE.matcher(name).matches());
        if (files == null) {
            return;
        }
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (File f : files) {
            try {
                if (!f.isFile() || f.length() > MAX_FILE_SIZE) {
                    failed++;
                    continue;
                }
                UUID id = UUID.fromString(f.getName().substring(0, 36));
                JsonObject json;
                try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                    json = JsonParser.parseReader(r).getAsJsonObject();
                }
                JsonElement balanceEl = json.get("balance");
                if (balanceEl == null || !balanceEl.isJsonPrimitive()) {
                    failed++;
                    continue;
                }
                BigDecimal balance = new BigDecimal(balanceEl.getAsString());
                if (balance.signum() < 0) {
                    balance = BigDecimal.ZERO;
                }
                JsonElement nameEl = json.get("lastKnownUsername");
                String name = nameEl != null && nameEl.isJsonPrimitive() ? nameEl.getAsString() : id.toString();
                if (name.isEmpty() || name.length() > 32) {
                    name = id.toString();
                }
                JsonElement acceptEl = json.get("acceptingPayments");
                boolean accepting = acceptEl == null || !acceptEl.isJsonPrimitive() || acceptEl.getAsBoolean();
                if (bank.importAccount(id, name, balance, accepting)) {
                    imported++;
                    total = total.add(bank.scale(balance));
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
            }
        }
        // 先把帳戶寫進資料庫，再記錄「已匯入」，中途當機的話下次會重新匯入
        bank.flushSync();
        int fImported = imported;
        try {
            db.call(() -> {
                db.setMeta(META_KEY, System.currentTimeMillis() + ":" + fImported);
                return null;
            }).get();
        } catch (Exception e) {
            plugin.getLogger().warning("無法記錄匯入狀態：" + e);
        }
        plugin.getLogger().info(plugin.messages().console("import-done",
                "imported", imported, "skipped", skipped, "failed", failed, "total", total.toPlainString()));
    }
}
