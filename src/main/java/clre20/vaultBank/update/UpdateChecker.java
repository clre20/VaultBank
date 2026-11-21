package clre20.vaultBank.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 從 GitHub Releases 檢查新版本。
 * 結果快取 10 分鐘、不定時輪詢；同一時間只會有一個查詢在進行。
 * 網路請求只用一條背景執行緒。
 */
public final class UpdateChecker {

    public static final String REPO = "clre20/VaultBank";
    public static final String PAGE = "https://github.com/" + REPO + "/releases";
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;

    public enum Status { NEWER, LATEST, DEV, FAILED }

    /**
     * 檢查結果。FAILED 時 reasonKey 是 messages.yml 的 version.reason-* 鍵，reasonArg 是附帶的變數值。
     */
    public record Result(Status status, String current, String latest, String reasonKey, String reasonArg) {
    }

    private final String current;
    private CompletableFuture<Result> cached;
    private long cachedAt;

    public UpdateChecker(String current) {
        this.current = current;
    }

    /** 取得檢查結果（10 分鐘內重複呼叫會直接用快取）。 */
    public synchronized CompletableFuture<Result> check() {
        long now = System.currentTimeMillis();
        if (cached != null && (!cached.isDone() || now - cachedAt < CACHE_MILLIS)) {
            return cached;
        }
        cachedAt = now;
        cached = fetch();
        return cached;
    }

    /** 已經有的結果（沒查過或還在查時回傳 null），不會發出網路請求。 */
    public synchronized Result lastResult() {
        return cached != null && cached.isDone() ? cached.getNow(null) : null;
    }

    public void shutdown() {
        // 無常駐線程，不需關閉
    }

        private CompletableFuture<Result> fetch() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String targetUrl = System.getProperty("vaultbank.update-api", "https://api.github.com/repos/" + REPO + "/releases/latest");
                java.net.URI uri = java.net.URI.create(targetUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "VaultBank/" + this.current);
                int code = conn.getResponseCode();
                if (code == 404) {
                    conn.disconnect();
                    return failed("version.reason-no-release", "");
                }
                if (code != 200) {
                    conn.disconnect();
                    return failed("version.reason-http", String.valueOf(code));
                }
                String body;
                try (java.io.InputStream in = conn.getInputStream()) {
                    body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } finally {
                    conn.disconnect();
                }
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (!json.has("tag_name")) {
                    return failed("version.reason-no-tag", "");
                }
                String latest = strip(json.get("tag_name").getAsString());
                int cmp = compare(latest, strip(this.current));
                Status status = cmp > 0 ? Status.NEWER : cmp < 0 ? Status.DEV : Status.LATEST;
                return new Result(status, this.current, latest, null, null);
            } catch (Exception t) {
                return failed("version.reason-offline", "");
            }
        });
    }

    private Result failed(String key, String arg) {
        return new Result(Status.FAILED, current, null, key, arg);
    }

    /** 去掉標籤的 v 前綴。 */
    static String strip(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    /** 比較 1.2.3 格式的版本號。 */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-+]");
        String[] y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0;
            int yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
