package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.config.PluginConfig.ExchangePair;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.storage.Database;
import clre20.vaultBank.util.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幣種兌換。每一組（config exchange.pairs）定義 1 from ＝ rate to；allow-reverse: false 時只能 from → to。
 * <ul>
 *   <li>固定匯率：永遠用 rate。</li>
 *   <li>浮動匯率：像外匯，全服的兌換會推動匯率。用一個虛擬的「恆定乘積」資金池計算（x·y＝k，
 *       基準匯率時池中有 L/rate 個 from 與 L 個 to，L＝流動性），換得越多匯率被推得越遠，
 *       而且結果與拆成幾筆無關、來回兌換不會多賺；沒有人換的時候每小時往基準匯率回復一定比例。
 *       匯率限制在 min-rate～max-rate，會超出範圍的兌換直接拒絕。</li>
 * </ul>
 * 目前匯率只放在記憶體，有變動時跟著定時存檔寫入資料庫的 meta 表（每組一列），不會產生額外的 I/O。
 * 兌換只在主執行緒執行；匯率可從任何執行緒讀取（PlaceholderAPI）。
 */
public final class Exchange {

    public enum Status { OK, DISABLED, NO_PAIR, ONE_WAY, INVALID, TOO_MANY_DECIMALS, TOO_SMALL, OUT_OF_RANGE, INSUFFICIENT, MAX_BALANCE, CHANGED, FAILED }

