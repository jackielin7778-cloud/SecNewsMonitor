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
    /** 回應內容的讀取時限（秒，v1.8.0）。測試時可縮短。 */
    static long bodyTimeoutSec = 30;

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

    /** 推播文字訊息（每次產生新的重送編號）。 */
    public List<String> send(String token, Channel.LineMode mode, List<String> to, String text) throws IOException, InterruptedException {
        return send(token, mode, to, text, java.util.UUID.randomUUID().toString());
    }

    /**
     * 推播文字訊息。to 在 push 模式為收件人 ID 清單；broadcast 模式忽略。
     * idempotencyBase：同一則通知重送時使用相同的值（v1.8.0），重送編號（X-Line-Retry-Key）由它與收件人、段落編號產生，
     * LINE 已收過的請求會回 409，不會重複送達。
     * 回傳傳送失敗的收件人 ID（全部成功回傳空清單）；全部失敗或 broadcast 失敗時丟出例外。
     */
    public List<String> send(String token, Channel.LineMode mode, List<String> to, String text, String idempotencyBase)
            throws IOException, InterruptedException {
        validateToken(token);
        List<String> parts = split(text);
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < parts.size(); i += MAX_MESSAGES_PER_REQUEST) {
            batches.add(parts.subList(i, Math.min(parts.size(), i + MAX_MESSAGES_PER_REQUEST)));
        }
        if (mode == Channel.LineMode.BROADCAST) {
            for (int bi = 0; bi < batches.size(); bi++) {
                post("/v2/bot/message/broadcast", token, "{\"messages\":" + messages(batches.get(bi)) + "}",
                        retryKey(idempotencyBase, "broadcast", bi));
            }
            return List.of();
        }
        // 先檢查所有收件人 ID（v1.8.0）：格式不符的略過並記錄，避免送到一半才失敗、下次又重送給前面的人
        List<String> valid = new ArrayList<>();
        for (String id : to) {
            try {
                validateId(id);
                valid.add(id.trim());
            } catch (IllegalArgumentException e) {
                AppLog.info("LINE：" + e.getMessage() + "，已略過此收件人");
            }
        }
        if (valid.isEmpty()) throw new IllegalArgumentException("沒有格式正確的 LINE 收件人 ID");
        List<String> failed = new ArrayList<>();
        IOException last = null;
        for (String id : valid) {
            try {
                for (int bi = 0; bi < batches.size(); bi++) {
                    post("/v2/bot/message/push", token, "{\"to\":" + GoogleChatClient.jsonString(id)
                            + ",\"messages\":" + messages(batches.get(bi)) + "}", retryKey(idempotencyBase, id, bi));
                }
            } catch (IOException e) {
                failed.add(id);
                last = e;
            }
        }
        if (last != null) {
            if (failed.size() == valid.size()) throw last;
            AppLog.info("LINE：" + failed.size() + " 位收件人傳送失敗（" + mask(failed) + "）：" + last.getMessage());
        }
        return failed;
    }

    /** 重送編號：同一則通知、同一位收件人、同一段，永遠相同（UUID 格式）。 */
    static String retryKey(String base, String recipient, int batch) {
        return java.util.UUID.nameUUIDFromBytes((base + "|" + recipient + "|" + batch).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String mask(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(id.substring(0, Math.min(5, id.length())) + "…");
        return String.join("、", out);
    }

    private static String messages(List<String> parts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"text\",\"text\":").append(GoogleChatClient.jsonString(parts.get(i))).append('}');
        }
        return sb.append(']').toString();
    }

    /** 送出一個請求；5xx 與連線錯誤（逾時、連線中斷）以相同的重送編號重試（v1.8.0）。 */
    private void post(String path, String token, String json, String retryKey) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token.trim())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Line-Retry-Key", retryKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            int sc;
            String body;
            try {
                HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                sc = resp.statusCode();
                try {
                    body = Fetcher.readLimited(resp, 64 * 1024, bodyTimeoutSec);      // 大小與時間上限（v1.8.0）
                } catch (IOException e) {
                    body = "";
                }
            } catch (IOException e) {
                if (attempt >= RETRIES) throw new IOException("LINE 連線失敗：" + ChatText.oneLine(String.valueOf(e.getMessage()), 200), e);
                AppLog.info("LINE 連線失敗（" + e.getClass().getSimpleName() + "），" + (backoffMs << attempt) / 1000.0 + " 秒後以相同重送編號重試");
                Thread.sleep(backoffMs << attempt);
                continue;
            }
            if (sc / 100 == 2) return;
            if (sc == 409) return;                 // 同一個重送編號 LINE 已經收過
            String b = ChatText.oneLine(body, 300);
            String why = switch (sc) {
                case 400 -> "請求格式錯誤（請確認收件人 ID）";
                case 401 -> "Channel access token 無效或已過期";
                case 403 -> "沒有權限（請確認官方帳號已啟用 Messaging API）";
                case 429 -> "超過傳送頻率或本月可傳送則數（免費方案每月 200 則）";
                default -> "HTTP " + sc;
            };
            if (sc < 500 || attempt >= RETRIES) {
                throw new IOException("LINE 傳送失敗：" + why + (b.isEmpty() ? "" : "；回應：" + b));
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
