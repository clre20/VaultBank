package clre20.vaultBank.currency;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.storage.Database;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流通量：每種面額發行（提取、管理員發放）與回收（存入）的數量，以面額 id 為鍵。
 * 回收超過發行代表有人複製了貨幣（例如利用複製漏洞），會警告管理員。
 * 只存在記憶體，有變動時跟帳戶一起批次寫入。
 */
public final class Circulation {

    private static final long ALERT_INTERVAL_MS = 10 * 60 * 1000L;

    private final Database db;
    private final Map<String, AtomicLong> minted = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> redeemed = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public Circulation(VaultBank plugin, Database db) {
        this.db = db;
    }

    public void load() throws Exception {
        Map<String, long[]> rows = db.call(db::loadCirculation).get();
        for (Map.Entry<String, long[]> e : rows.entrySet()) {
            minted.put(e.getKey(), new AtomicLong(e.getValue()[0]));
            redeemed.put(e.getKey(), new AtomicLong(e.getValue()[1]));
        }
    }

    private AtomicLong of(Map<String, AtomicLong> map, Denomination d) {
        return map.computeIfAbsent(d.id(), k -> new AtomicLong());
    }

    public void minted(Denomination d, long count) {
        of(minted, d).addAndGet(count);
        dirty.set(true);
    }

    /** 記錄回收；回傳 true 表示回收已超過發行（可能有複製）。 */
    public boolean redeemed(Denomination d, long count) {
        long r = of(redeemed, d).addAndGet(count);
        dirty.set(true);
        return r > of(minted, d).get();
    }

    public long minted(Denomination d) {
        return of(minted, d).get();
    }

    public long redeemed(Denomination d) {
        return of(redeemed, d).get();
    }

    /** 同一面額 10 分鐘內只警告一次。只在主執行緒呼叫。 */
    public boolean shouldAlert(Denomination d) {
        long now = System.currentTimeMillis();
        Long last = lastAlert.get(d.id());
        if (last != null && now - last < ALERT_INTERVAL_MS) {
            return false;
        }
        lastAlert.put(d.id(), now);
        return true;
    }

    public void flushAsync() {
        if (!dirty.getAndSet(false)) {
            return;
        }
        Map<String, long[]> snapshot = new HashMap<>();
        for (String id : minted.keySet()) {
            snapshot.put(id, new long[]{minted.get(id).get(), redeemed.containsKey(id) ? redeemed.get(id).get() : 0});
        }
        for (String id : redeemed.keySet()) {
            snapshot.putIfAbsent(id, new long[]{0, redeemed.get(id).get()});
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
