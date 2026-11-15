package clre20.vaultBank.api;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.economy.Account;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.economy.TxType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@link VaultBankAPI} 的實作。帳戶操作交給 Bank（每個帳戶各自上鎖，執行緒安全）。 */
public final class VaultBankService implements VaultBankAPI {

    private final VaultBank plugin;

    public VaultBankService(VaultBank plugin) {
        this.plugin = plugin;
    }

    private Currency cur(String id) {
        return plugin.economyActive() ? plugin.config().currencies().enabled(id) : null;
    }

    /** 離線玩家也建立帳戶，但不寫入名稱以外的資料；名稱用伺服器快取，不連 Mojang。 */
    private Account account(UUID id) {
        Account a = plugin.bank().account(id);
        if (a != null) {
            return a;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String name = op.getName() != null ? op.getName() : id.toString().substring(0, 8);
        return plugin.bank().getOrCreateWeak(id, name);
    }

    @Override
    public String primaryCurrency() {
        return Currency.PRIMARY;
    }

    @Override
    public List<String> currencies() {
        List<String> out = new ArrayList<>();
        for (Currency c : plugin.config().currencies().allEnabled()) {
            out.add(c.id());
        }
        return out;
    }

    @Override
    public boolean isEnabled(String currency) {
        return cur(currency) != null;
    }

    @Override
    public String currencyName(String currency) {
        Currency c = plugin.config().currencies().get(currency);
        return c == null ? String.valueOf(currency) : plugin.currencyName(c);
    }

    @Override
    public int decimalPlaces(String currency) {
        Currency c = plugin.config().currencies().get(currency);
        return c == null ? 0 : c.scale();
    }

    @Override
    public String format(String currency, BigDecimal amount) {
        Currency c = plugin.config().currencies().get(currency);
        return c == null ? String.valueOf(amount) : plugin.format(c, amount);
    }

    @Override
    public BigDecimal balance(UUID player, String currency) {
        Currency c = cur(currency);
        if (c == null || player == null) {
            return BigDecimal.ZERO;
        }
        Account a = plugin.bank().account(player);
        return a == null ? BigDecimal.ZERO : Bank.get(a, c);
    }

    @Override
    public boolean has(UUID player, String currency, BigDecimal amount) {
        return amount != null && balance(player, currency).compareTo(amount) >= 0;
    }

    @Override
    public boolean withdraw(UUID player, String currency, BigDecimal amount, String source) {
        Currency c = cur(currency);
        if (c == null || player == null || amount == null || amount.signum() <= 0) {
            return false;
        }
        account(player);
        return plugin.bank().withdraw(player, c, amount, TxType.PLUGIN_OUT, null, source(source), null) == Bank.Result.OK;
    }

    @Override
    public boolean deposit(UUID player, String currency, BigDecimal amount, String source) {
        Currency c = cur(currency);
        if (c == null || player == null || amount == null || amount.signum() <= 0) {
            return false;
        }
        account(player);
        return plugin.bank().deposit(player, c, amount, TxType.PLUGIN_IN, null, source(source), null) == Bank.Result.OK;
    }

    private static String source(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
}
