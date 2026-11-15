package clre20.vaultBank.economy;

import clre20.vaultBank.currency.Currency;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 一個玩家的帳戶。餘額的讀寫一律透過 Bank，並以這個物件當鎖。 */
public final class Account {

    private final UUID id;
    private volatile String name;
    /** 主貨幣（元）的餘額。 */
    private volatile BigDecimal balance;
    /** 其他幣種的餘額（沒有＝0）。 */
    private final Map<String, BigDecimal> others = new ConcurrentHashMap<>();
    private volatile boolean accepting;

    Account(UUID id, String name, BigDecimal balance, boolean accepting) {
        this.id = id;
        this.name = name;
        this.balance = balance;
        this.accepting = accepting;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    void name(String name) {
        this.name = name;
    }

    /** 主貨幣餘額。 */
    public BigDecimal balance() {
        return balance;
    }

    void balance(BigDecimal balance) {
        this.balance = balance;
    }

    /** 指定幣種的餘額（沒有時為 null，呼叫端自己當成 0）。 */
    public BigDecimal balance(String currency) {
        return Currency.PRIMARY.equals(currency) ? balance : others.get(currency);
    }

    void balance(String currency, BigDecimal value) {
        if (Currency.PRIMARY.equals(currency)) {
            balance = value;
        } else {
            others.put(currency, value);
        }
    }

    /** 其他幣種的餘額（唯讀快照，給存檔用）。 */
    Map<String, BigDecimal> others() {
        return Map.copyOf(others);
    }

    public boolean accepting() {
        return accepting;
    }

    void accepting(boolean accepting) {
        this.accepting = accepting;
    }
}
