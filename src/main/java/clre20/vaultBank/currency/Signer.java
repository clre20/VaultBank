package clre20.vaultBank.currency;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.logging.Logger;

/**
 * 物品簽章：HMAC-SHA256（伺服器密鑰 secret.key，第一次啟動隨機產生）。
 * 玩家沒有密鑰就做不出有效的簽章；密鑰外流時刪掉 secret.key 重開，舊的貨幣與支票會全部失效。
 */
public final class Signer {

    private static final int KEY_BYTES = 32;
    private static final int SIG_BYTES = 16;

    private final byte[] key;

    private Signer(byte[] key) {
        this.key = key;
    }

    public static Signer load(File file, Logger logger) throws IOException {
        if (file.isFile()) {
            String text = Files.readString(file.toPath(), StandardCharsets.US_ASCII).trim();
            try {
                byte[] k = Base64.getDecoder().decode(text);
                if (k.length >= KEY_BYTES) {
                    return new Signer(k);
                }
            } catch (IllegalArgumentException ignored) {
                // 下面當成壞掉處理
            }
            throw new IOException(file.getName() + " 格式錯誤；如果要重新產生，刪掉它再啟動（舊的貨幣與支票會失效）");
        }
        byte[] k = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(k);
        Files.writeString(file.toPath(), Base64.getEncoder().encodeToString(k), StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(file.toPath(),
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等不支援 POSIX 權限
        }
        logger.info("已產生新的密鑰 " + file.getName() + "，請勿外流");
        return new Signer(k);
    }

    public String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            byte[] cut = new byte[SIG_BYTES];
            System.arraycopy(full, 0, cut, 0, SIG_BYTES);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(cut);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 固定時間比對，避免從比對時間猜出簽章。 */
    public boolean verify(String data, String signature) {
        if (signature == null || signature.length() > 64) {
            return false;
        }
        return MessageDigest.isEqual(sign(data).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
