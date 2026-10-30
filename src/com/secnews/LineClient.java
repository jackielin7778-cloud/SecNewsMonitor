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
import java.util.regex.Pattern;

/**
 * LINE 推播（v1.8，LINE 官方帳號的 Messaging API）。
 * LINE Notify 已於 2025-03-31 停止服務，因此改用 Messaging API：
 *   push      ：POST https://api.line.me/v2/bot/message/push      {"to": ID, "messages": [...]}
 *   broadcast ：POST https://api.line.me/v2/bot/message/broadcast {"messages": [...]}
 * 每次請求最多 5 個訊息物件；文字過長時分段。
 * 免費方案（台灣輕用量）每月 200 則，push 每位收件人、broadcast 每位好友各算 1 則。
 */
public final class LineClient {
    /** API 位址（測試時可改成本機假伺服器）。 */
    static String apiBase = "https://api.line.me";
    /** 每段文字的上限（LINE 文字訊息上限為 5,000 字，保守取 4,000）。 */
    static final int CHUNK = 4000;
    static final int MAX_MESSAGES_PER_REQUEST = 5;
    /** 5xx 錯誤重試次數；429（頻率或每月則數已達上限）不重試。 */
    static final int RETRIES = 2;
    static long backoffMs = 2000;

    /** 使用者 ID（U）、群組 ID（C）、聊天室 ID（R）：一個字母加 32 個十六進位字元。 */
    private static final Pattern ID = Pattern.compile("[UCR][0-9a-f]{32}");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public static void validateId(String id) {
        if (id == null || !ID.matcher(id.trim()).matches()) {
            throw new IllegalArgumentException("LINE 收件人 ID 格式不符：" + ChatText.oneLine(id, 40)
                    + "（應為 U 開頭加 32 個英數字，例如 LINE Developers 後台「Your user ID」）");
        }
    }

    public static void validateToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("請填寫 Channel access token");
        String t = token.trim();
        if (t.length() > 2000) throw new IllegalArgumentException("Channel access token 長度不符");
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= 0x20 || c >= 0x7f) throw new IllegalArgumentException("Channel access token 含有不允許的字元（請確認沒有貼到空白或換行）");
        }
    }

    /** 推播文字訊息。to 在 push 模式為收件人 ID 清單；broadcast 模式忽略。 */
    public void send(String token, Channel.LineMode mode, List<String> to, String text) throws IOException, InterruptedException {
        validateToken(token);
        List<String> parts = split(text);
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < parts.size(); i += MAX_MESSAGES_PER_REQUEST) {
            batches.add(parts.subList(i, Math.min(parts.size(), i + MAX_MESSAGES_PER_REQUEST)));
        }
        if (mode == Channel.LineMode.BROADCAST) {
            for (List<String> b : batches) post("/v2/bot/message/broadcast", token, "{\"messages\":" + messages(b) + "}");
            return;
        }
        if (to.isEmpty()) throw new IllegalArgumentException("沒有設定 LINE 收件人 ID");
        List<String> failed = new ArrayList<>();
        IOException last = null;
        for (String id : to) {
            validateId(id);
            try {
                for (List<String> b : batches) {
                    post("/v2/bot/message/push", token, "{\"to\":" + GoogleChatClient.jsonString(id.trim())
                            + ",\"messages\":" + messages(b) + "}");
                }
            } catch (IOException e) {
                failed.add(id.substring(0, 5) + "…");
                last = e;
            }
        }
        if (last != null) {
            if (failed.size() == to.size()) throw last;
            AppLog.info("LINE：部分收件人傳送失敗（" + String.join("、", failed) + "）：" + last.getMessage());
        }
    }

    private static String messages(List<String> parts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"text\",\"text\":").append(GoogleChatClient.jsonString(parts.get(i))).append('}');
        }
        return sb.append(']').toString();
    }

    private void post(String path, String token, String json) throws IOException, InterruptedException {
        String retryKey = java.util.UUID.randomUUID().toString();   // 重試時沿用，避免重複傳送
        for (int attempt = 0; ; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token.trim())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Line-Retry-Key", retryKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc / 100 == 2) return;
            if (sc == 409) return;                 // 同一個 Retry-Key 已經成功送出過
            String body = resp.body() == null ? "" : ChatText.oneLine(resp.body(), 300);
            String why = switch (sc) {
                case 400 -> "請求格式錯誤（請確認收件人 ID）";
                case 401 -> "Channel access token 無效或已過期";
                case 403 -> "沒有權限（請確認官方帳號已啟用 Messaging API）";
                case 429 -> "超過傳送頻率或本月可傳送則數（免費方案每月 200 則）";
                default -> "HTTP " + sc;
            };
            if (sc < 500 || attempt >= RETRIES) {
                throw new IOException("LINE 傳送失敗：" + why + (body.isEmpty() ? "" : "；回應：" + body));
            }
            AppLog.info("LINE 回應 HTTP " + sc + "，" + (backoffMs << attempt) / 1000.0 + " 秒後重試");
            Thread.sleep(backoffMs << attempt);
        }
    }

    /** 依段落（空一行）切成不超過 CHUNK 字的段。 */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String block : text.split("\n\n")) {
            if (block.length() > CHUNK) block = block.substring(0, CHUNK);
            if (cur.length() > 0 && cur.length() + block.length() + 2 > CHUNK) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append("\n\n");
            cur.append(block);
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }
}
