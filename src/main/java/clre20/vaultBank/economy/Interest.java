package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 利息（預設關閉）。每分鐘檢查一次是否到了發放時間；上次發放時間存在資料庫，重啟不會重發。
 */
public final class Interest {

    private static final String META_KEY = "interest.last-payout";

    private final VaultBank plugin;
    private final Database db;
    private volatile long lastPayout = -1;

    public Interest(VaultBank plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    /** 主執行緒每分鐘呼叫。 */
    public void tick() {
        if (!plugin.config().interestEnabled() || plugin.config().interestRatePercent().signum() <= 0) {
            return;
        }
        List<UUID> online = null;
        if (plugin.config().interestOnlineOnly()) {
            online = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                online.add(p.getUniqueId());
            }
        }
        List<UUID> targets = online;
        db.call(() -> {
            long now = System.currentTimeMillis();
            if (lastPayout < 0) {
                String v = db.meta(META_KEY);
                if (v != null) {
                    lastPayout = parse(v);
                } else {
                    // 第一次開啟：從現在開始計時
                    lastPayout = now;
                    db.setMeta(META_KEY, String.valueOf(now));
                    return null;
                }
            }
            // 測試用：-Dvaultbank.debug.interestIntervalSeconds=30
            long interval = Long.getLong("vaultbank.debug.interestIntervalSeconds",
                    plugin.config().interestIntervalHours() * 3_600L) * 1000L;
            if (now - lastPayout < interval) {
                return null;
            }
            lastPayout = now;
            db.setMeta(META_KEY, String.valueOf(now));
            payout(targets);
            return null;
        });
    }

    private static long parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void payout(List<UUID> targets) {
        Bank bank = plugin.bank();
        BigDecimal rate = plugin.config().interestRatePercent();
        BigDecimal cap = plugin.config().interestMaxPerPayout();
        int paid = 0;
        Iterable<UUID> ids = targets != null ? targets : bank.all().stream().map(Account::id).toList();
        for (UUID id : ids) {
            BigDecimal balance = bank.balance(id);
            if (balance == null || balance.signum() <= 0) {
                continue;
            }
            BigDecimal amount = balance.multiply(rate).divide(BigDecimal.valueOf(100), bank.scaleDigits(), RoundingMode.DOWN);
            if (cap.signum() > 0 && amount.compareTo(cap) > 0) {
                amount = cap;
            }
            if (amount.signum() <= 0) {
                continue;
            }
            if (bank.deposit(id, amount, TxType.INTEREST, null, null, null) == Bank.Result.OK) {
                paid++;
            }
        }
        int count = paid;
        plugin.getLogger().info(plugin.messages().console("interest-paid", "count", count));
    }
}
