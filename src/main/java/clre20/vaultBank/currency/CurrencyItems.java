package clre20.vaultBank.currency;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.util.Money;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 實體貨幣與支票的物品。
 * <ul>
 *   <li>底層物品是試煉鑰匙：沒有合成配方、村民不收、只能開「指定同一把鑰匙」的寶庫，所以不會被拿去做別的事。</li>
 *   <li>外觀用 item_model 指到 vaultbank:&lt;id&gt;（材質包）；沒有提供模型或圖片的面額用原版物品外觀（minecraft:&lt;物品&gt;）。</li>
 *   <li>同面額的貨幣每一枚完全相同（只有面額＋簽章），可以堆疊，交易插件也視為同一個物品。</li>
 *   <li>支票每張有唯一序號，簽章包含序號與金額。</li>
 * </ul>
 */
public final class CurrencyItems {

    public static final Material BASE = Material.TRIAL_KEY;

    /** UNKNOWN：簽章正確，但面額或幣種已不在設定裡。 */
    public enum Kind { NONE, MONEY, CHEQUE, FORGED, UNKNOWN }

    /** 辨識結果。MONEY 時 denomination 有值；CHEQUE 時 serial、amount、currency 有值。 */
    public record Identified(Kind kind, Denomination denomination, String serial, long amount, Currency currency) {
        static final Identified NONE = new Identified(Kind.NONE, null, null, 0, null);
        static final Identified FORGED = new Identified(Kind.FORGED, null, null, 0, null);
        static final Identified UNKNOWN = new Identified(Kind.UNKNOWN, null, null, 0, null);

        public boolean ours() {
            return kind != Kind.NONE;
        }
    }

    private final VaultBank plugin;
    private final Signer signer;
    private final NamespacedKey kindKey;
    private final NamespacedKey denomKey;
    private final NamespacedKey serialKey;
    private final NamespacedKey amountKey;
    private final NamespacedKey currencyKey;
    private final NamespacedKey sigKey;
    private final Map<String, String> moneySigs = new ConcurrentHashMap<>();
    private volatile Map<String, ItemStack> prototypes = Map.of();

    public CurrencyItems(VaultBank plugin, Signer signer) {
        this.plugin = plugin;
        this.signer = signer;
        this.kindKey = new NamespacedKey(plugin, "kind");
        this.denomKey = new NamespacedKey(plugin, "denom");
        this.serialKey = new NamespacedKey(plugin, "serial");
        this.amountKey = new NamespacedKey(plugin, "amount");
        this.currencyKey = new NamespacedKey(plugin, "currency");
        this.sigKey = new NamespacedKey(plugin, "sig");
    }

    private String moneySig(String denomId) {
        return moneySigs.computeIfAbsent(denomId, id -> signer.sign("money|" + id));
    }

    /** 支票簽章：主貨幣沿用 1.0.x 的格式（舊支票繼續有效），其他幣種把幣種也簽進去。 */
    private String chequeData(String serial, long amount, String currency) {
        return Currency.PRIMARY.equals(currency)
                ? "cheque|" + serial + "|" + amount
                : "cheque|" + serial + "|" + amount + "|" + currency;
    }

    /** 重新產生原型（config／messages.yml 重載後名稱、描述、外觀會變）。 */
    public void rebuild() {
        Map<String, ItemStack> out = new HashMap<>();
        for (Denomination d : plugin.config().currencies().denominations()) {
            ItemStack item = new ItemStack(BASE);
            ItemMeta meta = item.getItemMeta();
            String key = plugin.messages().has("items." + d.id() + ".name") ? "items." + d.id() : "items.generic-money";
            meta.itemName(plugin.messages().item(key + ".name", "value", d.value(),
                    "currency", plugin.currencyName(d.currency())));
            meta.lore(plugin.messages().itemLore(key + ".lore", "value", d.value(),
                    "currency", plugin.currencyName(d.currency())));
            meta.setItemModel(plugin.appearance().model(d));
            meta.setEnchantmentGlintOverride(false);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(kindKey, PersistentDataType.STRING, "money");
            pdc.set(denomKey, PersistentDataType.STRING, d.id());
            pdc.set(sigKey, PersistentDataType.STRING, moneySig(d.id()));
            item.setItemMeta(meta);
            out.put(d.id(), item);
        }
        prototypes = Map.copyOf(out);
    }

