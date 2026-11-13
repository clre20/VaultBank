package clre20.vaultBank.currency;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.economy.TxType;
import clre20.vaultBank.util.Money;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Teller：實體貨幣與支票的存入、提取、開票。只在主執行緒呼叫。
 */
public final class Teller {

    public enum WithdrawResult { OK, EMPTY, DISABLED, TOO_MANY, NO_SPACE, INSUFFICIENT, FAILED }

    public enum ChequeResult { OK, BELOW_MIN, ABOVE_MAX, NO_SPACE, INSUFFICIENT, FAILED }

    /**
     * 存入結果。
     *
     * @param credited  入帳金額
     * @param accepted  收下的物品數量（枚／張）
     * @param returned  退回的物品（偽造、已兌現的支票等；沒收時為空）
     * @param forged    偽造物品數量
     * @param usedCheques 已兌現過或無效的支票張數
     * @param overMax   入帳後會超過餘額上限，全部退回
     */
    public record DepositResult(BigDecimal credited, int accepted, List<ItemStack> returned,
                                int forged, int usedCheques, boolean overMax) {
    }

    private final VaultBank plugin;

    public Teller(VaultBank plugin) {
        this.plugin = plugin;
    }

    // ---------- 存入 ----------

    /**
     * 把物品存入玩家帳戶。傳入的物品視為已從玩家身上拿走；退回的物品放在結果裡由呼叫端還給玩家。
     */
    public DepositResult deposit(Player player, List<ItemStack> items) {
        CurrencyItems ci = plugin.items();
        Cheques cheques = plugin.cheques();
        Map<Denomination, Long> money = new EnumMap<>(Denomination.class);
        List<ItemStack> chequeItems = new ArrayList<>();
        List<CurrencyItems.Identified> chequeIds = new ArrayList<>();
        List<ItemStack> returned = new ArrayList<>();
        List<ItemStack> confiscated = new ArrayList<>();
        int forged = 0;
        int used = 0;
        BigDecimal total = BigDecimal.ZERO;

        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            CurrencyItems.Identified id = ci.identify(item);
            switch (id.kind()) {
                case MONEY -> {
                    money.merge(id.denomination(), (long) item.getAmount(), Long::sum);
                    total = total.add(BigDecimal.valueOf((long) id.denomination().value() * item.getAmount()));
                }
                case CHEQUE -> {
                    if (cheques.isOutstanding(id.serial(), id.amount()) && item.getAmount() == 1) {
                        chequeItems.add(item);
                        chequeIds.add(id);
                        total = total.add(BigDecimal.valueOf(id.amount()));
                    } else {
                        used += item.getAmount();
                        (plugin.config().confiscateInvalid() ? confiscated : returned).add(item);
                        plugin.alerts().usedCheque(player, id.serial());
                    }
                }
                case FORGED -> {
                    forged += item.getAmount();
                    (plugin.config().confiscateInvalid() ? confiscated : returned).add(item);
                    plugin.alerts().forged(player, item.getAmount());
                }
                default -> returned.add(item);
            }
        }

        if (total.signum() <= 0) {
            return new DepositResult(BigDecimal.ZERO, 0, returned, forged, used, false);
        }
        Bank bank = plugin.bank();
        bank.getOrCreate(player.getUniqueId(), player.getName());
        BigDecimal current = bank.balance(player.getUniqueId());
        if (current.add(total).compareTo(plugin.config().maxBalance()) > 0) {
            java.util.Set<ItemStack> taken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            taken.addAll(confiscated);
            List<ItemStack> all = new ArrayList<>();
            for (ItemStack item : items) {
                if (item != null && !item.getType().isAir() && !taken.contains(item)) {
                    all.add(item);
                }
            }
            return new DepositResult(BigDecimal.ZERO, 0, all, forged, used, true);
        }

