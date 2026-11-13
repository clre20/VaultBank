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
import java.util.EnumMap;
import java.util.Map;

/**
 * 實體貨幣與支票的物品。
 * <ul>
 *   <li>底層物品是試煉鑰匙：沒有合成配方、村民不收、只能開「指定同一把鑰匙」的寶庫，所以不會被拿去做別的事。</li>
 *   <li>外觀用 item_model 指到 vaultbank:&lt;id&gt;（材質包）。</li>
 *   <li>同面額的貨幣每一枚完全相同（只有面額＋簽章），可以堆疊，交易插件也視為同一個物品。</li>
 *   <li>支票每張有唯一序號，簽章包含序號與金額。</li>
 * </ul>
 */
public final class CurrencyItems {

    public static final Material BASE = Material.TRIAL_KEY;

    public enum Kind { NONE, MONEY, CHEQUE, FORGED }

    /** 辨識結果。MONEY 時 denomination 有值；CHEQUE 時 serial 與 amount 有值。 */
    public record Identified(Kind kind, Denomination denomination, String serial, long amount) {
        static final Identified NONE = new Identified(Kind.NONE, null, null, 0);
        static final Identified FORGED = new Identified(Kind.FORGED, null, null, 0);

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
    private final NamespacedKey sigKey;
    private final Map<Denomination, String> moneySigs = new EnumMap<>(Denomination.class);
    private final Map<Denomination, ItemStack> prototypes = new EnumMap<>(Denomination.class);

    public CurrencyItems(VaultBank plugin, Signer signer) {
        this.plugin = plugin;
        this.signer = signer;
        this.kindKey = new NamespacedKey(plugin, "kind");
        this.denomKey = new NamespacedKey(plugin, "denom");
        this.serialKey = new NamespacedKey(plugin, "serial");
        this.amountKey = new NamespacedKey(plugin, "amount");
        this.sigKey = new NamespacedKey(plugin, "sig");
        for (Denomination d : Denomination.values()) {
            moneySigs.put(d, signer.sign("money|" + d.id()));
        }
    }

    /** 重新產生原型（messages.yml 重載後名稱與描述會變）。 */
    public void rebuild() {
        prototypes.clear();
        for (Denomination d : Denomination.values()) {
            ItemStack item = new ItemStack(BASE);
            ItemMeta meta = item.getItemMeta();
            meta.itemName(plugin.messages().item("items." + d.id() + ".name", "value", d.value()));
            meta.lore(plugin.messages().itemLore("items." + d.id() + ".lore", "value", d.value()));
            meta.setItemModel(new NamespacedKey("vaultbank", d.id()));
            meta.setEnchantmentGlintOverride(false);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(kindKey, PersistentDataType.STRING, "money");
            pdc.set(denomKey, PersistentDataType.STRING, d.id());
            pdc.set(sigKey, PersistentDataType.STRING, moneySigs.get(d));
            item.setItemMeta(meta);
            prototypes.put(d, item);
        }
    }

    /** 一個貨幣物品（複本）。 */
    public ItemStack money(Denomination d, int amount) {
        ItemStack item = prototypes.get(d).clone();
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return item;
    }

    public int maxStack(Denomination d) {
        return prototypes.get(d).getMaxStackSize();
    }

    public ItemStack cheque(String serial, long amount, String issuerName, long issuedAt) {
        ItemStack item = new ItemStack(BASE);
        ItemMeta meta = item.getItemMeta();
        String date;
        try {
            date = new SimpleDateFormat(plugin.messages().raw("items.cheque.date-format")).format(new Date(issuedAt));
        } catch (IllegalArgumentException e) {
            date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(issuedAt));
        }
        String amountText = Money.format(BigDecimal.valueOf(amount), 0);
        meta.itemName(plugin.messages().item("items.cheque.name", "amount", amountText));
        meta.lore(plugin.messages().itemLore("items.cheque.lore",
                "amount", amountText, "issuer", issuerName, "date", date, "serial", serial));
        meta.setItemModel(new NamespacedKey("vaultbank", "cheque"));
        meta.setEnchantmentGlintOverride(false);
        meta.setMaxStackSize(1);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(kindKey, PersistentDataType.STRING, "cheque");
        pdc.set(serialKey, PersistentDataType.STRING, serial);
        pdc.set(amountKey, PersistentDataType.LONG, amount);
        pdc.set(sigKey, PersistentDataType.STRING, signer.sign("cheque|" + serial + "|" + amount));
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
        if (kind.equals("money")) {
            Denomination d = Denomination.byId(pdc.getOrDefault(denomKey, PersistentDataType.STRING, ""));
            if (d == null || sig == null || !java.security.MessageDigest.isEqual(
                    moneySigs.get(d).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    sig.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return Identified.FORGED;
            }
            return new Identified(Kind.MONEY, d, null, 0);
        }
        if (kind.equals("cheque")) {
            String serial = pdc.get(serialKey, PersistentDataType.STRING);
            Long amount = pdc.get(amountKey, PersistentDataType.LONG);
            if (serial == null || amount == null || amount <= 0 || serial.length() > 32
                    || !signer.verify("cheque|" + serial + "|" + amount, sig)) {
                return Identified.FORGED;
            }
            return new Identified(Kind.CHEQUE, null, serial, amount);
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
