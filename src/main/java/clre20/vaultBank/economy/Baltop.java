package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 每個幣種的餘額排行榜：定時在背景重新排序，查詢時只讀快照。 */
public final class Baltop {

    public record Entry(UUID id, String name, BigDecimal balance) {
    }

    public record Snapshot(List<Entry> entries, BigDecimal total, long builtAt, Map<UUID, Integer> ranks) {
        static final Snapshot EMPTY = new Snapshot(List.of(), BigDecimal.ZERO, 0, Map.of());
    }

    private final VaultBank plugin;
    private volatile Map<String, Snapshot> snapshots = Map.of();

    public Baltop(VaultBank plugin) {
        this.plugin = plugin;
    }

    /** 主貨幣的排行榜。 */
    public Snapshot snapshot() {
        return snapshot(Currency.PRIMARY);
    }

    public Snapshot snapshot(String currency) {
        return snapshots.getOrDefault(currency, Snapshot.EMPTY);
    }

    /** 在背景執行緒呼叫。 */
    public void rebuild() {
        BigDecimal min = plugin.config().baltopMinBalance();
        Map<String, Snapshot> out = new HashMap<>();
        for (Currency cur : plugin.config().currencies().allEnabled()) {
            List<Entry> list = new ArrayList<>(plugin.bank().size());
            BigDecimal total = BigDecimal.ZERO;
            for (Account a : plugin.bank().all()) {
                BigDecimal b = Bank.get(a, cur);
                total = total.add(b);
                if (b.signum() > 0 && b.compareTo(min) >= 0) {
                    list.add(new Entry(a.id(), a.name(), b));
                }
            }
            list.sort((x, y) -> {
                int c = y.balance().compareTo(x.balance());
                return c != 0 ? c : x.name().compareToIgnoreCase(y.name());
            });
            Map<UUID, Integer> ranks = new HashMap<>(list.size() * 2);
            for (int i = 0; i < list.size(); i++) {
                ranks.put(list.get(i).id(), i + 1);
            }
            out.put(cur.id(), new Snapshot(Collections.unmodifiableList(list), total, System.currentTimeMillis(),
                    Collections.unmodifiableMap(ranks)));
        }
        snapshots = Map.copyOf(out);
    }

    /** 主貨幣名次（1 起算），不在榜上回傳 0。 */
    public int rankOf(UUID id) {
        return rankOf(id, Currency.PRIMARY);
    }

    public int rankOf(UUID id, String currency) {
        return snapshot(currency).ranks().getOrDefault(id, 0);
    }
}