        int accepted = 0;
        BigDecimal moneyTotal = BigDecimal.ZERO;
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<Denomination, Long> e : money.entrySet()) {
            Denomination d = e.getKey();
            long count = e.getValue();
            accepted += (int) count;
            moneyTotal = moneyTotal.add(BigDecimal.valueOf(d.value() * count));
            if (!detail.isEmpty()) {
                detail.append(',');
            }
            detail.append(d.value()).append('x').append(count);
            if (plugin.circulation().redeemed(d, count) && plugin.circulation().shouldAlert(d)) {
                plugin.alerts().overRedeemed(d, plugin.circulation().minted(d), plugin.circulation().redeemed(d));
            }
        }
        if (moneyTotal.signum() > 0) {
            bank.deposit(player.getUniqueId(), moneyTotal, TxType.DEPOSIT_ITEM, null, null, detail.toString());
        }
        BigDecimal credited = moneyTotal;
        for (int i = 0; i < chequeIds.size(); i++) {
            CurrencyItems.Identified id = chequeIds.get(i);
            if (cheques.redeem(id.serial(), id.amount(), player.getUniqueId())) {
                bank.deposit(player.getUniqueId(), BigDecimal.valueOf(id.amount()), TxType.CHEQUE_REDEEM,
                        null, null, id.serial());
                credited = credited.add(BigDecimal.valueOf(id.amount()));
                accepted++;
            } else {
                used++;
                returned.add(chequeItems.get(i));
            }
        }
        return new DepositResult(credited, accepted, returned, forged, used, false);
    }

    // ---------- 提取 ----------

    /** 用最大面額優先拆成實體貨幣（只用啟用的面額）；拆不完回傳 null。 */
    public Map<Denomination, Integer> plan(long amount) {
        Map<Denomination, Integer> out = new EnumMap<>(Denomination.class);
        long left = amount;
        for (Denomination d : Denomination.descending()) {
            if (!plugin.config().denominationEnabled(d) || left < d.value()) {
                continue;
            }
            long n = left / d.value();
            if (n > Integer.MAX_VALUE) {
                return null;
            }
            out.put(d, (int) n);
            left -= n * d.value();
        }
        return left == 0 ? out : null;
    }

    public static long value(Map<Denomination, Integer> selection) {
        long sum = 0;
        for (Map.Entry<Denomination, Integer> e : selection.entrySet()) {
            sum += (long) e.getKey().value() * e.getValue();
        }
        return sum;
    }

    public static long count(Map<Denomination, Integer> selection) {
        long sum = 0;
        for (int v : selection.values()) {
            sum += v;
        }
        return sum;
    }

    /** 背包放不放得下（只算主背包 36 格，不碰裝備與副手）。 */
    public boolean fits(Player player, Map<Denomination, Integer> selection) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) {
            copy[i] = storage[i] == null ? null : storage[i].clone();
        }
        for (Map.Entry<Denomination, Integer> e : selection.entrySet()) {
            int left = e.getValue();
            if (left <= 0) {
                continue;
            }
            ItemStack proto = plugin.items().money(e.getKey(), 1);
            int max = proto.getMaxStackSize();
            for (int i = 0; i < copy.length && left > 0; i++) {
                ItemStack s = copy[i];
                if (s != null && !s.getType().isAir() && s.isSimilar(proto) && s.getAmount() < max) {
                    int add = Math.min(left, max - s.getAmount());
                    s.setAmount(s.getAmount() + add);
                    left -= add;
                }
            }
            for (int i = 0; i < copy.length && left > 0; i++) {
                if (copy[i] == null || copy[i].getType().isAir()) {
                    int add = Math.min(left, max);
                    copy[i] = plugin.items().money(e.getKey(), add);
                    left -= add;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }

    public WithdrawResult withdraw(Player player, Map<Denomination, Integer> selection) {
        long items = count(selection);
        if (items <= 0) {
            return WithdrawResult.EMPTY;
        }
        if (items > plugin.config().withdrawMaxItems()) {
            return WithdrawResult.TOO_MANY;
        }
        for (Map.Entry<Denomination, Integer> e : selection.entrySet()) {
            if (e.getValue() < 0) {
                return WithdrawResult.FAILED;
            }
            if (e.getValue() > 0 && !plugin.config().denominationEnabled(e.getKey())) {
                return WithdrawResult.DISABLED;
            }
        }
        if (!fits(player, selection)) {
            return WithdrawResult.NO_SPACE;
        }
        long total = value(selection);
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<Denomination, Integer> e : selection.entrySet()) {
            if (e.getValue() > 0) {
                if (!detail.isEmpty()) {
                    detail.append(',');
                }
                detail.append(e.getKey().value()).append('x').append(e.getValue());
            }
        }
        Bank bank = plugin.bank();
        bank.getOrCreate(player.getUniqueId(), player.getName());
        Bank.Result r = bank.withdraw(player.getUniqueId(), BigDecimal.valueOf(total), TxType.WITHDRAW_ITEM,
                null, null, detail.toString());
        if (r == Bank.Result.INSUFFICIENT) {
            return WithdrawResult.INSUFFICIENT;
        }
        if (r != Bank.Result.OK) {
            return WithdrawResult.FAILED;
        }
        PlayerInventory inv = player.getInventory();
        long refund = 0;
        for (Map.Entry<Denomination, Integer> e : selection.entrySet()) {
            Denomination d = e.getKey();
            int left = e.getValue();
            int max = plugin.items().maxStack(d);
            int given = 0;
            while (left > 0) {
                int n = Math.min(left, max);
                Map<Integer, ItemStack> rest = inv.addItem(plugin.items().money(d, n));
                int notAdded = 0;
                for (ItemStack s : rest.values()) {
                    notAdded += s.getAmount();
                }
                given += n - notAdded;
                left -= n;
                if (notAdded > 0) {
                    refund += (long) notAdded * d.value() + (long) left * d.value();
                    left = 0;
                }
            }
            if (given > 0) {
                plugin.circulation().minted(d, given);
            }
        }
        if (refund > 0) {
            // 理論上不會發生（前面已檢查空間），保險起見把沒放進去的部分退回帳戶
            bank.deposit(player.getUniqueId(), BigDecimal.valueOf(refund), TxType.DEPOSIT_ITEM, null, null, "refund");
        }
        return WithdrawResult.OK;
    }

    // ---------- 支票 ----------

    public BigDecimal chequeFee(long amount) {
        BigDecimal pct = plugin.config().chequeFeePercent();
        if (pct.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(amount).multiply(pct)
                .divide(BigDecimal.valueOf(100), plugin.config().decimalPlaces(), RoundingMode.HALF_UP);
    }

    public ChequeResult issueCheque(Player player, long amount) {
        if (amount < plugin.config().chequeMinAmount()) {
            return ChequeResult.BELOW_MIN;
        }
        long max = plugin.config().chequeMaxAmount();
        if (max > 0 && amount > max) {
            return ChequeResult.ABOVE_MAX;
        }
        if (player.getInventory().firstEmpty() < 0) {
            return ChequeResult.NO_SPACE;
        }
        BigDecimal fee = chequeFee(amount);
        BigDecimal cost = BigDecimal.valueOf(amount).add(fee);
        Bank bank = plugin.bank();
        bank.getOrCreate(player.getUniqueId(), player.getName());
        String serial = plugin.cheques().reserve(amount);
        String detail = fee.signum() > 0 ? serial + " fee=" + Money.plain(fee) : serial;
        Bank.Result r = bank.withdraw(player.getUniqueId(), cost, TxType.CHEQUE_ISSUE, null, null, detail);
        if (r != Bank.Result.OK) {
            plugin.cheques().cancel(serial);
            return r == Bank.Result.INSUFFICIENT ? ChequeResult.INSUFFICIENT : ChequeResult.FAILED;
        }
        long now = System.currentTimeMillis();
        plugin.cheques().confirm(serial, amount, player.getUniqueId(), player.getName(), now);
        ItemStack cheque = plugin.items().cheque(serial, amount, player.getName(), now);
        Map<Integer, ItemStack> rest = player.getInventory().addItem(cheque);
        for (ItemStack s : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), s);
        }
        return ChequeResult.OK;
    }

    // ---------- 管理員發放 ----------

    /** 直接發放實體貨幣（不扣款），放不下的掉在腳下。 */
    public void mint(Player player, Denomination d, int count) {
        int left = count;
        int max = plugin.items().maxStack(d);
        while (left > 0) {
            int n = Math.min(left, max);
            for (ItemStack s : player.getInventory().addItem(plugin.items().money(d, n)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), s);
            }
            left -= n;
        }
        plugin.circulation().minted(d, count);
    }

    /** 把物品還給玩家，放不下的掉在腳下。 */
    public static void giveBack(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> rest = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack s : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), s);
        }
    }
}
