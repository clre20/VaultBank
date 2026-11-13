package clre20.vaultBank.currency;

import clre20.vaultBank.storage.Database;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 支票：尚未兌現的支票放在記憶體（序號 → 金額），兌現時從記憶體移除，所以同一張只能兌現一次，
 * 兌現時不用讀資料庫。開票與兌現的紀錄排隊後批次寫入。
 */
public final class Cheques {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final Database db;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> outstanding = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Database.ChequeRow> issued = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Database.Redemption> redeemed = new ConcurrentLinkedQueue<>();

    public Cheques(Database db) {
        this.db = db;
    }

    public void load() throws Exception {
        outstanding.putAll(db.call(db::loadOutstandingCheques).get());
    }

    /** 先保留一個新序號（扣款前）；扣款失敗要呼叫 cancel。 */
    public String reserve(long amount) {
        String serial;
        do {
            serial = newSerial();
        } while (outstanding.putIfAbsent(serial, amount) != null);
        return serial;
    }

    /** 扣款成功後登記，排隊寫入資料庫。 */
    public void confirm(String serial, long amount, UUID issuer, String issuerName, long time) {
        issued.add(new Database.ChequeRow(serial, amount, issuer.toString(), issuerName, time));
    }

    public void cancel(String serial) {
        outstanding.remove(serial);
    }

    public boolean isOutstanding(String serial, long amount) {
        Long v = outstanding.get(serial);
        return v != null && v == amount;
    }

    /** 兌現：序號存在且金額相符才成功，成功後立刻作廢。 */
    public boolean redeem(String serial, long amount, UUID redeemer) {
        if (!outstanding.remove(serial, amount)) {
            return false;
        }
        redeemed.add(new Database.Redemption(serial, System.currentTimeMillis(), redeemer.toString()));
        return true;
    }

    public int outstandingCount() {
        return outstanding.size();
    }

    public long outstandingTotal() {
        long sum = 0;
        for (long v : outstanding.values()) {
            sum += v;
        }
        return sum;
    }

    public void flushAsync() {
        if (issued.isEmpty() && redeemed.isEmpty()) {
            return;
        }
        db.call(() -> {
            writeNow();
            return null;
        });
    }

    public void flushSync() {
        try {
            db.call(() -> {
                writeNow();
                return null;
            }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 已在 Database 記錄錯誤
        }
    }

    /** I/O 執行緒：先寫開票再寫兌現，確保兌現的 UPDATE 找得到那一列。 */
    private void writeNow() throws Exception {
        List<Database.ChequeRow> a = new ArrayList<>();
        Database.ChequeRow r;
        while ((r = issued.poll()) != null) {
            a.add(r);
        }
        try {
            db.insertCheques(a);
        } catch (Exception e) {
            issued.addAll(a);
            throw e;
        }
        List<Database.Redemption> b = new ArrayList<>();
        Database.Redemption x;
        while ((x = redeemed.poll()) != null) {
            b.add(x);
        }
        try {
            db.markRedeemed(b);
        } catch (Exception e) {
            redeemed.addAll(b);
            throw e;
        }
    }

    private String newSerial() {
        char[] c = new char[12];
        for (int i = 0; i < c.length; i++) {
            c[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(c);
    }
}
