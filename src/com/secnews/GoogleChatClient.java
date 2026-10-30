package com.secnews;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Chat 傳入 Webhook（Incoming Webhook）。
 * Webhook URL 格式：https://chat.googleapis.com/v1/spaces/SPACE_ID/messages?key=KEY&token=TOKEN
 * 以 POST JSON {"text": "..."} 傳送訊息。
 */
public final class GoogleChatClient {
    /** 保守的單則訊息長度，超過就拆成多則。 */
    private static final int CHUNK = 3500;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public static String buildWebhookUrl(String spaceId, String key, String token) {
        return "https://chat.googleapis.com/v1/spaces/" + enc(spaceId.trim())
                + "/messages?key=" + enc(key.trim()) + "&token=" + enc(token.trim());
    }

    public static void validate(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("尚未設定 Webhook URL");
        URI u = URI.create(url.trim());
        // 以 Locale.ROOT 轉小寫後完全比對（v1.7.1：避免 equalsIgnoreCase 把 ı、K 等非 ASCII 字元視為相同）
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equals(sc) || !"chat.googleapis.com".equals(host)) {
            throw new IllegalArgumentException("Webhook 必須是 https://chat.googleapis.com/... 的網址");
        }
    }

    /** 最多重試次數（遇到 HTTP 429 頻率限制或 5xx 伺服器錯誤時）。 */
    static final int MAX_RETRIES = 4;
    /** 測試時可縮短等待時間。 */
    static long backoffUnitMs = 2000;

    public void send(String webhookUrl, String text) throws IOException, InterruptedException {
        validate(webhookUrl);
        post(webhookUrl.trim(), split(text));
    }

    /** 逐則送出；遇到 429／5xx 依 Retry-After 或指數退避（2、4、8、16 秒）重試。 */
    void post(String url, List<String> parts) throws IOException, InterruptedException {
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) Thread.sleep(1100); // 每則間隔 1.1 秒，降低觸發頻率限制的機會
            String json = "{\"text\":" + jsonString(parts.get(i)) + "}";
            for (int attempt = 0; ; attempt++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if (sc / 100 == 2) break;
                String b = resp.body() == null ? "" : resp.body();
                String detail = "HTTP " + sc + "：" + b.substring(0, Math.min(300, b.length()));
                boolean retryable = sc == 429 || sc >= 500;
                if (!retryable || attempt >= MAX_RETRIES) {
                    throw new IOException("Google Chat 第 " + (i + 1) + "/" + parts.size() + " 則傳送失敗（"
                            + (retryable ? "已重試 " + attempt + " 次，" : "") + detail + "）");
                }
                long wait = retryAfterMs(resp).orElse(backoffUnitMs << attempt);
                AppLog.info("Google Chat 回應 HTTP " + sc + "（第 " + (i + 1) + "/" + parts.size()
                        + " 則），" + (wait / 1000.0) + " 秒後重試（第 " + (attempt + 1) + " 次）");
                Thread.sleep(wait);
            }
        }
    }

    private static java.util.Optional<Long> retryAfterMs(HttpResponse<?> resp) {
        return resp.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                long s = Long.parseLong(v.trim());
                return java.util.Optional.of(Math.min(120, Math.max(1, s)) * 1000);
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String block : text.split("\n\n")) {
            if (cur.length() + block.length() + 2 > CHUNK && cur.length() > 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            if (block.length() > CHUNK) block = block.substring(0, CHUNK);
            if (cur.length() > 0) cur.append("\n\n");
            cur.append(block);
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
