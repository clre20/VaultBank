package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.economy.Account;
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
        String balance = a == null ? "0" : plugin.format(a.balance());
        int rank = a == null ? 0 : plugin.baltop().rankOf(a.id());

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (p != null && head.getItemMeta() instanceof SkullMeta skull) {
            // 用線上玩家現成的外觀資料，伺服器不用再向 Mojang 查詢
            skull.setPlayerProfile(p.getPlayerProfile());
            head.setItemMeta(skull);
        }
        inventory.setItem(INFO, decorate(head, "gui.main.info", "balance", balance,
                "rank", rank > 0 ? plugin.messages().plain("balance.rank", "rank", rank) : plugin.messages().plain("balance.no-rank"),
                "player", p == null ? "" : p.getName()));
        inventory.setItem(DEPOSIT, button(Material.CHEST, "gui.main.deposit"));
        inventory.setItem(WITHDRAW, icon("note_100", "gui.main.withdraw"));
        inventory.setItem(CHEQUE, icon("cheque", "gui.main.cheque"));
        inventory.setItem(LOG, button(Material.WRITABLE_BOOK, "gui.main.log",
                "days", plugin.config().logRetentionDays()));
        inventory.setItem(PAY, icon("coin_50", "gui.main.pay"));
        boolean accepting = a == null || a.accepting();
        inventory.setItem(TOGGLE, button(accepting ? Material.LIME_DYE : Material.GRAY_DYE,
                accepting ? "gui.main.toggle-on" : "gui.main.toggle-off"));
        inventory.setItem(CLOSE, button(Material.BARRIER, "gui.common.close"));
    }

    /** 用貨幣外觀當按鈕圖示（只借外觀，不是真的錢）。 */
    private ItemStack icon(String model, String key) {
        return icon(plugin, model, key, this);
    }

    static ItemStack icon(VaultBank plugin, String model, String key, Menu menu, Object... vars) {
        // 和真的貨幣用同一種底層物品，基岩版（Geyser 依底層物品＋外觀對應）才看得到圖示
        ItemStack item = new ItemStack(clre20.vaultBank.currency.CurrencyItems.BASE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new org.bukkit.NamespacedKey("vaultbank", model));
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
                    new WithdrawMenu(plugin, player).open(player);
                }
            }
            case CHEQUE -> {
                if (allowed(player, "vaultbank.cheque")) {
                    plugin.chatInput().ask(player, "input.cheque-amount",
                            (pl, text) -> {
                                if (pl.hasPermission("vaultbank.cheque")) {
                                    plugin.actions().cheque(pl, text);
                                }
                                new MainMenu(plugin, pl).open(pl);
                            },
                            () -> new MainMenu(plugin, player).open(player));
                }
            }
            case LOG -> {
                if (allowed(player, "vaultbank.log")) {
                    LogMenu.open(plugin, player, player.getUniqueId(), player.getName(), 0, true);
                }
            }
            case PAY -> {
                if (allowed(player, "vaultbank.pay")) {
                    plugin.chatInput().ask(player, "input.pay",
                            (pl, text) -> {
                                String[] parts = text.trim().split("\\s+");
                                if (parts.length != 2) {
                                    plugin.messages().send(pl, "input.pay-format");
                                } else if (pl.hasPermission("vaultbank.pay")) {
                                    plugin.actions().pay(pl, parts[0], parts[1]);
                                }
                                new MainMenu(plugin, pl).open(pl);
                            },
                            () -> new MainMenu(plugin, player).open(player));
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
