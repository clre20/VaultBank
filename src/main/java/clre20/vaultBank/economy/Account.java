package clre20.vaultBank.economy;

import java.math.BigDecimal;
import java.util.UUID;

/** 一個玩家的帳戶。餘額的讀寫一律透過 Bank，並以這個物件當鎖。 */
public final class Account {

    private final UUID id;
    private volatile String name;
    private volatile BigDecimal balance;
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

    public BigDecimal balance() {
        return balance;
    }

    void balance(BigDecimal balance) {
        this.balance = balance;
    }

    public boolean accepting() {
        return accepting;
    }

    void accepting(boolean accepting) {
        this.accepting = accepting;
    }
}
