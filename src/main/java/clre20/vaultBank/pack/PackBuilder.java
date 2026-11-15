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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 產生材質包：Java 版 zip（3D 模型）、基岩版 .mcpack 與 Geyser 自訂物品對照檔。
 * 外觀由 {@link Appearance} 決定（模型、圖片加厚度或原版物品），內容相同時輸出的檔案位元組也相同，
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

    public Built build() throws IOException {
        Map<String, Appearance.Look> looks = plugin.appearance().looks();
        byte[] javaZip = javaPack(looks);
        String sha1 = HexFormat.of().formatHex(digest("SHA-1", javaZip));
        byte[] bedrock = bedrockPack(looks);
        return new Built(javaZip, sha1, bedrock, mappings(looks));
    }

    /** 材質包圖示：元 50 的圖，沒有就用第一個有圖的外觀。 */
    private static byte[] packIcon(Map<String, Appearance.Look> looks) {
        Appearance.Look l = looks.get("coin_50");
        if (l != null && l.icon() != null) {
            return l.icon();
        }
        for (Appearance.Look x : looks.values()) {
            if (x.icon() != null) {
                return x.icon();
            }
        }
        return null;
    }

    // ---------- Java ----------

    private byte[] javaPack(Map<String, Appearance.Look> looks) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject pack = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("description", plugin.messages().plain("pack.description"));
        inner.addProperty("min_format", MIN_FORMAT);
        inner.addProperty("max_format", 999);
        pack.add("pack", inner);
        files.put("pack.mcmeta", PRETTY.toJson(pack).getBytes(StandardCharsets.UTF_8));
        byte[] icon = packIcon(looks);
        if (icon != null) {
            files.put("pack.png", icon);
        }
        Map<String, byte[]> textures = new LinkedHashMap<>();
        for (Map.Entry<String, Appearance.Look> e : looks.entrySet()) {
            String id = e.getKey();
            Appearance.Look look = e.getValue();
            JsonObject model;
            switch (look.type()) {
                case MODEL -> model = look.model();
                case IMAGE -> {
                    byte[] png = look.textures().get(id);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                    model = ModelGenerator.model(img, "vaultbank:item/" + id, thickness(id));
                }
                default -> {
                    continue; // 原版外觀不需要材質包
                }
            }
            for (Map.Entry<String, byte[]> t : look.textures().entrySet()) {
                byte[] old = textures.putIfAbsent(t.getKey(), t.getValue());
                if (old != null && !java.util.Arrays.equals(old, t.getValue())) {
                    plugin.getLogger().warning("貼圖名稱 " + t.getKey() + " 被不同的模型重複使用，只會用第一張");
                }
            }
            files.put("assets/vaultbank/models/item/" + id + ".json", GSON.toJson(model).getBytes(StandardCharsets.UTF_8));
            JsonObject def = new JsonObject();
            JsonObject m = new JsonObject();
            m.addProperty("type", "minecraft:model");
            m.addProperty("model", "vaultbank:item/" + id);
            def.add("model", m);
            files.put("assets/vaultbank/items/" + id + ".json", GSON.toJson(def).getBytes(StandardCharsets.UTF_8));
        }
        for (Map.Entry<String, byte[]> t : textures.entrySet()) {
            files.put("assets/vaultbank/textures/item/" + t.getKey() + ".png", t.getValue());
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
        Denomination d = plugin.config().currencies().denomination(id);
        double t = d != null ? d.thickness() : plugin.config().coinThickness();
        return (x, y, size) -> t;
    }

    // ---------- 基岩版 ----------

    private byte[] bedrockPack(Map<String, Appearance.Look> looks) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject itemTexture = new JsonObject();
        itemTexture.addProperty("resource_pack_name", "vaultbank");
        itemTexture.addProperty("texture_name", "atlas.items");
        JsonObject data = new JsonObject();
        for (Map.Entry<String, Appearance.Look> e : looks.entrySet()) {
            if (e.getValue().icon() == null) {
                continue;
            }
            String id = e.getKey();
            JsonObject entry = new JsonObject();
            entry.addProperty("textures", "textures/items/vaultbank/" + id);
            data.add("vaultbank." + id, entry);
            files.put("textures/items/vaultbank/" + id + ".png", e.getValue().icon());
        }
        itemTexture.add("texture_data", data);
        files.put("textures/item_texture.json", PRETTY.toJson(itemTexture).getBytes(StandardCharsets.UTF_8));
        byte[] icon = packIcon(looks);
        if (icon != null) {
            files.put("pack_icon.png", icon);
        }

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

    /**
     * Geyser custom_mappings（format_version 2，以 item_model 對應）。
     * 原版外觀的面額用 minecraft:&lt;物品&gt; 當 item_model，同一種物品只能對應一次，圖示用基岩版內建的同名貼圖。
     */
    private String mappings(Map<String, Appearance.Look> looks) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        JsonObject items = new JsonObject();
        JsonArray list = new JsonArray();
        java.util.Set<String> vanilla = new java.util.HashSet<>();
        for (Map.Entry<String, Appearance.Look> e : looks.entrySet()) {
            String id = e.getKey();
            Appearance.Look look = e.getValue();
            Denomination d = plugin.config().currencies().denomination(id);
            JsonObject def = new JsonObject();
            def.addProperty("type", "definition");
            String icon;
            if (look.type() == Appearance.Type.VANILLA) {
                if (!vanilla.add(look.material())) {
                    continue;
                }
                def.addProperty("model", "minecraft:" + look.material());
                def.addProperty("bedrock_identifier", "vaultbank:vanilla_" + look.material());
                icon = look.material();
            } else {
                def.addProperty("model", "vaultbank:" + id);
                def.addProperty("bedrock_identifier", "vaultbank:" + id);
                icon = "vaultbank." + id;
            }
            String name = d != null ? plugin.itemName(d) : plugin.messages().plain("items.cheque.bedrock-name");
            def.addProperty("display_name", name);
            JsonObject options = new JsonObject();
            options.addProperty("icon", icon);
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
