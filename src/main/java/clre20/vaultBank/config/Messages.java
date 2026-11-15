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
     * 1.0.3：「櫃台」改成「系統」。1.1.0：多幣種（「元」寫死的地方改成變數）。
     */
    private static final String[][] OLD_DEFAULTS = {
            {"input.withdraw-amount", "&e請在聊天室輸入要提取的金額（整數），櫃台會自動配好面額，{seconds} 秒內有效，輸入「{cancel}」返回。"},
            {"input.withdraw-amount", "&e請在聊天室輸入要提取的金額（整數），系統會自動配好面額，{seconds} 秒內有效，輸入「{cancel}」返回。"},
            {"gui.withdraw.title", "&8提取（櫃台）"},
            {"gui.withdraw.title", "&8提取"},
            {"gui.withdraw.auto.name", "&b櫃台自動配"},
            {"items.cheque.name", "&c支票 &f{amount} 元"},
            {"usage.pay", "&c用法：&e/pay <玩家> <金額>"},
            {"usage.baltop", "&c用法：&e/baltop [頁]"},
            {"usage.withdraw", "&c用法：&e/vb withdraw [金額] &c或 &e/vb withdraw <面額> <數量>"},
            {"usage.cheque", "&c用法：&e/vb cheque [金額]"},
            {"usage.eco", "&c用法：&e/eco <give|take|set|reset> <玩家> [金額]"},
            {"help.pay", "&e/vb pay <玩家> <金額> &7或 &e/pay &7- 轉帳"},
            {"help.baltop", "&e/vb baltop [頁] &7或 &e/baltop &7- 餘額排行榜（管理員）"},
            {"help.withdraw", "&e/vb withdraw [金額] &7或 &e<面額> <數量> &7- 提取實體貨幣（不加參數開啟提取介面）"},
            {"help.cheque", "&e/vb cheque [金額] &7- 開支票"},
            {"help.eco", "&e/vb eco &7或 &e/eco <give|take|set|reset> <玩家> [金額] &7- 管理餘額"},
            {"help.give", "&e/vb give <玩家> <面額> [數量] &7- 直接發放實體貨幣"},
            {"help.audit", "&e/vb audit &7- 貨幣流通量與支票統計"},
            {"baltop.header", "&6&l餘額排行榜 &7（第 {page}/{pages} 頁，全服總額 &6{total}&7）"},
            {"deposit.over-max", "&c存入後餘額會超過上限 &e{max}&c，物品已退回。"},
            {"withdraw.unknown-denomination", "&c沒有「{input}」這個面額，可用：1、5、10、50、100、500、1000。"},
            {"give.done", "&a已發給 &e{player} &a{count} 個 {value} 元。"},
            {"audit.line", "&7{value} 元：發行 &f{minted} &7回收 &f{redeemed} &7流通中 &a{outstanding}"},
            {"audit.line-bad", "&7{value} 元：發行 &f{minted} &7回收 &f{redeemed} &7流通中 &c{outstanding} &c（回收超過發行，可能有人複製）"},
            {"alert.over-redeemed", "&c[警告] {value} 元的回收量（{redeemed}）超過發行量（{minted}），可能有人複製貨幣。"},
            {"console.alert-over-redeemed", "{value} 元的回收量（{redeemed}）超過發行量（{minted}），可能有人複製貨幣"},
            {"console.texture-invalid", "貼圖 {file} 無效（需為 16～128 像素、2 的次方的正方形 PNG，256 KB 以內），改用內建貼圖"},
            {"input.cheque-amount", "&e請在聊天室輸入支票金額（整數），{seconds} 秒內有效，輸入「{cancel}」返回。"},
            {"input.pay", "&e請在聊天室輸入「玩家名稱 金額」，例如 &fSteve 100&e，{seconds} 秒內有效，輸入「{cancel}」返回。"},
            {"input.form.cheque-amount.label", "支票金額（整數）"},
            {"input.form.withdraw-amount.label", "要提取的金額（整數），系統會自動配好面額"},
            {"input.form.pay.label", "玩家名稱 金額"},
            {"gui.withdraw.denomination.name", "&f{value} 元 × {count}"},
            {"gui.withdraw.disabled.name", "&8{value} 元（停止提取）"},
            {"log.types.import", "從 TheosisEconomy 匯入"},
    };

    /** 清單（lore）裡的單行：{鍵, 舊行, 新行}。 */
    private static final String[][] OLD_LINES = {
            {"gui.main.withdraw.lore", "&7可以讓櫃台自動配，也可以自己選面額", "&7可以讓系統自動配，也可以自己選面額"},
            {"items.cheque.lore", "&7面額：&f{amount} 元", "&7面額：&f{money}"},
            {"gui.withdraw.add.lore", "&7{value} 元 加 {n} 個", "&7{money} 加 {n} 個"},
            {"gui.withdraw.remove.lore", "&7{value} 元 減 {n} 個", "&7{money} 減 {n} 個"},
    };

    private void upgradeOldDefaults() {
        boolean changed = false;
        for (String[] e : OLD_DEFAULTS) {
            if (e[1].equals(yaml.getString(e[0], null))) {
                yaml.set(e[0], defaults.getString(e[0]));
                changed = true;
            }
        }
        for (String[] e : OLD_LINES) {
            if (yaml.isList(e[0])) {
                List<String> lore = new ArrayList<>(yaml.getStringList(e[0]));
                int i = lore.indexOf(e[1]);
                if (i >= 0) {
                    lore.set(i, e[2]);
                    yaml.set(e[0], lore);
                    changed = true;
                }
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

    /** messages.yml（或預設檔）有這個項目。 */
    public boolean has(String key) {
        return yaml.contains(key) || defaults.contains(key);
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
