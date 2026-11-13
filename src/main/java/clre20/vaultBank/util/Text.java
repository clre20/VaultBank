package clre20.vaultBank.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

/**
 * 文字工具：& 顏色碼（含 &#RRGGBB）轉成 Adventure Component。
 * 變數值一律當純文字插入，玩家名稱或輸入裡的 & 不會被當成顏色碼。
 */
public final class Text {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private static final Pattern MARK = Pattern.compile("(\\d{1,2})");

    private Text() {
    }

    /**
     * 把含 {name} 變數的模板轉成 Component。
     *
     * @param vars 成對的變數名稱與值
     */
    public static Component render(String template, Object... vars) {
        if (template == null) {
            return Component.empty();
        }
        if (vars.length < 2 || template.indexOf('{') < 0) {
            return AMPERSAND.deserialize(template);
        }
        String marked = template;
        int count = 0;
        String[] values = new String[vars.length / 2];
        for (int i = 0; i + 1 < vars.length; i += 2) {
            String token = "{" + vars[i] + "}";
            if (marked.contains(token)) {
                marked = marked.replace(token, "" + count + "");
            }
            values[count] = String.valueOf(vars[i + 1]);
            count++;
        }
        if (marked.indexOf('') < 0) {
            return AMPERSAND.deserialize(marked);
        }
        Component base = AMPERSAND.deserialize(marked);
        return base.replaceText(TextReplacementConfig.builder()
                .match(MARK)
                .replacement((java.util.regex.MatchResult m, net.kyori.adventure.text.TextComponent.Builder b) -> {
                    int idx = Integer.parseInt(m.group(1));
                    return Component.text(idx < values.length && values[idx] != null ? values[idx] : "");
                })
                .build());
    }

    /** 物品名稱與描述用：預設不要斜體。 */
    public static Component item(String template, Object... vars) {
        return render(template, vars).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String plain(String template, Object... vars) {
        return plain(render(template, vars));
    }
}
