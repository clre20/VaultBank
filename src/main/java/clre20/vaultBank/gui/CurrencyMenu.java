package clre20.vaultBank.gui;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currency;
import clre20.vaultBank.currency.Denomination;
import clre20.vaultBank.economy.Bank;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 選擇幣種（提取、開支票、轉帳前）。只有一個幣種可用時直接進下一步，不顯示這個介面。
 */
public final class CurrencyMenu extends Menu {

    public enum Purpose {
        WITHDRAW("vaultbank.withdraw", "withdraw"),
        CHEQUE("vaultbank.cheque", "cheque"),
        PAY("vaultbank.pay", "pay");

        final String permission;
        final String key;

        Purpose(String permission, String key) {
            this.permission = permission;
            this.key = key;
        }

        boolean allows(Currency c) {
            return switch (this) {
                case WITHDRAW -> c.features().physical() && !c.denominations().isEmpty();
                case CHEQUE -> c.features().cheque();
                case PAY -> c.features().pay();
            };
        }
    }

    private static final int MAX = 28;

    private final Purpose purpose;
    private final List<Currency> list;
    private final int back;

    private CurrencyMenu(VaultBank plugin, Player viewer, Purpose purpose, List<Currency> list) {
        super(plugin, viewer);
        this.purpose = purpose;
        this.list = list;
        int size = list.size() <= 7 ? 27 : 54;
        this.back = size - 9;
        create(size, "gui.currency.title." + purpose.key);
    }

    /** 開啟選擇；只有一個可用的幣種時直接進下一步。 */
    public static void open(VaultBank plugin, Player player, Purpose purpose) {
        List<Currency> list = new ArrayList<>();
        for (Currency c : plugin.config().currencies().allEnabled()) {
            if (purpose.allows(c)) {
                list.add(c);
            }
        }
        if (list.isEmpty()) {
            plugin.messages().send(player, "gui.currency.none");
            return;
        }
        if (list.size() == 1) {
            start(plugin, player, purpose, list.get(0));
            return;
        }
        new CurrencyMenu(plugin, player, purpose, list.subList(0, Math.min(MAX, list.size()))).open(player);
    }

    /** 選好幣種之後的下一步。 */
    static void start(VaultBank plugin, Player player, Purpose purpose, Currency c) {
        String name = plugin.currencyName(c);
        switch (purpose) {
            case WITHDRAW -> new WithdrawMenu(plugin, player, c).open(player);
            case CHEQUE -> plugin.chatInput().ask(player, "input.cheque-amount",
                    (pl, text) -> {
                        if (pl.hasPermission("vaultbank.cheque")) {
                            plugin.actions().cheque(pl, text, c);
                        }
                        new MainMenu(plugin, pl).open(pl);
                    },
                    () -> new MainMenu(plugin, player).open(player), "currency", name);
            case PAY -> plugin.chatInput().ask(player, "input.pay",
                    (pl, text) -> {
                        String[] parts = text.trim().split("\\s+");
                        if (parts.length != 2) {
                            plugin.messages().send(pl, "input.pay-format");
                        } else if (pl.hasPermission("vaultbank.pay")) {
                            plugin.actions().pay(pl, parts[0], parts[1], c);
                        }
                        new MainMenu(plugin, pl).open(pl);
                    },
                    () -> new MainMenu(plugin, player).open(player), "currency", name);
        }
    }

    private int slotOf(int index) {
        if (inventory.getSize() == 27) {
            return 10 + index + (7 - list.size()) / 2;
        }
        return 10 + (index / 7) * 9 + index % 7;
    }

    @Override
    protected void render() {
        fill(0, inventory.getSize());
        Player p = plugin.getServer().getPlayer(viewer);
        for (int i = 0; i < list.size(); i++) {
            Currency c = list.get(i);
            String balance = p == null ? "-" : plugin.format(c, Bank.get(plugin.bank().getOrCreate(p.getUniqueId(), p.getName()), c));
            ItemStack icon;
            if (!c.denominations().isEmpty()) {
                Denomination top = c.denominations().get(0);
                icon = MainMenu.icon(plugin, plugin.appearance().model(top), "gui.currency.entry", this,
                        "currency", plugin.currencyName(c), "balance", balance);
            } else {
                icon = button(Material.SUNFLOWER, "gui.currency.entry", "currency", plugin.currencyName(c), "balance", balance);
            }
            inventory.setItem(slotOf(i), icon);
        }
        inventory.setItem(back, button(Material.ARROW, "gui.common.back"));
    }

    @Override
    protected void onButton(Player player, int slot) {
        if (!allowed(player, purpose.permission)) {
            return;
        }
        if (slot == back) {
            new MainMenu(plugin, player).open(player);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (slotOf(i) == slot) {
                Currency c = plugin.config().currencies().enabled(list.get(i).id());
                if (c != null && purpose.allows(c)) {
                    start(plugin, player, purpose, c);
                }
                return;
            }
        }
    }
}
