package clre20.vaultBank.config;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currencies;
import clre20.vaultBank.currency.Currency;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** config.yml：讀取時一律有預設值，數值超出範圍時夾回合理範圍。 */
public final class PluginConfig {

    private final VaultBank plugin;
    private int lastFilled;

    // 貨幣
    private int decimalPlaces;
    private BigDecimal defaultBalance;
    private BigDecimal maxBalance;
    private Currencies currencies;

    // 兌換
    private boolean exchangeEnabled;
    private BigDecimal exchangeFeePercent;
    private List<ExchangePair> exchangePairs = List.of();

    // 存檔
    private int saveIntervalSeconds;

    // 排行榜
    private int baltopIntervalSeconds;
    private int baltopPageLength;
    private BigDecimal baltopMinBalance;

    // 轉帳
    private BigDecimal payMinAmount;
    private BigDecimal payFeePercent;

    // 提取
    private int withdrawMaxItems;

    // 支票
    private long chequeMinAmount;
    private long chequeMaxAmount;
    private BigDecimal chequeFeePercent;

    // 利息
    private boolean interestEnabled;
    private BigDecimal interestRatePercent;
    private int interestIntervalHours;
    private BigDecimal interestMaxPerPayout;
    private boolean interestOnlineOnly;

    // 交易紀錄
    private boolean logEnabled;
    private int logRetentionDays;
    private boolean logPluginTransactions;
    private int logPageSize;

    // 防偽
    private boolean alertAdmins;
    private boolean confiscateInvalid;

    // 材質包
    private boolean javaPackEnabled;
    private boolean javaPackRequired;
    private String javaPackUrl;
    private int javaPackDelayTicks;
    private boolean bedrockPackEnabled;
    private String geyserFolder;
    private double coinThickness;
    private double noteThickness;
    private double chequeThickness;
    private double chequeRollThickness;

    // 介面
    private int chatInputTimeoutSeconds;
    private int actionCooldownMs;
    private int bedrockOpenDelayTicks;

    // 匯入
    private boolean importTheosis;

    // 更新檢查
    private boolean updateCheckOnStartup;
    private boolean updateNotifyOnJoin;

    public PluginConfig(VaultBank plugin) {
        this.plugin = plugin;
    }

