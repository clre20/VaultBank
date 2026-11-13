package clre20.vaultBank.pack;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.currency.CurrencyItems;
import clre20.vaultBank.currency.Denomination;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 產生材質包：Java 版 zip（3D 模型）、基岩版 .mcpack 與 Geyser 自訂物品對照檔。
 * 貼圖讀 plugins/VaultBank/textures/&lt;名稱&gt;.png（沒有時放入預設貼圖），內容相同時輸出的檔案位元組也相同，
 * 所以 SHA-1 不變，玩家不會每次重開都重新下載。
 */
public final class PackBuilder {

    /** Java 版 26.1 的資源包格式。 */
    private static final int MIN_FORMAT = 84;
    private static final long ZIP_TIME = 1_577_836_800_000L; // 2020-01-01
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public record Built(byte[] javaZip, String javaSha1, byte[] bedrockPack, String bedrockMappings) {
    }

    private final VaultBank plugin;

    public PackBuilder(VaultBank plugin) {
        this.plugin = plugin;
    }

    /** 所有物品的名稱（材質包裡的檔名）。 */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (Denomination d : Denomination.values()) {
            ids.add(d.id());
        }
        ids.add("cheque");
        return ids;
    }

    public Built build() throws IOException {
        Map<String, byte[]> textures = loadTextures();
        byte[] javaZip = javaPack(textures);
        String sha1 = HexFormat.of().formatHex(digest("SHA-1", javaZip));
        byte[] bedrock = bedrockPack(textures);
        return new Built(javaZip, sha1, bedrock, mappings());
    }

    // ---------- 貼圖 ----------

    private Map<String, byte[]> loadTextures() throws IOException {
        File dir = new File(plugin.getDataFolder(), "textures");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("無法建立 " + dir);
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String id : ids()) {
            byte[] builtin = builtin(id);
            File f = new File(dir, id + ".png");
            if (!f.isFile()) {
                Files.write(f.toPath(), builtin);
                out.put(id, builtin);
                continue;
            }
            byte[] custom = Files.readAllBytes(f.toPath());
            if (custom.length > 256 * 1024 || !validTexture(custom)) {
                plugin.getLogger().warning(plugin.messages().console("texture-invalid", "file", f.getName()));
                out.put(id, builtin);
            } else {
                out.put(id, custom);
            }
        }
        return out;
    }

    private byte[] builtin(String id) throws IOException {
        try (InputStream in = plugin.getResource("textures/" + id + ".png")) {
            if (in == null) {
                throw new IOException("jar 內缺少貼圖 " + id);
            }
            return in.readAllBytes();
        }
    }

    /** 正方形、16～128 像素、2 的次方。 */
    private static boolean validTexture(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                return false;
            }
            int w = img.getWidth();
            return w == img.getHeight() && w >= 16 && w <= 128 && Integer.bitCount(w) == 1;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------- Java ----------

    private byte[] javaPack(Map<String, byte[]> textures) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject pack = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("description", plugin.messages().plain("pack.description"));
        inner.addProperty("min_format", MIN_FORMAT);
        inner.addProperty("max_format", 999);
        pack.add("pack", inner);
        files.put("pack.mcmeta", PRETTY.toJson(pack).getBytes(StandardCharsets.UTF_8));
        files.put("pack.png", textures.get(Denomination.COIN_50.id()));
        for (String id : ids()) {
            byte[] png = textures.get(id);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            ModelGenerator.Thickness t = thickness(id);
            JsonObject model = ModelGenerator.model(img, "vaultbank:item/" + id, t);
            files.put("assets/vaultbank/models/item/" + id + ".json", GSON.toJson(model).getBytes(StandardCharsets.UTF_8));
            JsonObject def = new JsonObject();
            JsonObject m = new JsonObject();
            m.addProperty("type", "minecraft:model");
            m.addProperty("model", "vaultbank:item/" + id);
            def.add("model", m);
            files.put("assets/vaultbank/items/" + id + ".json", GSON.toJson(def).getBytes(StandardCharsets.UTF_8));
            files.put("assets/vaultbank/textures/item/" + id + ".png", png);
        }
        return zip(files);
    }

    private ModelGenerator.Thickness thickness(String id) {
        if (id.equals("cheque")) {
            double body = plugin.config().chequeThickness();
            double roll = plugin.config().chequeRollThickness();
            // 羊皮卷兩端的捲軸比較厚（左右各約 5/32 寬）
            return (x, y, size) -> (x < size * 5 / 32 || x >= size * 27 / 32) ? roll : body;
        }
        Denomination d = Denomination.byId(id);
        double t = d != null && d.coin() ? plugin.config().coinThickness() : plugin.config().noteThickness();
        return (x, y, size) -> t;
    }

    // ---------- 基岩版 ----------

    private byte[] bedrockPack(Map<String, byte[]> textures) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject itemTexture = new JsonObject();
        itemTexture.addProperty("resource_pack_name", "vaultbank");
        itemTexture.addProperty("texture_name", "atlas.items");
        JsonObject data = new JsonObject();
        for (String id : ids()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("textures", "textures/items/vaultbank/" + id);
            data.add("vaultbank." + id, entry);
            files.put("textures/items/vaultbank/" + id + ".png", textures.get(id));
        }
        itemTexture.add("texture_data", data);
        files.put("textures/item_texture.json", PRETTY.toJson(itemTexture).getBytes(StandardCharsets.UTF_8));
        files.put("pack_icon.png", textures.get(Denomination.COIN_50.id()));

        // 版本號跟著內容變，基岩版玩家才會重新下載改過的貼圖
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            all.write(e.getKey().getBytes(StandardCharsets.UTF_8));
            all.write(e.getValue());
        }
        byte[] h = digest("SHA-256", all.toByteArray());
        int patch = ((h[0] & 0x7f) << 8 | (h[1] & 0xff)) + 1;

        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        JsonObject header = new JsonObject();
        header.addProperty("name", plugin.messages().plain("pack.bedrock-name"));
        header.addProperty("description", plugin.messages().plain("pack.description"));
        header.addProperty("uuid", UUID.nameUUIDFromBytes("VaultBank-bedrock-header".getBytes(StandardCharsets.UTF_8)).toString());
        header.add("version", version(patch));
        JsonArray minEngine = new JsonArray();
        minEngine.add(1);
        minEngine.add(20);
        minEngine.add(0);
        header.add("min_engine_version", minEngine);
        manifest.add("header", header);
        JsonArray modules = new JsonArray();
        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", UUID.nameUUIDFromBytes("VaultBank-bedrock-module".getBytes(StandardCharsets.UTF_8)).toString());
        module.add("version", version(patch));
        modules.add(module);
        manifest.add("modules", modules);
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        ordered.put("manifest.json", PRETTY.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        ordered.putAll(files);
        return zip(ordered);
    }

    private static JsonArray version(int patch) {
        JsonArray v = new JsonArray();
        v.add(1);
        v.add(0);
        v.add(patch);
        return v;
    }

    /** Geyser custom_mappings（format_version 2，以 item_model 對應）。 */
    private String mappings() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        JsonObject items = new JsonObject();
        JsonArray list = new JsonArray();
        for (String id : ids()) {
            JsonObject def = new JsonObject();
            def.addProperty("type", "definition");
            def.addProperty("model", "vaultbank:" + id);
            def.addProperty("bedrock_identifier", "vaultbank:" + id);
            Denomination d = Denomination.byId(id);
            String name = d != null
                    ? plugin.messages().plain("items." + id + ".name", "value", d.value())
                    : plugin.messages().plain("items.cheque.bedrock-name");
            def.addProperty("display_name", name);
            JsonObject options = new JsonObject();
            options.addProperty("icon", "vaultbank." + id);
            options.addProperty("allow_offhand", true);
            def.add("bedrock_options", options);
            JsonObject components = new JsonObject();
            components.addProperty("minecraft:max_stack_size", d != null ? 64 : 1);
            def.add("components", components);
            list.add(def);
        }
        items.add(CurrencyItems.BASE.getKey().toString(), list);
        root.add("items", items);
        return PRETTY.toJson(root);
    }

    // ---------- 共用 ----------

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(9);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(ZIP_TIME);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
