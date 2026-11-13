package clre20.vaultBank.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Pattern;

/** 金額解析與格式化。 */
public final class Money {

    /** 只接受一般數字（可有小數點與千分位逗號），不接受科學記號、正負號以外的字元。 */
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]{0,24}(\\.[0-9]{1,8})?");

    private Money() {
    }

    /** 解析玩家輸入的金額；格式不對回傳 null。不檢查小數位數，由呼叫端檢查。 */
    public static BigDecimal parse(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.length() > 32 || !NUMBER.matcher(s).matches()) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 小數位數是否不超過 scale。 */
    public static boolean fitsScale(BigDecimal v, int scale) {
        return v.stripTrailingZeros().scale() <= scale;
    }

    /** 解析正整數（例如支票金額、面額數量），格式不對或超過範圍回傳 -1。 */
    public static long parseWhole(String input, long max) {
        BigDecimal v = parse(input);
        if (v == null || !fitsScale(v, 0) || v.signum() <= 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) {
            return -1;
        }
        return v.longValueExact();
    }

    /** 加上千分位；整數不顯示小數，有小數時固定顯示到設定的位數（例如 12.50）。 */
    public static String format(BigDecimal v, int scale) {
        BigDecimal r = v.setScale(scale, RoundingMode.HALF_UP);
        boolean whole = r.stripTrailingZeros().scale() <= 0;
        DecimalFormat f = new DecimalFormat(scale > 0 && !whole ? "#,##0." + "0".repeat(scale) : "#,##0",
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(r);
    }

    /** 不加千分位的數字（紀錄與後台用）。 */
    public static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
