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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teller：實體貨幣與支票的存入、提取、開票。只在主執行緒呼叫。
 */
public final class Teller {

    public enum WithdrawResult { OK, EMPTY, DISABLED, CURRENCY_DISABLED, TOO_MANY, NO_SPACE, INSUFFICIENT, FAILED }

    public enum ChequeResult { OK, DISABLED, BELOW_MIN, ABOVE_MAX, NO_SPACE, INSUFFICIENT, FAILED }

    /**
     * 存入結果。
     *
     * @param credited    各幣種入帳金額（有入帳的才有）
     * @param accepted    收下的物品數量（枚／張）
     * @param returned    退回的物品（偽造、已兌現的支票、停用幣種等；沒收時為空）
     * @param forged      偽造物品數量
     * @param usedCheques 已兌現過或無效的支票張數
     * @param overMax     入帳後會超過餘額上限而整批退回的幣種
     * @param disabled    停用幣種或已移除面額的物品數量（退回）
     */
    public record DepositResult(Map<Currency, BigDecimal> credited, Map<Currency, Integer> counts, int accepted, List<ItemStack> returned,
                                int forged, int usedCheques, List<Currency> overMax, int disabled) {

        public boolean any() {
            return !credited.isEmpty();
        }
    }

    /** 同一幣種的存入物品。 */
    private static final class Group {
        final Map<Denomination, Long> money = new LinkedHashMap<>();
        final List<ItemStack> items = new ArrayList<>();
        final List<ItemStack> chequeItems = new ArrayList<>();
        final List<CurrencyItems.Identified> chequeIds = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
    }

    private final VaultBank plugin;

    public Teller(VaultBank plugin) {
        this.plugin = plugin;
    }

    // ---------- 存入 ----------

    /**
     * 把物品存入玩家帳戶（各自存到對應幣種）。傳入的物品視為已從玩家身上拿走；退回的物品放在結果裡由呼叫端還給玩家。
     */
    public DepositResult deposit(Player player, List<ItemStack> items) {
        CurrencyItems ci = plugin.items();
        Cheques cheques = plugin.cheques();
        Map<Currency, Group> groups = new LinkedHashMap<>();
        List<ItemStack> returned = new ArrayList<>();
        boolean confiscate = plugin.config().confiscateInvalid();
        int forged = 0;
        int used = 0;
        int disabled = 0;

        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            CurrencyItems.Identified id = ci.identify(item);
            switch (id.kind()) {
                case MONEY -> {
                    if (!id.currency().enabled()) {
                        disabled += item.getAmount();
                        returned.add(item);
                        continue;
                    }
                    Group g = groups.computeIfAbsent(id.currency(), k -> new Group());
                    g.money.merge(id.denomination(), (long) item.getAmount(), Long::sum);
                    g.items.add(item);
                    g.total = g.total.add(BigDecimal.valueOf(id.denomination().value() * item.getAmount()));
                }
                case CHEQUE -> {
                    if (!id.currency().enabled()) {
                        disabled += item.getAmount();
                        returned.add(item);
                    } else if (cheques.isOutstanding(id.serial(), id.amount()) && item.getAmount() == 1) {
                        Group g = groups.computeIfAbsent(id.currency(), k -> new Group());
                        g.chequeItems.add(item);
                        g.chequeIds.add(id);
                        g.total = g.total.add(BigDecimal.valueOf(id.amount()));
                    } else {
                        used += item.getAmount();
                        if (!confiscate) {
                            returned.add(item);
                        }
                        plugin.alerts().usedCheque(player, id.serial());
                    }
                }
                case FORGED -> {
                    forged += item.getAmount();
                    if (!confiscate) {
                        returned.add(item);
                    }
                    plugin.alerts().forged(player, item.getAmount());
                }
                case UNKNOWN -> {
                    disabled += item.getAmount();
                    returned.add(item);
                }
                default -> returned.add(item);
            }
        }

        Map<Currency, BigDecimal> credited = new LinkedHashMap<>();
        Map<Currency, Integer> counts = new LinkedHashMap<>();
        List<Currency> overMax = new ArrayList<>();
        int accepted = 0;
        if (groups.isEmpty()) {
            return new DepositResult(credited, Map.of(), 0, returned, forged, used, overMax, disabled);
        }
        Bank bank = plugin.bank();
        bank.getOrCreate(player.getUniqueId(), player.getName());
        // 依 config 的幣種順序（元在最前面）
        List<Map.Entry<Currency, Group>> ordered = new ArrayList<>(groups.entrySet());
        List<Currency> order = new ArrayList<>(plugin.config().currencies().all());
        ordered.sort(java.util.Comparator.comparingInt(e -> order.indexOf(e.getKey())));
        for (Map.Entry<Currency, Group> ge : ordered) {
            Currency cur = ge.getKey();
            Group g = ge.getValue();
            if (bank.balance(player.getUniqueId(), cur).add(g.total).compareTo(cur.maxBalance()) > 0) {
                overMax.add(cur);
                returned.addAll(g.items);
                returned.addAll(g.chequeItems);
                continue;
            }
            int before = accepted;
            BigDecimal moneyTotal = BigDecimal.ZERO;
            StringBuilder detail = new StringBuilder();
            List<Map.Entry<Denomination, Long>> sorted = new ArrayList<>(g.money.entrySet());
            sorted.sort((x, y) -> Long.compare(y.getKey().value(), x.getKey().value()));
            for (Map.Entry<Denomination, Long> e : sorted) {
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
            BigDecimal got = BigDecimal.ZERO;
            if (moneyTotal.signum() > 0) {
                Bank.Result r = bank.deposit(player.getUniqueId(), cur, moneyTotal, TxType.DEPOSIT_ITEM, null, null, detail.toString());
                if (r == Bank.Result.OK) {
                    got = moneyTotal;
                } else {
                    // 不會發生（前面已檢查幣種與上限），保險起見把貨幣退回並撤銷回收數
                    returned.addAll(g.items);
                    for (Map.Entry<Denomination, Long> e : sorted) {
                        plugin.circulation().redeemed(e.getKey(), -e.getValue());
                    }
                    accepted -= (int) sum(g.money);
                }
            }
            for (int i = 0; i < g.chequeIds.size(); i++) {
                CurrencyItems.Identified id = g.chequeIds.get(i);
                if (cheques.redeem(id.serial(), id.amount(), player.getUniqueId())) {
                    bank.deposit(player.getUniqueId(), cur, BigDecimal.valueOf(id.amount()), TxType.CHEQUE_REDEEM,
                            null, null, id.serial());
                    got = got.add(BigDecimal.valueOf(id.amount()));
                    accepted++;
                } else {
                    used++;
                    returned.add(g.chequeItems.get(i));
                }
            }
            if (got.signum() > 0) {
                credited.put(cur, got);
                counts.put(cur, accepted - before);
            }
        }
        return new DepositResult(credited, counts, accepted, returned, forged, used, overMax, disabled);
    }

