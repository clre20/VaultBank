package clre20.vaultBank.pack;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.Currencies;
import clre20.vaultBank.currency.Denomination;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.NamespacedKey;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 每個面額（與支票）的外觀，依序找：
 * <ol>
 *   <li>plugins/VaultBank/textures/&lt;id&gt;.json：模型（例如 Blockbench 匯出的 Java 版模型），模型用到的貼圖也放在 textures/</li>
 *   <li>plugins/VaultBank/textures/&lt;id&gt;.png：圖片，自動加上厚度</li>
 *   <li>插件內建的模型或圖片（元的 7 種面額、支票、企鵝幣）</li>
 *   <li>都沒有（或 config 設 appearance: vanilla）：原版物品外觀（config 的 material）</li>
 * </ol>
 * 在 onEnable 與 /vb reload 時於主執行緒掃描（檔案很少很小）；材質包在背景依結果產生。
 */
public final class Appearance {

    public enum Type { MODEL, IMAGE, VANILLA }

    /**
     * @param model    MODEL 時的模型（貼圖參照已改成 vaultbank:item/&lt;名稱&gt;）
     * @param textures MODEL 時模型用到的貼圖（名稱 → PNG）；IMAGE 時只有一張（id → PNG）
     * @param icon     基岩版圖示（PNG）；VANILLA 時為 null
     */
    public record Look(Type type, JsonObject model, Map<String, byte[]> textures, byte[] icon, String material) {
    }

