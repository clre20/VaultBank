package clre20.vaultBank.config;

import clre20.vaultBank.VaultBank;
import clre20.vaultBank.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * messages.yml：所有顯示文字。
 * 變數寫法 {name}，呼叫時用成對的參數替換：send(sender, "key", "name", 值, ...)。
 */
public final class Messages {

    private final VaultBank plugin;
    private volatile YamlConfiguration yaml = new YamlConfiguration();
    private volatile YamlConfiguration defaults = new YamlConfiguration();
    private int lastFilled;

    public Messages(VaultBank plugin) {
        this.plugin = plugin;
    }

    public void load() {
        YamlFiles.Loaded loaded = YamlFiles.load(plugin, "messages.yml",
                (file, info) -> plugin.getLogger().warning(file + " 讀取失敗，已備份為 " + info + "，這次使用預設文字"));
        yaml = loaded.yaml();
        defaults = loaded.defaults();
        lastFilled = loaded.filled();
        if (!loaded.broken()) {
            upgradeOldDefaults();
        }
    }

    /**
     * 舊版預設文字已改掉的項目：使用者沒改過（和舊預設完全相同）才換成新預設，改過的保留。
     * 1.0.3：「櫃台」改成「系統」。
     */
    private static final String[][] OLD_DEFAULTS = {
            {"input.withdraw-amount", "&e請在聊天室輸入要提取的金額（整數），櫃台會自動配好面額，{seconds} 秒內有效，輸入「{cancel}」返回。"},
            {"gui.withdraw.title", "&8提取（櫃台）"},
            {"gui.withdraw.auto.name", "&b櫃台自動配"},
    };
    private static final String[] OLD_LORE = {"gui.main.withdraw.lore", "&7可以讓櫃台自動配，也可以自己選面額"};

    private void upgradeOldDefaults() {
        boolean changed = false;
        for (String[] e : OLD_DEFAULTS) {
            if (e[1].equals(yaml.getString(e[0], null))) {
                yaml.set(e[0], defaults.getString(e[0]));
                changed = true;
            }
        }
        if (yaml.isList(OLD_LORE[0])) {
            List<String> lore = new ArrayList<>(yaml.getStringList(OLD_LORE[0]));
            int i = lore.indexOf(OLD_LORE[1]);
            if (i >= 0) {
                lore.set(i, OLD_LORE[1].replace("櫃台", "系統"));
                yaml.set(OLD_LORE[0], lore);
                changed = true;
            }
        }
        if (changed) {
            try {
                yaml.save(new java.io.File(plugin.getDataFolder(), "messages.yml"));
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("messages.yml 更新失敗：" + e.getMessage());
            }
        }
    }

    public int lastFilled() {
        return lastFilled;
    }

    /** 原始模板（含 & 顏色碼與 {變數}），找不到時回傳 key 本身。 */
    public String raw(String key) {
        String s = yaml.getString(key);
        if (s == null) {
            s = defaults.getString(key, key);
        }
        return s;
    }

    public List<String> rawList(String key) {
        if (yaml.isList(key)) {
            return yaml.getStringList(key);
        }
        return defaults.getStringList(key);
    }

    public Component component(String key, Object... vars) {
        return Text.render(raw(key), vars);
    }

    public Component item(String key, Object... vars) {
        return Text.item(raw(key), vars);
    }

    public List<Component> itemLore(String key, Object... vars) {
        List<String> lines = rawList(key);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(Text.item(line, vars));
        }
        return out;
    }

    public String plain(String key, Object... vars) {
        return Text.plain(raw(key), vars);
    }

    /** 帶前綴的訊息。 */
    public void send(CommandSender to, String key, Object... vars) {
        String template = raw(key);
        if (template.isEmpty()) {
            return;
        }
        to.sendMessage(Text.render(raw("prefix")).append(Text.render(template, vars)));
    }

    /** 不帶前綴的訊息。 */
    public void line(CommandSender to, String key, Object... vars) {
        String template = raw(key);
        if (template.isEmpty()) {
            return;
        }
        to.sendMessage(Text.render(template, vars));
    }

    /** 後台訊息（純文字）。 */
    public String console(String key, Object... vars) {
        return plain("console." + key, vars);
    }
}