    private static long sum(Map<Denomination, Long> m) {
        long s = 0;
        for (long v : m.values()) {
            s += v;
        }
        return s;
    }

    // ---------- 提取 ----------

    /** 用最大面額優先拆成實體貨幣（只用啟用的面額）；拆不完回傳 null。 */
    public Map<Denomination, Integer> plan(Currency cur, long amount) {
        Map<Denomination, Integer> out = new LinkedHashMap<>();
        long left = amount;
        for (Denomination d : cur.denominations()) { // 由大到小
            if (!d.enabled() || left < d.value()) {
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
            sum += e.getKey().value() * e.getValue();
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

    /** 選擇裡的幣種；空的或混了不同幣種回傳 null。 */
    public static Currency currencyOf(Map<Denomination, Integer> selection) {
        Currency c = null;
        for (Map.Entry<Denomination, Integer> e : selection.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            if (c == null) {
                c = e.getKey().currency();
            } else if (c != e.getKey().currency()) {
                return null;
            }
        }
        return c;
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

    /** 提取（selection 只能是同一個幣種）。 */
    public WithdrawResult withdraw(Player player, Map<Denomination, Integer> selection) {
        long items = count(selection);
        if (items <= 0) {
            return WithdrawResult.EMPTY;
        }
        Currency cur = currencyOf(selection);
        if (cur == null) {
            return WithdrawResult.FAILED;
        }
        if (!cur.enabled() || !cur.features().physical()) {
            return WithdrawResult.CURRENCY_DISABLED;
        }
        if (items > plugin.config().withdrawMaxItems()) {
            return WithdrawResult.TOO_MANY;
        }
        for (Map.Entry<Denomination, Integer> e : selection.entrySet()) {
            if (e.getValue() < 0) {
                return WithdrawResult.FAILED;
            }
            if (e.getValue() > 0 && !e.getKey().enabled()) {
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
        Bank.Result r = bank.withdraw(player.getUniqueId(), cur, BigDecimal.valueOf(total), TxType.WITHDRAW_ITEM,
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
                    refund += notAdded * d.value() + left * d.value();
                    left = 0;
                }
            }
            if (given > 0) {
                plugin.circulation().minted(d, given);
            }
        }
        if (refund > 0) {
            // 理論上不會發生（前面已檢查空間），保險起見把沒放進去的部分退回帳戶
            bank.deposit(player.getUniqueId(), cur, BigDecimal.valueOf(refund), TxType.DEPOSIT_ITEM, null, null, "refund");
        }
        return WithdrawResult.OK;
    }

    // ---------- 支票 ----------

    public BigDecimal chequeFee(Currency cur, long amount) {
        BigDecimal pct = cur.chequeFeePercent();
        if (pct.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(amount).multiply(pct)
                .divide(BigDecimal.valueOf(100), cur.scale(), RoundingMode.HALF_UP);
    }

    public ChequeResult issueCheque(Player player, Currency cur, long amount) {
        if (!cur.enabled() || !cur.features().cheque()) {
            return ChequeResult.DISABLED;
        }
        if (amount < cur.chequeMin()) {
            return ChequeResult.BELOW_MIN;
        }
        long max = cur.chequeMax();
        if (max > 0 && amount > max) {
            return ChequeResult.ABOVE_MAX;
        }
        if (player.getInventory().firstEmpty() < 0) {
            return ChequeResult.NO_SPACE;
        }
        BigDecimal fee = chequeFee(cur, amount);
        BigDecimal cost = BigDecimal.valueOf(amount).add(fee);
        Bank bank = plugin.bank();
        bank.getOrCreate(player.getUniqueId(), player.getName());
        String serial = plugin.cheques().reserve(amount);
        String detail = fee.signum() > 0 ? serial + " fee=" + Money.plain(fee) : serial;
        Bank.Result r = bank.withdraw(player.getUniqueId(), cur, cost, TxType.CHEQUE_ISSUE, null, null, detail);
        if (r != Bank.Result.OK) {
            plugin.cheques().cancel(serial);
            return r == Bank.Result.INSUFFICIENT ? ChequeResult.INSUFFICIENT : ChequeResult.FAILED;
        }
        long now = System.currentTimeMillis();
        plugin.cheques().confirm(serial, amount, player.getUniqueId(), player.getName(), now, cur.id());
        ItemStack cheque = plugin.items().cheque(serial, amount, player.getName(), now, cur);
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
