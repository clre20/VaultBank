package clre20.vaultBank.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * VaultBank 給其他插件用的多幣種 API（Vault 只能用「元」）。
 * 用 {@code Bukkit.getServicesManager().load(VaultBankAPI.class)} 取得。
 * 所有方法都可以在任何執行緒呼叫；幣種 id 例如 "yuan"、"penguin"，不分大小寫。
 * 停用或不存在的幣種：查詢回傳 0／false，扣款與存款回傳 false。
 */
public interface VaultBankAPI {

    /** 主貨幣（Vault 綁定的幣種）的 id。 */
    String primaryCurrency();

    /** 啟用中的幣種 id。 */
    List<String> currencies();

    /** 幣種是否存在且啟用。 */
    boolean isEnabled(String currency);

    /** 幣種名稱，例如「企鵝幣」。 */
    String currencyName(String currency);

    /** 小數位數（0 表示只能是整數）。 */
    int decimalPlaces(String currency);

    /** 金額顯示，例如「1,234 企鵝幣」。 */
    String format(String currency, BigDecimal amount);

    /** 餘額（沒有帳戶回傳 0）。 */
    BigDecimal balance(UUID player, String currency);

    boolean has(UUID player, String currency, BigDecimal amount);

    /**
     * 扣款。餘額不足、幣種停用、金額無效時回傳 false，不會扣任何錢。
     *
     * @param source 來源（例如插件名稱），會寫在交易紀錄裡，可為 null
     */
    boolean withdraw(UUID player, String currency, BigDecimal amount, String source);

    /** 存款。超過餘額上限、幣種停用、金額無效時回傳 false。 */
    boolean deposit(UUID player, String currency, BigDecimal amount, String source);
}
