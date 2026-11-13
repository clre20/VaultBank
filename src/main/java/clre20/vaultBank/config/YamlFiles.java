package clre20.vaultBank.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;

/**
 * 讀取插件資料夾裡的 yml，並自動補上缺少的項目。
 * 有缺少時：先備份成 .bak，再以插件內建的預設檔為底（保留預設檔的註解），填回使用者原本的值後寫回檔案。
 * 檔案格式壞掉時：備份成 .broken-時間，這次先用預設值，不覆寫原檔。
 */
final class YamlFiles {

    record Loaded(YamlConfiguration yaml, YamlConfiguration defaults, int filled, boolean broken) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private YamlFiles() {
    }

    static YamlConfiguration defaults(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    static Loaded load(JavaPlugin plugin, String name, BiConsumer<String, String> onError) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration defaults = defaults(plugin, name);
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            File backup = new File(file.getParentFile(), name + ".broken-" + LocalDateTime.now().format(STAMP));
            try {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 備份失敗也照樣用預設值
            }
            onError.accept(name, backup.getName());
            YamlConfiguration fallback = defaults(plugin, name);
            return new Loaded(fallback, defaults, 0, true);
        }

        int missing = 0;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !user.contains(key, true)) {
                missing++;
            }
        }
        if (missing > 0) {
            YamlConfiguration merged = defaults(plugin, name);
            for (String key : user.getKeys(true)) {
                if (user.isConfigurationSection(key) || key.equals("config-version")) {
                    continue;
                }
                if (merged.isConfigurationSection(key)) {
                    continue;
                }
                merged.set(key, user.get(key));
            }
            try {
                Files.copy(file.toPath(), new File(file.getParentFile(), name + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                merged.save(file);
                user = merged;
            } catch (IOException e) {
                onError.accept(name, e.getMessage());
                missing = 0;
            }
        }
        user.setDefaults(defaults);
        return new Loaded(user, defaults, missing, false);
    }
}
