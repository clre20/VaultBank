package clre20.vaultBank.currency;

/** 實體貨幣的面額。面額值與外觀綁在一起（材質包），所以固定這 7 種，可在 config 個別停用。 */
public enum Denomination {
    COIN_1(1, true, "coin_1"),
    COIN_5(5, true, "coin_5"),
    COIN_10(10, true, "coin_10"),
    COIN_50(50, true, "coin_50"),
    NOTE_100(100, false, "note_100"),
    NOTE_500(500, false, "note_500"),
    NOTE_1000(1000, false, "note_1000");

    private static final Denomination[] DESCENDING = {
            NOTE_1000, NOTE_500, NOTE_100, COIN_50, COIN_10, COIN_5, COIN_1
    };

    private final int value;
    private final boolean coin;
    private final String id;

    Denomination(int value, boolean coin, String id) {
        this.value = value;
        this.coin = coin;
        this.id = id;
    }

    public int value() {
        return value;
    }

    public boolean coin() {
        return coin;
    }

    /** 材質包與 PDC 用的識別名稱，例如 coin_1。 */
    public String id() {
        return id;
    }

    /** config.yml 的鍵名，例如 coin-1。 */
    public String configKey() {
        return id.replace('_', '-');
    }

    public static Denomination byValue(int value) {
        for (Denomination d : values()) {
            if (d.value == value) {
                return d;
            }
        }
        return null;
    }

    public static Denomination byId(String id) {
        for (Denomination d : values()) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }

    /** 由大到小。 */
    public static Denomination[] descending() {
        return DESCENDING.clone();
    }
}