    public void load() {
        YamlFiles.Loaded loaded = YamlFiles.load(plugin, "config.yml",
                (file, info) -> plugin.getLogger().warning(file + " 讀取失敗，已備份為 " + info + "，這次使用預設設定"));
        lastFilled = loaded.filled();
        YamlConfiguration c = loaded.yaml();

        decimalPlaces = clamp(c.getInt("currency.decimal-places", 2), 0, 4);
        defaultBalance = nonNegative(dec(c, "currency.default-balance", "0"));
        maxBalance = dec(c, "currency.max-balance", "10000000000000");
        if (maxBalance.signum() <= 0) {
            maxBalance = new BigDecimal("10000000000000");
        }
        Map<String, Boolean> enabledDenoms = new HashMap<>();
        for (String id : Currencies.primaryDenominationIds()) {
            enabledDenoms.put(id, c.getBoolean("denominations." + id.replace('_', '-'), true));
        }

        saveIntervalSeconds = clamp(c.getInt("storage.save-interval-seconds", 5), 1, 300);

        baltopIntervalSeconds = clamp(c.getInt("baltop.update-interval-seconds", 300), 10, 86400);
        baltopPageLength = clamp(c.getInt("baltop.page-length", 10), 1, 45);
        baltopMinBalance = nonNegative(dec(c, "baltop.min-balance", "0.01"));

        payMinAmount = nonNegative(dec(c, "pay.min-amount", "0.01"));
        payFeePercent = percent(dec(c, "pay.fee-percent", "0"));

        withdrawMaxItems = clamp(c.getInt("withdraw.max-items", 2304), 1, 2304);

        chequeMinAmount = Math.max(1, c.getLong("cheque.min-amount", 1));
        chequeMaxAmount = Math.max(0, c.getLong("cheque.max-amount", 0));
        chequeFeePercent = percent(dec(c, "cheque.fee-percent", "0"));

        interestEnabled = c.getBoolean("interest.enabled", false);
        interestRatePercent = percent(dec(c, "interest.rate-percent", "0.1"));
        interestIntervalHours = clamp(c.getInt("interest.interval-hours", 24), 1, 8760);
        interestMaxPerPayout = nonNegative(dec(c, "interest.max-per-payout", "0"));
        interestOnlineOnly = c.getBoolean("interest.online-only", true);

        logEnabled = c.getBoolean("logs.enabled", true);
        logRetentionDays = clamp(c.getInt("logs.retention-days", 30), 1, 3650);
        logPluginTransactions = c.getBoolean("logs.log-plugin-transactions", true);
        logPageSize = clamp(c.getInt("logs.page-size", 10), 1, 45);

        alertAdmins = c.getBoolean("anti-counterfeit.alert-admins", true);
        confiscateInvalid = c.getBoolean("anti-counterfeit.confiscate-invalid", false);

        javaPackEnabled = c.getBoolean("resource-pack.java.enabled", true);
        javaPackRequired = c.getBoolean("resource-pack.java.required", false);
        javaPackUrl = c.getString("resource-pack.java.url", "").trim();
        javaPackDelayTicks = clamp(c.getInt("resource-pack.java.delay-ticks", 20), 0, 600);
        bedrockPackEnabled = c.getBoolean("resource-pack.bedrock.enabled", true);
        geyserFolder = c.getString("resource-pack.bedrock.geyser-folder", "").trim();
        coinThickness = clampD(c.getDouble("resource-pack.model.coin-thickness", 1.5), 0.25, 4);
        noteThickness = clampD(c.getDouble("resource-pack.model.note-thickness", 0.5), 0.25, 4);
        chequeThickness = clampD(c.getDouble("resource-pack.model.cheque-thickness", 0.5), 0.25, 4);
        chequeRollThickness = clampD(c.getDouble("resource-pack.model.cheque-roll-thickness", 2.0), 0.25, 4);

        chatInputTimeoutSeconds = clamp(c.getInt("gui.chat-input-timeout-seconds", 60), 10, 600);
        actionCooldownMs = clamp(c.getInt("gui.action-cooldown-ms", 500), 0, 10000);
        bedrockOpenDelayTicks = clamp(c.getInt("gui.bedrock-open-delay-ticks", 6), 1, 40);

        importTheosis = c.getBoolean("migration.import-theosis-economy", true);

        Currency primary = Currencies.primaryCurrency(decimalPlaces, defaultBalance, maxBalance, payMinAmount,
                payFeePercent, chequeMinAmount, chequeMaxAmount, chequeFeePercent, interestRatePercent, interestMaxPerPayout);
        currencies = Currencies.build(primary, enabledDenoms, c.getConfigurationSection("currencies"),
                coinThickness, noteThickness,
                (key, why) -> plugin.getLogger().warning("config.yml " + key + "：" + why));

        exchangeEnabled = c.getBoolean("exchange.enabled", false);
        exchangeFeePercent = percent(dec(c, "exchange.fee-percent", "0"));
        List<ExchangePair> pairs = new ArrayList<>();
        ConfigurationSection ps = c.getConfigurationSection("exchange.pairs");
        if (ps != null) {
            for (String name : ps.getKeys(false)) {
                ConfigurationSection e = ps.getConfigurationSection(name);
                if (e == null || !Currencies.CURRENCY_ID.matcher(name.replace("-", "_")).matches()) {
                    continue;
                }
                Currency from = currencies.get(e.getString("from", ""));
                Currency to = currencies.get(e.getString("to", ""));
                BigDecimal rate = dec(e, "rate", "0");
                if (from == null || to == null || from == to || rate.signum() <= 0) {
                    plugin.getLogger().warning("config.yml exchange.pairs." + name + "：from／to 要是不同的幣種，rate 要大於 0，已略過");
                    continue;
                }
                boolean floating = e.getString("mode", "fixed").trim().equalsIgnoreCase("floating");
                BigDecimal min = dec(e, "floating.min-rate", "0");
                BigDecimal maxRate = dec(e, "floating.max-rate", "0");
                pairs.add(new ExchangePair(name, from.id(), to.id(), floating, rate,
                        dec(e, "floating.liquidity", "100000").max(BigDecimal.ONE),
                        clampD(e.getDouble("floating.recover-percent-per-hour", 5), 0, 100),
                        min.signum() > 0 ? min : rate.divide(BigDecimal.TEN),
                        maxRate.signum() > 0 ? maxRate : rate.multiply(BigDecimal.TEN),
                        e.getBoolean("allow-reverse", true)));
            }
        }
        exchangePairs = List.copyOf(pairs);

        updateCheckOnStartup = c.getBoolean("update-checker.check-on-startup", true);
        updateNotifyOnJoin = c.getBoolean("update-checker.notify-on-join", true);
    }

