package clre20.vaultBank.currency;

import java.util.Locale;

/**
 * 一種實體貨幣面額（屬於某個幣種）。
 * 主貨幣「元」的 7 種面額固定；其他幣種的面額寫在 config.yml 的 currencies.&lt;幣種&gt;.denominations。
 * id 在所有幣種之間唯一，也是物品外觀（材質包）與防偽簽章用的名稱，例如 coin_1、penguin_1。
 */
public final class Denomination {

    /** 預設外觀的形狀，決定自動加厚度時用哪個厚度設定。 */
    public enum Shape { COIN, NOTE }

    private final Currency currency;
    private final String id;
    private final long value;
    private final boolean enabled;
    private final Shape shape;
    private final double thickness;
    private final String material;
    private final boolean forceVanilla;

    /**
     * @param thickness 用圖片自動加厚度時的厚度（一格方塊＝16）；0 以下表示用形狀的預設厚度
     * @param material  沒有提供模型與圖片時使用的原版物品外觀（例如 GOLD_NUGGET），小寫 key
     */
    Denomination(Currency currency, String id, long value, boolean enabled, Shape shape, double thickness, String material,
                 boolean forceVanilla) {
        this.forceVanilla = forceVanilla;
        this.currency = currency;
        this.id = id;
        this.value = value;
        this.enabled = enabled;
        this.shape = shape;
        this.thickness = thickness;
        this.material = material == null ? "gold_nugget" : material.toLowerCase(Locale.ROOT);
    }

    public Currency currency() {
        return currency;
    }

    public String id() {
        return id;
    }

    public long value() {
        return value;
    }

    /** 這個面額可以提取（停用後仍可存入已發出去的）。 */
    public boolean enabled() {
        return enabled;
    }

    public Shape shape() {
        return shape;
    }

    public boolean coin() {
        return shape == Shape.COIN;
    }

    public double thickness() {
        return thickness;
    }

    public String material() {
        return material;
    }

    /** config 設定 appearance: vanilla，不使用模型或圖片。 */
    public boolean forceVanilla() {
        return forceVanilla;
    }

    @Override
    public String toString() {
        return id;
    }
}
