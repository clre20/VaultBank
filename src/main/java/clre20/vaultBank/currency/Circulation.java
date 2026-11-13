package clre20.vaultBank.currency;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.storage.Database;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 流通量：每種面額發行（提取、管理員發放）與回收（存入）的數量。
 * 回收超過發行代表有人複製了貨幣（例如利用複製漏洞），會警告管理員。
 * 只存在記憶體，有變動時跟帳戶一起批次寫入。
 */
public final class Circulation {

    private static final long ALERT_INTERVAL_MS = 10 * 60 * 1000L;

    private final VaultBank plugin;
    private final Database db;
    private final AtomicLongArray minted = new AtomicLongArray(Denomination.values().length);
    private final AtomicLongArray redeemed = new AtomicLongArray(Denomination.values().length);
    private final long[] lastAlert = new long[Denomination.values().length];
    private final AtomicBoolean dirty = new AtomicBoolean();

    public Circulation(VaultBank plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void load() throws Exception {
        Map<String, long[]> rows = db.call(db::loadCirculation).get();
        for (Denomination d : Denomination.values()) {
            long[] v = rows.get(d.id());
            if (v != null) {
                minted.set(d.ordinal(), v[0]);
                redeemed.set(d.ordinal(), v[1]);
            }
        }
    }

    public void minted(Denomination d, long count) {
        minted.addAndGet(d.ordinal(), count);
        dirty.set(true);
    }

    /** 記錄回收；回傳 true 表示回收已超過發行（可能有複製）。 */
    public boolean redeemed(Denomination d, long count) {
        long r = redeemed.addAndGet(d.ordinal(), count);
        dirty.set(true);
        return r > minted.get(d.ordinal());
    }

    public long minted(Denomination d) {
        return minted.get(d.ordinal());
    }

    public long redeemed(Denomination d) {
        return redeemed.get(d.ordinal());
    }

    /** 同一面額 10 分鐘內只警告一次。只在主執行緒呼叫。 */
    public boolean shouldAlert(Denomination d) {
        long now = System.currentTimeMillis();
        if (now - lastAlert[d.ordinal()] < ALERT_INTERVAL_MS) {
            return false;
        }
        lastAlert[d.ordinal()] = now;
        return true;
    }

    public void flushAsync() {
        if (!dirty.getAndSet(false)) {
            return;
        }
        Map<String, long[]> snapshot = new HashMap<>();
        for (Denomination d : Denomination.values()) {
            snapshot.put(d.id(), new long[]{minted.get(d.ordinal()), redeemed.get(d.ordinal())});
        }
        db.call(() -> {
            db.saveCirculation(snapshot);
            return null;
        }).exceptionally(t -> {
            dirty.set(true);
            return null;
        });
    }
}
