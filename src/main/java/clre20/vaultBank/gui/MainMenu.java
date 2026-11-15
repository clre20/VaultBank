package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.economy.Account;
import clre20.vaultBank.economy.Bank;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** 銀行主選單（27 格）。 */
public final class MainMenu extends Menu {

    static final int INFO = 4;
    static final int DEPOSIT = 10;
    static final int WITHDRAW = 12;
    static final int CHEQUE = 14;
    static final int LOG = 16;
    static final int PAY = 20;
    static final int EXCHANGE = 22;
    static final int TOGGLE = 24;
    static final int CLOSE = 26;

    public MainMenu(VaultBank plugin, Player viewer) {
        super(plugin, viewer);
        create(27, "gui.main.title");
    }

    @Override
    protected void render() {
        fill(0, 27);
        Player p = plugin.getServer().getPlayer(viewer);
        Account a = p == null ? null : plugin.bank().getOrCreate(p.getUniqueId(), p.getName());
        Currency primary = plugin.config().currencies().primary();
        String balance = a == null ? "0" : plugin.format(primary, a.balance());
        int rank = a == null ? 0 : plugin.baltop().rankOf(a.id());

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (p != null && head.getItemMeta() instanceof SkullMeta skull) {
            // 用線上玩家現成的外觀資料，伺服器不用再向 Mojang 查詢
            skull.setPlayerProfile(p.getPlayerProfile());
            head.setItemMeta(skull);
        }
        decorate(head, "gui.main.info", "balance", balance,
                "rank", rankText(rank), "player", p == null ? "" : p.getName());
        List<Currency> currencies = plugin.config().currencies().allEnabled();
        if (a != null && currencies.size() > 1) {
            // 多個幣種：每個幣種一行
            ItemMeta meta = head.getItemMeta();
            List<Component> lore = new ArrayList<>();
            for (Currency c : currencies) {
                int r = c.features().baltop() ? plugin.baltop().rankOf(a.id(), c.id()) : 0;
                lore.add(plugin.messages().item(c.features().baltop() ? "gui.main.info.balance-line"
                                : "gui.main.info.balance-line-no-rank", "currency", plugin.currencyName(c),
                        "balance", plugin.format(c, Bank.get(a, c)), "rank", rankText(r)));
            }
            meta.lore(lore);
            head.setItemMeta(meta);
        }
        inventory.setItem(INFO, head);
        inventory.setItem(DEPOSIT, button(Material.CHEST, "gui.main.deposit"));
        inventory.setItem(WITHDRAW, icon(model("note_100"), "gui.main.withdraw"));
        inventory.setItem(CHEQUE, icon(new NamespacedKey("vaultbank", "cheque"), "gui.main.cheque"));
        inventory.setItem(LOG, button(Material.WRITABLE_BOOK, "gui.main.log",
                "days", plugin.config().logRetentionDays()));
        inventory.setItem(PAY, icon(model("coin_50"), "gui.main.pay"));
        if (plugin.exchange().enabled() && p != null && p.hasPermission("vaultbank.exchange")) {
            inventory.setItem(EXCHANGE, button(Material.EMERALD, "gui.main.exchange"));
        }
        boolean accepting = a == null || a.accepting();
        inventory.setItem(TOGGLE, button(accepting ? Material.LIME_DYE : Material.GRAY_DYE,
                accepting ? "gui.main.toggle-on" : "gui.main.toggle-off"));
        inventory.setItem(CLOSE, button(Material.BARRIER, "gui.common.close"));
    }

    private String rankText(int rank) {
        return rank > 0 ? plugin.messages().plain("balance.rank", "rank", rank) : plugin.messages().plain("balance.no-rank");
    }

    private NamespacedKey model(String denomId) {
        Denomination d = plugin.config().currencies().denomination(denomId);
        return d == null ? new NamespacedKey("vaultbank", denomId) : plugin.appearance().model(d);
    }

    /** 用貨幣外觀當按鈕圖示（只借外觀，不是真的錢）。 */
    private ItemStack icon(NamespacedKey model, String key) {
        return icon(plugin, model, key, this);
    }

    static ItemStack icon(VaultBank plugin, NamespacedKey model, String key, Menu menu, Object... vars) {
        // 和真的貨幣用同一種底層物品，基岩版（Geyser 依底層物品＋外觀對應）才看得到圖示
        ItemStack item = new ItemStack(clre20.vaultBank.currency.CurrencyItems.BASE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model);
        meta.setMaxStackSize(64);
        item.setItemMeta(meta);
        return menu.decorate(item, key, vars);
    }

    @Override
    protected void onButton(Player player, int slot) {
        if (!allowed(player, "vaultbank.use")) {
            return;
        }
        switch (slot) {
            case DEPOSIT -> {
                if (allowed(player, "vaultbank.deposit")) {
                    new DepositMenu(plugin, player).open(player);
                }
            }
            case WITHDRAW -> {
                if (allowed(player, "vaultbank.withdraw")) {
                    CurrencyMenu.open(plugin, player, CurrencyMenu.Purpose.WITHDRAW);
                }
            }
            case CHEQUE -> {
                if (allowed(player, "vaultbank.cheque")) {
                    CurrencyMenu.open(plugin, player, CurrencyMenu.Purpose.CHEQUE);
                }
            }
            case EXCHANGE -> {
                if (plugin.exchange().enabled() && allowed(player, "vaultbank.exchange")) {
                    new ExchangeMenu(plugin, player).open(player);
                }
            }
            case LOG -> {
                if (allowed(player, "vaultbank.log")) {
                    LogMenu.open(plugin, player, player.getUniqueId(), player.getName(), 0, true);
                }
            }
            case PAY -> {
                if (allowed(player, "vaultbank.pay")) {
                    CurrencyMenu.open(plugin, player, CurrencyMenu.Purpose.PAY);
                }
            }
            case TOGGLE -> {
                if (allowed(player, "vaultbank.paytoggle")) {
                    plugin.actions().togglePay(player);
                    render();
                }
            }
            case CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }
}
