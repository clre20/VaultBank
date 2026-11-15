package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.config.PluginConfig.ExchangePair;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.economy.Exchange;
import clre20.vaultBank.service.Actions;
import clre20.vaultBank.util.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 兌換介面。
 * <ul>
 *   <li>選擇方向：每一組兌換有兩個方向（A→B、B→A），按鈕顯示目前匯率與餘額，左鍵後輸入要付出的數量。</li>
 *   <li>確認：顯示報價（付出、拿到、手續費、匯率），按「確認兌換」才成交；成交時重新報價，匯率變差就顯示新報價請玩家再確認。</li>
 * </ul>
 */
public final class ExchangeMenu extends Menu {

    private static final int MAX_DIRECTIONS = 28;
    // 確認畫面（27 格）
    private static final int PAY = 11;
    private static final int RATE = 13;
    private static final int GET = 15;
    private static final int CANCEL = 18;
    private static final int CONFIRM = 22;

    /** 一個方向：付出 pay，拿到 get。 */
    private record Direction(Currency pay, Currency get) {
    }

    private final List<Direction> directions = new ArrayList<>();
    private final Exchange.Quote quote;
    private final int back;

    /** 選擇方向的畫面。 */
    public ExchangeMenu(VaultBank plugin, Player viewer) {
        super(plugin, viewer);
        this.quote = null;
        for (ExchangePair p : plugin.exchange().pairs()) {
            Currency a = plugin.currency(p.from());
            Currency b = plugin.currency(p.to());
            if (a != null && b != null && directions.size() + 2 <= MAX_DIRECTIONS) {
                directions.add(new Direction(a, b));
                if (p.allowReverse()) {
                    directions.add(new Direction(b, a));
                }
            }
        }
        int size = directions.size() <= 7 ? 27 : 54;
        this.back = size - 9;
        create(size, "gui.exchange.title");
    }

    /** 確認報價的畫面。 */
    private ExchangeMenu(VaultBank plugin, Player viewer, Exchange.Quote quote) {
        super(plugin, viewer);
        this.quote = quote;
        this.back = CANCEL;
        create(27, "gui.exchange.confirm-title");
    }

    private int slotOf(int index) {
        if (inventory.getSize() == 27) {
            return 10 + index + (7 - directions.size()) / 2;
        }
        return 10 + (index / 7) * 9 + index % 7;
    }

    /** 幣種的圖示：用最大面額的外觀，沒有實體貨幣的幣種用向日葵。 */
    private ItemStack currencyIcon(Currency c, String key, Object... vars) {
        if (!c.denominations().isEmpty()) {
            Denomination top = c.denominations().get(0);
            return MainMenu.icon(plugin, plugin.appearance().model(top), key, this, vars);
        }
        return button(Material.SUNFLOWER, key, vars);
    }

    private String balance(Player p, Currency c) {
        return p == null ? "-" : plugin.format(c, Bank.get(plugin.bank().getOrCreate(p.getUniqueId(), p.getName()), c));
    }

    @Override
    protected void render() {
        fill(0, inventory.getSize());
        Player p = plugin.getServer().getPlayer(viewer);
        Actions actions = plugin.actions();
        if (quote == null) {
            for (int i = 0; i < directions.size(); i++) {
                Direction d = directions.get(i);
                BigDecimal r = plugin.exchange().rate(d.pay(), d.get());
                inventory.setItem(slotOf(i), currencyIcon(d.get(), "gui.exchange.direction",
                        "from", plugin.currencyName(d.pay()), "to", plugin.currencyName(d.get()),
                        "rate", r == null ? "-" : Actions.rateText(r),
                        "balance", balance(p, d.pay()),
                        "fee", plugin.config().exchangeFeePercent().stripTrailingZeros().toPlainString()));
            }
            inventory.setItem(back, button(Material.ARROW, "gui.common.back"));
            return;
        }
        Object[] vars = actions.quoteVars(quote, "balance", balance(p, quote.pay()));
        inventory.setItem(PAY, currencyIcon(quote.pay(), "gui.exchange.pay", vars));
        inventory.setItem(RATE, button(Material.COMPASS, "gui.exchange.rate", vars));
        inventory.setItem(GET, currencyIcon(quote.get(), "gui.exchange.get", vars));
        inventory.setItem(CANCEL, button(Material.ARROW, "gui.common.back"));
        inventory.setItem(CONFIRM, button(Material.LIME_CONCRETE, "gui.exchange.confirm", vars));
    }

