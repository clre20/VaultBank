package clre20.vaultBank.hook;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.config.PluginConfig.ExchangePair;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.economy.Account;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.service.Actions;
import clre20.vaultBank.util.Money;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * PlaceholderAPI 變數（只有安裝 PlaceholderAPI 時才會載入這個類別）。
 * <pre>
 * %vaultbank_balance_&lt;幣種&gt;%            餘額數字，例如 1234.5
 * %vaultbank_balance_formatted_&lt;幣種&gt;%  格式化，例如 1,234.5 元
 * %vaultbank_rank_&lt;幣種&gt;%               排名（未上榜為 0）
 * %vaultbank_rate_&lt;付出&gt;_&lt;換到&gt;%       目前匯率（1 付出 ＝ ? 換到）
 * %vaultbank_currency_&lt;幣種&gt;%           幣種名稱
 * </pre>
 * 省略幣種（%vaultbank_balance%）時用主貨幣「元」。只讀記憶體，不查資料庫，可在任何執行緒呼叫。
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final VaultBank plugin;

    public PlaceholderHook(VaultBank plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "vaultbank";
    }

    @Override
    public String getAuthor() {
        return "clre20";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params.length() > 80) {
            return null;
        }
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("rate_")) {
            return rate(p.substring(5));
        }
        if (p.equals("currency") || p.startsWith("currency_")) {
            Currency c = currency(p, "currency");
            return c == null ? "" : plugin.currencyName(c);
        }
        if (p.equals("balance_formatted") || p.startsWith("balance_formatted_")) {
            Currency c = currency(p, "balance_formatted");
            return c == null ? "" : plugin.format(c, balance(player, c));
        }
        if (p.equals("balance") || p.startsWith("balance_")) {
            Currency c = currency(p, "balance");
            return c == null ? "" : Money.plain(balance(player, c));
        }
        if (p.equals("rank") || p.startsWith("rank_")) {
            Currency c = currency(p, "rank");
            if (c == null || player == null || !c.features().baltop()) {
                return "0";
            }
            return String.valueOf(plugin.baltop().rankOf(player.getUniqueId(), c.id()));
        }
        return null;
    }

    /** "balance" → 主貨幣；"balance_penguin" → penguin。 */
    private Currency currency(String p, String prefix) {
        if (p.length() == prefix.length()) {
            return plugin.config().currencies().primary();
        }
        return plugin.config().currencies().enabled(p.substring(prefix.length() + 1));
    }

    private BigDecimal balance(OfflinePlayer player, Currency c) {
        if (player == null) {
            return BigDecimal.ZERO;
        }
        Account a = plugin.bank().account(player.getUniqueId());
        return a == null ? BigDecimal.ZERO : Bank.get(a, c);
    }

    /** "yuan_penguin"：幣種名稱可能含底線，所以逐一比對兌換組。 */
    private String rate(String rest) {
        for (ExchangePair pair : plugin.exchange().pairs()) {
            for (int dir = 0; dir < (pair.allowReverse() ? 2 : 1); dir++) {
                String a = dir == 0 ? pair.from() : pair.to();
                String b = dir == 0 ? pair.to() : pair.from();
                if (rest.equals(a + "_" + b)) {
                    BigDecimal r = plugin.exchange().rate(plugin.currency(a), plugin.currency(b));
                    return r == null ? "" : Actions.rateText(r);
                }
            }
        }
        return "";
    }
}
