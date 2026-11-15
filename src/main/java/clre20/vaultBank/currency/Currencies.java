package clre20.vaultBank.currency;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * 所有幣種與面額（不可變，config 重載時整個換掉）。
 */
public final class Currencies {

    public static final Pattern CURRENCY_ID = Pattern.compile("[a-z0-9_]{1,16}");
    public static final Pattern DENOM_ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern MATERIAL = Pattern.compile("[a-z0-9_]{1,64}");

    /** 主貨幣的 7 種面額：id、面額、形狀。 */
    private static final Object[][] PRIMARY_DENOMS = {
            {"coin_1", 1L, Denomination.Shape.COIN}, {"coin_5", 5L, Denomination.Shape.COIN},
            {"coin_10", 10L, Denomination.Shape.COIN}, {"coin_50", 50L, Denomination.Shape.COIN},
            {"note_100", 100L, Denomination.Shape.NOTE}, {"note_500", 500L, Denomination.Shape.NOTE},
            {"note_1000", 1000L, Denomination.Shape.NOTE},
    };

    /** 不能當成面額 id 的名稱（材質包裡已經用掉的）。 */
    private static final List<String> RESERVED = List.of("cheque");

    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Map<String, Denomination> denoms = new LinkedHashMap<>();

    private Currencies() {
    }

    /** 主貨幣面額的 id（給材質包與舊版相容用）。 */
    public static List<String> primaryDenominationIds() {
        List<String> ids = new ArrayList<>();
        for (Object[] d : PRIMARY_DENOMS) {
            ids.add((String) d[0]);
        }
        return ids;
    }

    /**
     * @param primary 主貨幣（由 config 的 currency／pay／cheque／interest 等舊位置的設定建立）
     * @param enabledPrimaryDenoms config denominations.coin-1 這類開關
     * @param section config 的 currencies 區段（可為 null）
     * @param warn 設定錯誤時的警告（鍵, 說明）
     */
    public static Currencies build(Currency primary, Map<String, Boolean> enabledPrimaryDenoms,
                                   ConfigurationSection section, double coinThickness, double noteThickness,
                                   BiConsumer<String, String> warn) {
        Currencies out = new Currencies();
        out.currencies.put(primary.id(), primary);
        for (Object[] d : PRIMARY_DENOMS) {
            String id = (String) d[0];
            Denomination.Shape shape = (Denomination.Shape) d[2];
            Denomination den = new Denomination(primary, id, (long) d[1],
                    enabledPrimaryDenoms.getOrDefault(id, true), shape,
                    shape == Denomination.Shape.COIN ? coinThickness : noteThickness, "gold_nugget", false);
            primary.addDenomination(den);
            out.denoms.put(id, den);
        }
        if (section == null) {
            return out;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            if (!CURRENCY_ID.matcher(id).matches() || out.currencies.containsKey(id)) {
                warn.accept("currencies." + id, "幣種名稱只能用 a-z、0-9、_（最多 16 字），且不能和其他幣種重複");
                continue;
            }
            int scale = clamp(s.getInt("decimal-places", 0), 0, 4);
            BigDecimal max = dec(s, "max-balance", "1000000000000");
            if (max.signum() <= 0) {
                max = new BigDecimal("1000000000000");
            }
            Currency.Features f = new Currency.Features(
                    s.getBoolean("features.pay", true), s.getBoolean("features.cheque", true),
                    s.getBoolean("features.interest", true), s.getBoolean("features.baltop", true),
                    s.getBoolean("features.physical", true));
            Currency c = new Currency(id, false, s.getBoolean("enabled", false), scale,
                    nonNegative(dec(s, "default-balance", "0")), max, f,
                    nonNegative(dec(s, "pay.min-amount", scale == 0 ? "1" : "0.01")),
                    percent(dec(s, "pay.fee-percent", "0")),
                    Math.max(1, s.getLong("cheque.min-amount", 1)), Math.max(0, s.getLong("cheque.max-amount", 0)),
                    percent(dec(s, "cheque.fee-percent", "0")),
                    percent(dec(s, "interest.rate-percent", "0.1")),
                    nonNegative(dec(s, "interest.max-per-payout", "0")),
                    s.getString("import.excellent-economy", "").trim());
            ConfigurationSection ds = s.getConfigurationSection("denominations");
            if (ds != null) {
                for (String did : ds.getKeys(false)) {
                    ConfigurationSection d = ds.getConfigurationSection(did);
                    if (d == null) {
                        continue;
                    }
                    if (!DENOM_ID.matcher(did).matches() || out.denoms.containsKey(did) || RESERVED.contains(did)) {
                        warn.accept("currencies." + id + ".denominations." + did,
                                "面額名稱只能用 a-z、0-9、_（最多 32 字），且所有幣種之間不能重複");
                        continue;
                    }
                    long value = d.getLong("value", 0);
                    if (value <= 0 || value > 1_000_000_000L || c.byValue(value) != null) {
                        warn.accept("currencies." + id + ".denominations." + did + ".value", "面額要是 1～1000000000 的整數，且同幣種不能重複");
                        continue;
                    }
                    String material = d.getString("material", "gold_nugget").trim().toLowerCase(Locale.ROOT);
                    if (material.startsWith("minecraft:")) {
                        material = material.substring(10);
                    }
                    if (!MATERIAL.matcher(material).matches()) {
                        warn.accept("currencies." + id + ".denominations." + did + ".material", "物品名稱格式錯誤，改用 gold_nugget");
                        material = "gold_nugget";
                    }
                    Denomination den = new Denomination(c, did, value, d.getBoolean("enabled", true),
                            Denomination.Shape.COIN, clampD(d.getDouble("thickness", coinThickness), 0.25, 4), material,
                            d.getString("appearance", "auto").trim().equalsIgnoreCase("vanilla"));
                    c.addDenomination(den);
                    out.denoms.put(did, den);
                }
            }
            out.currencies.put(id, c);
        }
        return out;
    }

