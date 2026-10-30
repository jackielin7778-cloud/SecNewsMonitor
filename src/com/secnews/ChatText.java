package com.secnews;

import java.net.URI;
import java.util.Locale;

/**
 * Google Chat 訊息的安全處理（v1.7.1，防訊息注入）。
 *
 * 推播內容（新聞標題、內文片段、商品名稱、連結）來自外部網站。Google Chat 的文字訊息支援
 * {@code <users/all>}（通知聊天室全部成員）、{@code <網址|文字>}（自訂連結文字，可偽裝成別的網址）、
 * {@code *粗體*}、{@code _斜體_}、{@code ~刪除線~}、反引號等格式，以及換行。
 * 外部文字一律經過這裡：格式符號換成全形、去除換行與控制字元（含 Unicode 雙向控制字元），
 * 連結只接受 http／https 且不含會被 Chat 解讀的字元。
 */
final class ChatText {
    private ChatText() {}

    /** 單行外部文字（標題、商品名稱、片段）：去除換行與控制字元、跳脫 Chat 格式符號、限制長度。 */
    static String oneLine(String s, int maxChars) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), maxChars + 8));
        boolean space = false;
        int count = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || cp == 0x00A0 || cp == 0x2028 || cp == 0x2029 || cp == 0x0085) {
                space = out.length() > 0;
                continue;
            }
            if (isInvisible(cp)) continue;
            if (count >= maxChars) { out.append('…'); break; }
            if (space) { out.append(' '); count++; space = false; }
            out.append(escape(cp));
            count++;
        }
        return out.toString();
    }

    /** 控制字元、雙向文字控制字元、零寬字元等（可用來偽造顯示內容）。 */
    private static boolean isInvisible(int cp) {
        if (Character.isISOControl(cp)) return true;
        if (cp >= 0x200B && cp <= 0x200F) return true;     // 零寬空白、LRM、RLM
        if (cp >= 0x202A && cp <= 0x202E) return true;     // LRE、RLE、PDF、LRO、RLO
        if (cp >= 0x2066 && cp <= 0x2069) return true;     // LRI、RLI、FSI、PDI
        return cp == 0xFEFF || cp == 0x061C;
    }

    private static String escape(int cp) {
        return switch (cp) {
            case '<' -> "＜";
            case '>' -> "＞";
            case '*' -> "＊";
            case '_' -> "＿";
            case '~' -> "～";
            case '`' -> "｀";
            case '|' -> "｜";
            default -> new String(Character.toChars(cp));
        };
    }

    /** 是否為 http／https 絕對網址（不分大小寫）。 */
    static boolean isWebUrl(String s) {
        if (s == null) return false;
        try {
            URI u = URI.create(s.trim());
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (sc.equals("http") || sc.equals("https")) && u.getHost() != null && !u.getHost().isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 外部連結：只接受 http／https；把空白與 Chat 會解讀的字元（&lt; &gt; | 反引號 *）做百分比編碼，
     * 不合格回傳空字串（呼叫端略過連結）。
     */
    static String link(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isInvisible(cp)) continue;
            switch (cp) {
                case ' ' -> b.append("%20");
                case '<' -> b.append("%3C");
                case '>' -> b.append("%3E");
                case '|' -> b.append("%7C");
                case '`' -> b.append("%60");
                case '*' -> b.append("%2A");
                case '"' -> b.append("%22");
                default -> {
                    if (Character.isWhitespace(cp)) continue;
                    b.appendCodePoint(cp);
                }
            }
        }
        String u = b.toString().trim();
        if (u.length() > 2000 || !isWebUrl(u)) return "";
        return u;
    }
}