    /**
     * 報價。
     *
     * @param forward 方向與設定相同（from → to）
     * @param rate    這個方向的匯率：1 個付出的幣換多少個得到的幣（未扣手續費、成交前）
     */
    public record Quote(Status status, ExchangePair pair, boolean forward, Currency pay, Currency get,
                        BigDecimal amountIn, BigDecimal amountOut, BigDecimal fee, BigDecimal rate, BigDecimal newRate) {

        static Quote fail(Status s) {
            return new Quote(s, null, false, null, null, null, null, null, null, null);
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_EVEN);
    private static final String META = "exchange.rate.";

    private final VaultBank plugin;
    private final Database db;
    /** pair 名稱 → 目前匯率（1 from ＝ r to）。只有浮動匯率的組有值。 */
    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public Exchange(VaultBank plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    /** 啟動時從資料庫讀回匯率（同步，資料只有幾列）。 */
    public void load() {
        for (ExchangePair p : plugin.config().exchangePairs()) {
            if (!p.floating()) {
                continue;
            }
            try {
                String v = db.call(() -> db.meta(META + p.name())).get();
                rates.put(p.name(), clamp(p, v == null ? p.rate() : new BigDecimal(v)));
            } catch (Exception e) {
                rates.put(p.name(), p.rate());
            }
        }
    }

    /** /vb reload：新增的浮動組從基準匯率開始，已存在的保留目前匯率（夾回新的上下限）。 */
    public void reload() {
        Map<String, BigDecimal> old = Map.copyOf(rates);
        rates.clear();
        for (ExchangePair p : plugin.config().exchangePairs()) {
            if (p.floating()) {
                rates.put(p.name(), clamp(p, old.getOrDefault(p.name(), p.rate())));
            }
        }
        if (rates.isEmpty() && old.isEmpty()) {
            return;
        }
        dirty.set(true);
    }

    public boolean enabled() {
        return plugin.config().exchangeEnabled() && !pairs().isEmpty();
    }

    /** 兩邊幣種都啟用的組。 */
    public List<ExchangePair> pairs() {
        return plugin.config().exchangePairs().stream()
                .filter(p -> plugin.config().currencies().enabled(p.from()) != null
                        && plugin.config().currencies().enabled(p.to()) != null)
                .toList();
    }

    /** 目前匯率：1 pair.from ＝ ? pair.to。 */
    public BigDecimal rate(ExchangePair p) {
        return p.floating() ? rates.getOrDefault(p.name(), p.rate()) : p.rate();
    }

    /** 這兩個幣種之間 1 pay ＝ ? get（目前匯率，未扣手續費）；沒有這組回傳 null。 */
    public BigDecimal rate(Currency pay, Currency get) {
        for (ExchangePair p : pairs()) {
            if (p.from().equals(pay.id()) && p.to().equals(get.id())) {
                return rate(p);
            }
            if (p.allowReverse() && p.to().equals(pay.id()) && p.from().equals(get.id())) {
                return BigDecimal.ONE.divide(rate(p), MC);
            }
        }
        return null;
    }

    // ---------- 報價與成交 ----------

    public Quote quote(Currency pay, Currency get, BigDecimal amountIn) {
        if (!plugin.config().exchangeEnabled() || pay == null || get == null || !pay.enabled() || !get.enabled()) {
            return Quote.fail(Status.DISABLED);
        }
        ExchangePair pair = null;
        boolean forward = false;
        boolean oneWay = false;
        for (ExchangePair p : pairs()) {
            if (p.from().equals(pay.id()) && p.to().equals(get.id())) {
                pair = p;
                forward = true;
                break;
            }
            if (p.to().equals(pay.id()) && p.from().equals(get.id())) {
                if (!p.allowReverse()) {
                    oneWay = true;
                    continue;
                }
                pair = p;
                break;
            }
        }
        if (pair == null) {
            return Quote.fail(oneWay ? Status.ONE_WAY : Status.NO_PAIR);
        }
        if (amountIn == null || amountIn.signum() <= 0 || amountIn.compareTo(pay.maxBalance()) > 0) {
            return Quote.fail(Status.INVALID);
        }
        if (!Money.fitsScale(amountIn, pay.scale())) {
            return Quote.fail(Status.TOO_MANY_DECIMALS);
        }
        BigDecimal r = rate(pair);
        BigDecimal gross;
        BigDecimal newRate = r;
        BigDecimal dirRate = forward ? r : BigDecimal.ONE.divide(r, MC);
        if (!pair.floating()) {
            gross = amountIn.multiply(dirRate, MC);
        } else {
            // 恆定乘積 x·y = k，k 由基準匯率與流動性決定（x0 = L / rate，y0 = L），目前匯率 r 決定池中數量：
            // x = √(k / r)、y = √(k · r)。成交後 k 不變，所以拆成很多筆或來回兌換都不會多賺。
            BigDecimal k = pair.liquidity().multiply(pair.liquidity(), MC).divide(pair.rate(), MC);
            BigDecimal x = k.divide(r, MC).sqrt(MC);
            BigDecimal y = k.multiply(r, MC).sqrt(MC);
            if (forward) {
                // 付 from、拿 to
                BigDecimal x2 = x.add(amountIn);
                BigDecimal y2 = k.divide(x2, MC);
                gross = y.subtract(y2);
                newRate = y2.divide(x2, MC);
            } else {
                // 付 to、拿 from
                BigDecimal y2 = y.add(amountIn);
                BigDecimal x2 = k.divide(y2, MC);
                gross = x.subtract(x2);
                newRate = y2.divide(x2, MC);
            }
            if (newRate.compareTo(pair.minRate()) < 0 || newRate.compareTo(pair.maxRate()) > 0) {
                return new Quote(Status.OUT_OF_RANGE, pair, forward, pay, get, amountIn, BigDecimal.ZERO,
                        BigDecimal.ZERO, dirRate, newRate);
            }
        }
        BigDecimal feePct = plugin.config().exchangeFeePercent();
        BigDecimal fee = feePct.signum() > 0
                ? gross.multiply(feePct, MC).divide(BigDecimal.valueOf(100), MC) : BigDecimal.ZERO;
        BigDecimal out = gross.subtract(fee).setScale(get.scale(), RoundingMode.DOWN);
        fee = fee.setScale(get.scale(), RoundingMode.UP);
        if (out.signum() <= 0) {
            return new Quote(Status.TOO_SMALL, pair, forward, pay, get, amountIn, BigDecimal.ZERO, fee, dirRate, newRate);
        }
        return new Quote(Status.OK, pair, forward, pay, get, amountIn, out, fee, dirRate, newRate);
    }

    /**
     * 成交。會重新報價：拿到的數量比 expectedOut 少（匯率在確認前變了）時回傳 CHANGED 與新報價，不成交。
     * expectedOut 為 null 表示不檢查（指令直接兌換）。
     */
    public Quote execute(UUID player, Currency pay, Currency get, BigDecimal amountIn, BigDecimal expectedOut) {
        Quote q = quote(pay, get, amountIn);
        if (!q.ok()) {
            return q;
        }
        if (expectedOut != null && q.amountOut().compareTo(expectedOut) < 0) {
            return new Quote(Status.CHANGED, q.pair(), q.forward(), pay, get, amountIn, q.amountOut(), q.fee(), q.rate(), q.newRate());
        }
        String detail = Money.plain(q.amountIn()) + " " + pay.id() + " -> " + Money.plain(q.amountOut()) + " " + get.id()
                + (q.fee().signum() > 0 ? " fee=" + Money.plain(q.fee()) : "");
        Bank.Result r = plugin.bank().exchange(player, pay, q.amountIn(), get, q.amountOut(), detail);
        Status s = switch (r) {
            case OK -> Status.OK;
            case INSUFFICIENT -> Status.INSUFFICIENT;
            case MAX_BALANCE -> Status.MAX_BALANCE;
            case DISABLED -> Status.DISABLED;
            default -> Status.FAILED;
        };
        if (s != Status.OK) {
            return new Quote(s, q.pair(), q.forward(), pay, get, amountIn, q.amountOut(), q.fee(), q.rate(), q.newRate());
        }
        if (q.pair().floating()) {
            rates.put(q.pair().name(), clamp(q.pair(), q.newRate()));
            dirty.set(true);
        }
        return q;
    }

    // ---------- 回復與存檔 ----------

    /** 每分鐘一次（主執行緒）：浮動匯率往基準匯率回復。 */
    public void tick() {
        for (ExchangePair p : plugin.config().exchangePairs()) {
            if (!p.floating() || p.recoverPercentPerHour() <= 0) {
                continue;
            }
            BigDecimal now = rates.get(p.name());
            if (now == null || now.compareTo(p.rate()) == 0) {
                continue;
            }
            // 每小時回復 f，換算成每分鐘：1 - (1 - f)^(1/60)
            double f = Math.min(1, p.recoverPercentPerHour() / 100.0);
            double step = f >= 1 ? 1 : 1 - Math.pow(1 - f, 1.0 / 60);
            BigDecimal next = now.add(p.rate().subtract(now).multiply(BigDecimal.valueOf(step), MC), MC);
            // 差距很小時直接回到基準，避免無限逼近
            if (next.subtract(p.rate()).abs().compareTo(p.rate().multiply(new BigDecimal("0.000001"))) < 0) {
                next = p.rate();
            }
            rates.put(p.name(), clamp(p, next));
            dirty.set(true);
        }
    }

    public void flushAsync() {
        if (!dirty.getAndSet(false)) {
            return;
        }
        Map<String, String> snapshot = new java.util.HashMap<>();
        rates.forEach((k, v) -> snapshot.put(META + k, v.toPlainString()));
        db.call(() -> {
            for (Map.Entry<String, String> e : snapshot.entrySet()) {
                db.setMeta(e.getKey(), e.getValue());
            }
            return null;
        }).exceptionally(t -> {
            dirty.set(true);
            return null;
        });
    }

    private static BigDecimal clamp(ExchangePair p, BigDecimal v) {
        if (v == null || v.signum() <= 0) {
            return p.rate();
        }
        if (v.compareTo(p.minRate()) < 0) {
            return p.minRate();
        }
        return v.compareTo(p.maxRate()) > 0 ? p.maxRate() : v;
    }
}