    /** 一個貨幣物品（複本）。 */
    public ItemStack money(Denomination d, int amount) {
        ItemStack item = prototypes.get(d.id()).clone();
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return item;
    }

    public int maxStack(Denomination d) {
        return prototypes.get(d.id()).getMaxStackSize();
    }

    public ItemStack cheque(String serial, long amount, String issuerName, long issuedAt, Currency currency) {
        ItemStack item = new ItemStack(BASE);
        ItemMeta meta = item.getItemMeta();
        String date;
        try {
            date = new SimpleDateFormat(plugin.messages().raw("items.cheque.date-format")).format(new Date(issuedAt));
        } catch (IllegalArgumentException e) {
            date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(issuedAt));
        }
        String amountText = Money.format(BigDecimal.valueOf(amount), 0);
        String money = plugin.format(currency, BigDecimal.valueOf(amount));
        meta.itemName(plugin.messages().item("items.cheque.name", "amount", amountText, "money", money,
                "currency", plugin.currencyName(currency)));
        meta.lore(plugin.messages().itemLore("items.cheque.lore",
                "amount", amountText, "money", money, "currency", plugin.currencyName(currency),
                "issuer", issuerName, "date", date, "serial", serial));
        meta.setItemModel(new NamespacedKey("vaultbank", "cheque"));
        meta.setEnchantmentGlintOverride(false);
        meta.setMaxStackSize(1);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(kindKey, PersistentDataType.STRING, "cheque");
        pdc.set(serialKey, PersistentDataType.STRING, serial);
        pdc.set(amountKey, PersistentDataType.LONG, amount);
        if (!currency.primary()) {
            pdc.set(currencyKey, PersistentDataType.STRING, currency.id());
        }
        pdc.set(sigKey, PersistentDataType.STRING, signer.sign(chequeData(serial, amount, currency.id())));
        item.setItemMeta(meta);
        return item;
    }

    /** 辨識物品；先用材質快速過濾，一般物品幾乎不花時間。 */
    public Identified identify(ItemStack item) {
        if (item == null || item.getType() != BASE || !item.hasItemMeta()) {
            return Identified.NONE;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String kind = pdc.get(kindKey, PersistentDataType.STRING);
        if (kind == null) {
            return Identified.NONE;
        }
        String sig = pdc.get(sigKey, PersistentDataType.STRING);
        if (sig == null || sig.length() > 64) {
            return Identified.FORGED;
        }
        if (kind.equals("money")) {
            String denomId = pdc.getOrDefault(denomKey, PersistentDataType.STRING, "");
            if (denomId.isEmpty() || denomId.length() > 32 || !MessageDigest.isEqual(
                    moneySig(denomId).getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII))) {
                return Identified.FORGED;
            }
            Denomination d = plugin.config().currencies().denomination(denomId);
            if (d == null) {
                return Identified.UNKNOWN;
            }
            return new Identified(Kind.MONEY, d, null, 0, d.currency());
        }
        if (kind.equals("cheque")) {
            String serial = pdc.get(serialKey, PersistentDataType.STRING);
            Long amount = pdc.get(amountKey, PersistentDataType.LONG);
            String cur = pdc.getOrDefault(currencyKey, PersistentDataType.STRING, Currency.PRIMARY);
            if (serial == null || amount == null || amount <= 0 || serial.length() > 32 || cur.length() > 16
                    || !signer.verify(chequeData(serial, amount, cur), sig)) {
                return Identified.FORGED;
            }
            Currency c = plugin.config().currencies().get(cur);
            if (c == null) {
                return Identified.UNKNOWN;
            }
            return new Identified(Kind.CHEQUE, null, serial, amount, c);
        }
        return Identified.FORGED;
    }

    /** 是否是本插件的物品（不驗簽章，給介面快速判斷能不能放進存入區）。 */
    public boolean isOurs(ItemStack item) {
        if (item == null || item.getType() != BASE || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(kindKey, PersistentDataType.STRING);
    }
}