    @Override
    protected void onButton(Player player, int slot) {
        if (!allowed(player, "vaultbank.exchange")) {
            return;
        }
        if (!plugin.exchange().enabled()) {
            plugin.messages().send(player, "exchange.disabled");
            player.closeInventory();
            return;
        }
        if (quote == null) {
            if (slot == back) {
                new MainMenu(plugin, player).open(player);
                return;
            }
            for (int i = 0; i < directions.size(); i++) {
                if (slotOf(i) == slot) {
                    ask(player, directions.get(i));
                    return;
                }
            }
            return;
        }
        switch (slot) {
            case CANCEL -> new ExchangeMenu(plugin, player).open(player);
            case CONFIRM -> {
                if (plugin.tooFast(player)) {
                    return;
                }
                Currency pay = plugin.config().currencies().enabled(quote.pay().id());
                Currency get = plugin.config().currencies().enabled(quote.get().id());
                Exchange.Quote q = plugin.exchange().execute(player.getUniqueId(), pay, get, quote.amountIn(), quote.amountOut());
                if (q.status() == Exchange.Status.CHANGED) {
                    // 匯率變差：顯示新報價，請玩家再確認一次
                    plugin.messages().send(player, "exchange.changed", plugin.actions().quoteVars(q));
                    new ExchangeMenu(plugin, player, new Exchange.Quote(Exchange.Status.OK, q.pair(), q.forward(),
                            q.pay(), q.get(), q.amountIn(), q.amountOut(), q.fee(), q.rate(), q.newRate())).open(player);
                    return;
                }
                plugin.actions().reportExchange(player, q);
                new ExchangeMenu(plugin, player).open(player);
            }
            default -> {
            }
        }
    }

    /** 輸入要付出的數量，報價後開啟確認畫面。 */
    private void ask(Player player, Direction d) {
        plugin.chatInput().ask(player, "input.exchange-amount",
                (pl, text) -> {
                    if (!pl.hasPermission("vaultbank.exchange")) {
                        return;
                    }
                    BigDecimal amount = Money.parse(text);
                    if (amount == null || amount.signum() <= 0) {
                        plugin.messages().send(pl, "error.invalid-amount", "input", text);
                        new ExchangeMenu(plugin, pl).open(pl);
                        return;
                    }
                    Currency pay = plugin.config().currencies().enabled(d.pay().id());
                    Currency get = plugin.config().currencies().enabled(d.get().id());
                    Exchange.Quote q = plugin.exchange().quote(pay, get, amount);
                    if (q.status() == Exchange.Status.TOO_MANY_DECIMALS) {
                        plugin.actions().decimalsError(pl, d.pay());
                        new ExchangeMenu(plugin, pl).open(pl);
                        return;
                    }
                    if (!q.ok()) {
                        plugin.messages().send(pl, Actions.exchangeErrorKey(q.status()),
                                q.pay() == null ? new Object[]{"from", plugin.currencyName(d.pay()), "to", plugin.currencyName(d.get())}
                                        : plugin.actions().quoteVars(q));
                        new ExchangeMenu(plugin, pl).open(pl);
                        return;
                    }
                    new ExchangeMenu(plugin, pl, q).open(pl);
                },
                () -> new ExchangeMenu(plugin, player).open(player),
                "from", plugin.currencyName(d.pay()), "to", plugin.currencyName(d.get()),
                "digits", d.pay().scale());
    }
}
