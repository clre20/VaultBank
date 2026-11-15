package clre20.vaultBank.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一個幣種。主貨幣（元，id = yuan）綁定 Vault，不能停用；其他幣種在 config.yml 的 currencies 設定。
 */
public final class Currency {

    public static final String PRIMARY = "yuan";

    /** 每種功能的開關與數值。 */
    public record Features(boolean pay, boolean cheque, boolean interest, boolean baltop, boolean physical) {
    }

    private final String id;
    private final boolean primary;
    private final boolean enabled;
    private final int scale;
    private final BigDecimal defaultBalance;
    private final BigDecimal maxBalance;
    private final Features features;
    private final BigDecimal payMin;
    private final BigDecimal payFeePercent;
    private final long chequeMin;
    private final long chequeMax;
    private final BigDecimal chequeFeePercent;
    private final BigDecimal interestRatePercent;
    private final BigDecimal interestMaxPerPayout;
    private final String importExcellent;
    private final List<Denomination> denominations = new ArrayList<>();

    Currency(String id, boolean primary, boolean enabled, int scale, BigDecimal defaultBalance, BigDecimal maxBalance,
             Features features, BigDecimal payMin, BigDecimal payFeePercent, long chequeMin, long chequeMax,
             BigDecimal chequeFeePercent, BigDecimal interestRatePercent, BigDecimal interestMaxPerPayout,
             String importExcellent) {
        this.id = id;
        this.primary = primary;
        this.enabled = enabled;
        this.scale = scale;
        this.defaultBalance = defaultBalance;
        this.maxBalance = maxBalance;
        this.features = features;
        this.payMin = payMin;
        this.payFeePercent = payFeePercent;
        this.chequeMin = chequeMin;
        this.chequeMax = chequeMax;
        this.chequeFeePercent = chequeFeePercent;
        this.interestRatePercent = interestRatePercent;
        this.interestMaxPerPayout = interestMaxPerPayout;
        this.importExcellent = importExcellent;
    }

    void addDenomination(Denomination d) {
        denominations.add(d);
        denominations.sort((a, b) -> Long.compare(b.value(), a.value()));
    }

    public String id() { return id; }
    public boolean primary() { return primary; }
    public boolean enabled() { return enabled; }
    public int scale() { return scale; }
    public BigDecimal defaultBalance() { return defaultBalance; }
    public BigDecimal maxBalance() { return maxBalance; }
    public Features features() { return features; }
    public BigDecimal payMin() { return payMin; }
    public BigDecimal payFeePercent() { return payFeePercent; }
    public long chequeMin() { return chequeMin; }
    public long chequeMax() { return chequeMax; }
    public BigDecimal chequeFeePercent() { return chequeFeePercent; }
    public BigDecimal interestRatePercent() { return interestRatePercent; }
    public BigDecimal interestMaxPerPayout() { return interestMaxPerPayout; }

    /** 第一次啟用時要從 ExcellentEconomy 匯入的幣種 id（空字串＝不匯入）。 */
    public String importExcellent() { return importExcellent; }

    /** 由大到小。 */
    public List<Denomination> denominations() {
        return Collections.unmodifiableList(denominations);
    }

    public boolean physical() {
        return features.physical() && !denominations.isEmpty();
    }

    public BigDecimal round(BigDecimal v) {
        return v.setScale(scale, RoundingMode.HALF_UP);
    }

    public Denomination byValue(long value) {
        for (Denomination d : denominations) {
            if (d.value() == value) {
                return d;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return id;
    }
}
