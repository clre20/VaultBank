package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 餘額排行榜：定時在背景重新排序，查詢時只讀快照。 */
public final class Baltop {

    public record Entry(UUID id, String name, BigDecimal balance) {
    }

    public record Snapshot(List<Entry> entries, BigDecimal total, long builtAt) {
    }

    private final VaultBank plugin;
    private volatile Snapshot snapshot = new Snapshot(List.of(), BigDecimal.ZERO, 0);

    public Baltop(VaultBank plugin) {
        this.plugin = plugin;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** 在背景執行緒呼叫。 */
    public void rebuild() {
        BigDecimal min = plugin.config().baltopMinBalance();
        List<Entry> list = new ArrayList<>(plugin.bank().size());
        BigDecimal total = BigDecimal.ZERO;
        for (Account a : plugin.bank().all()) {
            BigDecimal b = a.balance();
            total = total.add(b);
            if (b.compareTo(min) >= 0 && b.signum() > 0) {
                list.add(new Entry(a.id(), a.name(), b));
            }
        }
        list.sort((x, y) -> {
            int c = y.balance().compareTo(x.balance());
            return c != 0 ? c : x.name().compareToIgnoreCase(y.name());
        });
        snapshot = new Snapshot(Collections.unmodifiableList(list), total, System.currentTimeMillis());
    }

    /** 名次（1 起算），不在榜上回傳 0。 */
    public int rankOf(UUID id) {
        List<Entry> list = snapshot.entries();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i + 1;
            }
        }
        return 0;
    }
}
