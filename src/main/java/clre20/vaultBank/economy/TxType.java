package clre20.vaultBank.economy;

/** 交易紀錄的種類；顯示文字在 messages.yml 的 log.types.<小寫名稱>。 */
public enum TxType {
    DEPOSIT_ITEM,
    WITHDRAW_ITEM,
    CHEQUE_ISSUE,
    CHEQUE_REDEEM,
    PAY_OUT,
    PAY_IN,
    ADMIN_GIVE,
    ADMIN_TAKE,
    ADMIN_SET,
    ADMIN_RESET,
    PLUGIN_IN,
    PLUGIN_OUT,
    INTEREST,
    IMPORT;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static TxType parse(String s) {
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