    private static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,48}");
    private static final long MAX_MODEL = 512 * 1024;
    private static final long MAX_PNG = 256 * 1024;

    private final VaultBank plugin;
    private volatile Map<String, Look> looks = Map.of();

    public Appearance(VaultBank plugin) {
        this.plugin = plugin;
    }

    /** 內建外觀的檔名（第一次啟動時複製到 textures/ 當範例，之後不會覆蓋使用者的檔案）。 */
    private static final String[] BUILTIN = {
            "coin_1.png", "coin_5.png", "coin_10.png", "coin_50.png", "note_100.png", "note_500.png", "note_1000.png",
            "cheque.png", "penguin_1.png", "penguin_1.json",
    };

    public void reload() {
        File dir = new File(plugin.getDataFolder(), "textures");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("無法建立 " + dir);
        }
        for (String name : BUILTIN) {
            File f = new File(dir, name);
            if (!f.exists()) {
                byte[] b = builtin(name);
                if (b != null) {
                    try {
                        Files.write(f.toPath(), b);
                    } catch (IOException e) {
                        plugin.getLogger().warning("無法寫入 " + f + "：" + e.getMessage());
                    }
                }
            }
        }
        Map<String, Look> out = new LinkedHashMap<>();
        Currencies all = plugin.config().currencies();
        for (Denomination d : all.denominations()) {
            out.put(d.id(), resolve(dir, d.id(), d.forceVanilla(), d.material()));
        }
        Look cheque = resolve(dir, "cheque", false, "paper");
        out.put("cheque", cheque);
        looks = out;
    }

    public Map<String, Look> looks() {
        return looks;
    }

    /** 物品的 item_model。 */
    public NamespacedKey model(Denomination d) {
        Look l = looks.get(d.id());
        if (l == null || l.type() == Type.VANILLA) {
            return NamespacedKey.minecraft(l == null ? d.material() : l.material());
        }
        return new NamespacedKey("vaultbank", d.id());
    }

    // ---------- 尋找 ----------

    private Look resolve(File dir, String id, boolean forceVanilla, String material) {
        if (forceVanilla) {
            return new Look(Type.VANILLA, null, Map.of(), null, material);
        }
        // 1. 使用者的模型
        Look l = model(dir, id, read(new File(dir, id + ".json"), MAX_MODEL), false);
        if (l != null) {
            return l;
        }
        // 2. 使用者的圖片
        byte[] png = read(new File(dir, id + ".png"), MAX_PNG);
        if (png != null) {
            if (squareTexture(png)) {
                return new Look(Type.IMAGE, null, Map.of(id, png), png, material);
            }
            plugin.getLogger().warning(plugin.messages().console("texture-invalid", "file", id + ".png"));
        }
        // 3. 內建
        l = model(dir, id, builtin("textures/" + id + ".json"), true);
        if (l != null) {
            return l;
        }
        byte[] b = builtin("textures/" + id + ".png");
        if (b != null) {
            return new Look(Type.IMAGE, null, Map.of(id, b), b, material);
        }
        // 4. 原版
        return new Look(Type.VANILLA, null, Map.of(), null, material);
    }

    /** 讀模型；把貼圖參照改成 vaultbank:item/&lt;名稱&gt; 並一起載入貼圖。不合格時回傳 null（並警告）。 */
    private Look model(File dir, String id, byte[] json, boolean builtin) {
        if (json == null) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("elements")
                    || !root.getAsJsonObject().get("elements").isJsonArray()
                    || root.getAsJsonObject().getAsJsonArray("elements").size() > 4096) {
                throw new IllegalArgumentException("缺少 elements 或方塊太多");
            }
            JsonObject model = root.getAsJsonObject().deepCopy();
            model.remove("parent");
            Map<String, byte[]> textures = new LinkedHashMap<>();
            JsonObject refs = model.has("textures") && model.get("textures").isJsonObject()
                    ? model.getAsJsonObject("textures") : new JsonObject();
            List<String> keys = new ArrayList<>(refs.keySet());
            for (String key : keys) {
                if (!refs.get(key).isJsonPrimitive()) {
                    continue;
                }
                String ref = refs.get(key).getAsString();
                if (ref.startsWith("#")) {
                    continue;
                }
                String name = ref.substring(Math.max(ref.lastIndexOf('/'), ref.lastIndexOf(':')) + 1).toLowerCase();
                if (name.endsWith(".png")) {
                    name = name.substring(0, name.length() - 4);
                }
                if (!NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("貼圖名稱只能用 a-z、0-9、_、-：" + ref);
                }
                byte[] png = textures.get(name);
                if (png == null) {
                    png = builtin ? null : read(new File(dir, name + ".png"), MAX_PNG);
                    if (png == null) {
                        png = builtin("textures/" + name + ".png");
                    }
                    if (png == null || !anyTexture(png)) {
                        throw new IllegalArgumentException("找不到貼圖 " + name + ".png（要放在 textures/）");
                    }
                    textures.put(name, png);
                }
                refs.addProperty(key, "vaultbank:item/" + name);
            }
            if (textures.isEmpty()) {
                throw new IllegalArgumentException("模型沒有用到任何貼圖");
            }
            model.add("textures", refs);
            if (!model.has("display")) {
                model.add("display", ModelGenerator.defaultDisplay());
            }
            byte[] icon = textures.containsKey(id) ? textures.get(id) : textures.values().iterator().next();
            if (!builtin) {
                byte[] own = read(new File(dir, id + ".png"), MAX_PNG);
                if (own != null && anyTexture(own)) {
                    icon = own;
                }
            }
            return new Look(Type.MODEL, model, textures, icon, null);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(plugin.messages().console("model-invalid", "file", id + ".json",
                    "reason", String.valueOf(e.getMessage())));
            return null;
        }
    }

    private byte[] builtin(String path) {
        try (InputStream in = plugin.getResource(path.contains("/") ? path : "textures/" + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] read(File f, long max) {
        try {
            if (!f.isFile() || f.length() > max) {
                return null;
            }
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    /** 自動加厚度用的圖片：正方形、16～128 像素、2 的次方。 */
    static boolean squareTexture(byte[] png) {
        BufferedImage img = image(png);
        if (img == null) {
            return false;
        }
        int w = img.getWidth();
        return w == img.getHeight() && w >= 16 && w <= 128 && Integer.bitCount(w) == 1;
    }

    /** 模型用的貼圖：任何 1～512 像素的 PNG。 */
    static boolean anyTexture(byte[] png) {
        BufferedImage img = image(png);
        return img != null && img.getWidth() <= 512 && img.getHeight() <= 512;
    }

    static BufferedImage image(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