    /**
     * 一組兌換：1 from ＝ rate to（兩個方向都能換）。
     *
     * @param liquidity 浮動匯率的深度（以 to 幣計），越大匯率越不容易被推動
     * @param recoverPercentPerHour 沒有交易時，每小時回到基準匯率的比例
     * @param allowReverse 能不能反方向換（to → from）
     */
    public record ExchangePair(String name, String from, String to, boolean floating, BigDecimal rate,
                               BigDecimal liquidity, double recoverPercentPerHour, BigDecimal minRate, BigDecimal maxRate,
                               boolean allowReverse) {
    }

    private static BigDecimal dec(ConfigurationSection c, String path, String fallback) {
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

    private static BigDecimal nonNegative(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    private static BigDecimal percent(BigDecimal v) {
        if (v.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return v.compareTo(BigDecimal.valueOf(100)) > 0 ? BigDecimal.valueOf(100) : v;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampD(double v, double min, double max) {
        if (Double.isNaN(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }

    public int lastFilled() { return lastFilled; }
    public int decimalPlaces() { return decimalPlaces; }
    public BigDecimal defaultBalance() { return defaultBalance; }
    public BigDecimal maxBalance() { return maxBalance; }
    public Currencies currencies() { return currencies; }
    public boolean exchangeEnabled() { return exchangeEnabled; }
    public BigDecimal exchangeFeePercent() { return exchangeFeePercent; }
    public List<ExchangePair> exchangePairs() { return exchangePairs; }
    public int saveIntervalSeconds() { return saveIntervalSeconds; }
    public int baltopIntervalSeconds() { return baltopIntervalSeconds; }
    public int baltopPageLength() { return baltopPageLength; }
    public BigDecimal baltopMinBalance() { return baltopMinBalance; }
    public BigDecimal payMinAmount() { return payMinAmount; }
    public BigDecimal payFeePercent() { return payFeePercent; }
    public int withdrawMaxItems() { return withdrawMaxItems; }
    public long chequeMinAmount() { return chequeMinAmount; }
    public long chequeMaxAmount() { return chequeMaxAmount; }
    public BigDecimal chequeFeePercent() { return chequeFeePercent; }
    public boolean interestEnabled() { return interestEnabled; }
    public BigDecimal interestRatePercent() { return interestRatePercent; }
    public int interestIntervalHours() { return interestIntervalHours; }
    public BigDecimal interestMaxPerPayout() { return interestMaxPerPayout; }
    public boolean interestOnlineOnly() { return interestOnlineOnly; }
    public boolean logEnabled() { return logEnabled; }
    public int logRetentionDays() { return logRetentionDays; }
    public boolean logPluginTransactions() { return logPluginTransactions; }
    public int logPageSize() { return logPageSize; }
    public boolean alertAdmins() { return alertAdmins; }
    public boolean confiscateInvalid() { return confiscateInvalid; }
    public boolean javaPackEnabled() { return javaPackEnabled; }
    public boolean javaPackRequired() { return javaPackRequired; }
    public String javaPackUrl() { return javaPackUrl; }
    public int javaPackDelayTicks() { return javaPackDelayTicks; }
    public boolean bedrockPackEnabled() { return bedrockPackEnabled; }
    public String geyserFolder() { return geyserFolder; }
    public double coinThickness() { return coinThickness; }
    public double noteThickness() { return noteThickness; }
    public double chequeThickness() { return chequeThickness; }
    public double chequeRollThickness() { return chequeRollThickness; }
    public int chatInputTimeoutSeconds() { return chatInputTimeoutSeconds; }
    public int actionCooldownMs() { return actionCooldownMs; }
    public int bedrockOpenDelayTicks() { return bedrockOpenDelayTicks; }
    public boolean importTheosis() { return importTheosis; }
    public boolean updateCheckOnStartup() { return updateCheckOnStartup; }
    public boolean updateNotifyOnJoin() { return updateNotifyOnJoin; }
}
