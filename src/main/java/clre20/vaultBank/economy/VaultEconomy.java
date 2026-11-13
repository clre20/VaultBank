package clre20.vaultBank.economy;

import clre20.vaultBank.VaultBank;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 給其他插件透過 Vault 使用的經濟介面。可能在任何執行緒被呼叫，這裡不碰 Bukkit 世界與玩家物件以外的狀態。
 * 不支援 Vault 的「銀行」（多人共用帳戶）功能。
 */
public final class VaultEconomy implements Economy {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final VaultBank plugin;

    public VaultEconomy(VaultBank plugin) {
        this.plugin = plugin;
    }

    private Bank bank() {
        return plugin.bank();
    }

    @Override
    public boolean isEnabled() {
        return plugin.economyActive();
    }

    @Override
    public String getName() {
        return "VaultBank";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return plugin.config().decimalPlaces();
    }

    @Override
    public String format(double amount) {
        if (!Double.isFinite(amount)) {
            return String.valueOf(amount);
        }
        return plugin.format(BigDecimal.valueOf(amount));
    }

    @Override
    public String currencyNamePlural() {
        return plugin.messages().plain("currency.name-plural");
    }

    @Override
    public String currencyNameSingular() {
        return plugin.messages().plain("currency.name-singular");
    }

    // ---------- 帳戶 ----------

    private Account resolve(OfflinePlayer player, boolean create) {
        if (player == null) {
            return null;
        }
        Account a = bank().account(player.getUniqueId());
        if (a == null && create) {
            a = bank().getOrCreateWeak(player.getUniqueId(), player.getName());
        }
        return a;
    }

    private Account resolve(String name) {
        return bank().byName(name);
    }

    @Override
    public boolean hasAccount(String playerName) {
        return resolve(playerName) != null;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null && bank().account(player.getUniqueId()) != null;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        Account a = resolve(playerName);
        return a == null ? 0 : a.balance().doubleValue();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        Account a = resolve(player, false);
        return a == null ? 0 : a.balance().doubleValue();
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        Account a = resolve(playerName);
        return a != null && hasAmount(a, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        Account a = resolve(player, false);
        return a != null && hasAmount(a, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    private boolean hasAmount(Account a, double amount) {
        if (!Double.isFinite(amount)) {
            return false;
        }
        return a.balance().compareTo(bank().scale(BigDecimal.valueOf(amount))) >= 0;
    }

    // ---------- 存提 ----------

    private EconomyResponse withdraw(Account a, double amount) {
        if (a == null) {
            return new EconomyResponse(amount, 0, ResponseType.FAILURE, "Account not found");
        }
        if (!plugin.economyActive()) {
            return new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, "Economy is not active");
        }
        if (!Double.isFinite(amount) || amount < 0) {
            return new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, "Invalid amount");
        }
        if (amount == 0) {
            return new EconomyResponse(0, a.balance().doubleValue(), ResponseType.SUCCESS, null);
        }
        BigDecimal v = bank().scale(BigDecimal.valueOf(amount));
        if (v.signum() == 0) {
            return new EconomyResponse(0, a.balance().doubleValue(), ResponseType.SUCCESS, null);
        }
        Bank.Result r = bank().withdraw(a.id(), v, TxType.PLUGIN_OUT, null, caller(), null);
        return switch (r) {
            case OK -> new EconomyResponse(v.doubleValue(), a.balance().doubleValue(), ResponseType.SUCCESS, null);
            case INSUFFICIENT -> new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, "Insufficient funds");
            default -> new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, r.name());
        };
    }

    private EconomyResponse deposit(Account a, double amount) {
        if (a == null) {
            return new EconomyResponse(amount, 0, ResponseType.FAILURE, "Account not found");
        }
        if (!plugin.economyActive()) {
            return new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, "Economy is not active");
        }
        if (!Double.isFinite(amount) || amount < 0) {
            return new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, "Invalid amount");
        }
        if (amount == 0) {
            return new EconomyResponse(0, a.balance().doubleValue(), ResponseType.SUCCESS, null);
        }
        BigDecimal v = bank().scale(BigDecimal.valueOf(amount));
        if (v.signum() == 0) {
            return new EconomyResponse(0, a.balance().doubleValue(), ResponseType.SUCCESS, null);
        }
        Bank.Result r = bank().deposit(a.id(), v, TxType.PLUGIN_IN, null, caller(), null);
        return switch (r) {
            case OK -> new EconomyResponse(v.doubleValue(), a.balance().doubleValue(), ResponseType.SUCCESS, null);
            case MAX_BALANCE -> new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, "Exceeds maximum balance");
            default -> new EconomyResponse(amount, a.balance().doubleValue(), ResponseType.FAILURE, r.name());
        };
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdraw(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(resolve(player, true), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return deposit(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(resolve(player, true), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // ---------- 建立帳戶 ----------

    @Override
    public boolean createPlayerAccount(String playerName) {
        return resolve(playerName) != null;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return resolve(player, true) != null;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ---------- 銀行（不支援） ----------

    private static EconomyResponse noBank() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "VaultBank does not support Vault banks");
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBank();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBank();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBank();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    // ---------- 呼叫來源 ----------

    /** 找出呼叫 Vault 的插件名稱（交易紀錄用）。 */
    private String caller() {
        if (!plugin.config().logEnabled() || !plugin.config().logPluginTransactions()) {
            return null;
        }
        ClassLoader own = VaultEconomy.class.getClassLoader();
        Optional<Class<?>> found = WALKER.walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> {
                    ClassLoader cl = c.getClassLoader();
                    return cl != null && cl != own && cl.getClass().getName().contains("PluginClassLoader")
                            && !c.getName().startsWith("net.milkbowl.vault.");
                })
                .findFirst());
        if (found.isEmpty()) {
            return null;
        }
        try {
            return JavaPlugin.getProvidingPlugin(found.get()).getName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
