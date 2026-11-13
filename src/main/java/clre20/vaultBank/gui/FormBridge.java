package clre20.vaultBank.gui;

import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基岩版輸入框（Floodgate 表單）。只有 floodgate 啟用時才會載入這個類別，沒裝 Floodgate 時不會出現 NoClassDefFoundError。
 * 表單的回呼在網路執行緒上執行，呼叫端要自己排回主執行緒。
 */
final class FormBridge {

    private FormBridge() {
    }

    static boolean isBedrock(UUID id) {
        FloodgateApi api = FloodgateApi.getInstance();
        return api != null && api.isFloodgatePlayer(id);
    }

    /** 送出只有一個輸入框的表單；送出失敗回傳 false（呼叫端改用聊天室）。 */
    static boolean ask(UUID id, String title, String label, String placeholder,
                       Consumer<String> onText, Runnable onClose) {
        CustomForm form = CustomForm.builder()
                .title(title)
                .input(label, placeholder)
                .validResultHandler(response -> {
                    String text = response.asInput();
                    onText.accept(text == null ? "" : text);
                })
                .closedOrInvalidResultHandler(onClose)
                .build();
        return FloodgateApi.getInstance().sendForm(id, form);
    }
}