    public Currency primary() {
        return currencies.get(Currency.PRIMARY);
    }

    /** 找幣種（不分大小寫）；找不到回傳 null。 */
    public Currency get(String id) {
        return id == null ? null : currencies.get(id.toLowerCase(Locale.ROOT));
    }

    /** 啟用中的幣種；停用的回傳 null。 */
    public Currency enabled(String id) {
        Currency c = get(id);
        return c != null && c.enabled() ? c : null;
    }

    public Collection<Currency> all() {
        return Collections.unmodifiableCollection(currencies.values());
    }

    public List<Currency> allEnabled() {
        List<Currency> out = new ArrayList<>();
        for (Currency c : currencies.values()) {
            if (c.enabled()) {
                out.add(c);
            }
        }
        return out;
    }

    public Denomination denomination(String id) {
        return id == null ? null : denoms.get(id);
    }

    public Collection<Denomination> denominations() {
        return Collections.unmodifiableCollection(denoms.values());
    }

    // ---------- 共用 ----------

    static BigDecimal dec(ConfigurationSection c, String path, String fallback) {
        Object v = c.get(path);
        try {
            if (v != null) {
                return new BigDecimal(String.valueOf(v).trim());
            }
        } catch (NumberFormatException ignored) {
            // 用預設值
        }
        return new BigDecimal(fallback);
    }

    static BigDecimal nonNegative(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    static BigDecimal percent(BigDecimal v) {
        if (v.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return v.compareTo(BigDecimal.valueOf(100)) > 0 ? BigDecimal.valueOf(100) : v;
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static double clampD(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    /** 主貨幣（由 PluginConfig 呼叫）。 */
    public static Currency primaryCurrency(int scale, BigDecimal defaultBalance, BigDecimal maxBalance,
                                           BigDecimal payMin, BigDecimal payFee, long chequeMin, long chequeMax,
                                           BigDecimal chequeFee, BigDecimal interestRate, BigDecimal interestMax) {
        return new Currency(Currency.PRIMARY, true, true, scale, defaultBalance, maxBalance,
                new Currency.Features(true, true, true, true, true), payMin, payFee, chequeMin, chequeMax, chequeFee,
                interestRate, interestMax, "");
    }
}
