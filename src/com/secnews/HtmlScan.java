package com.secnews;

import java.util.ArrayList;
import java.util.List;

/**
 * 線性時間的 HTML 掃描工具（v1.7.1，取代原本的正規表示式）。
 *
 * 原本用 {@code <a ...>(.*?)</a>}、{@code <script>.*?</script>} 這類 regex，遇到大量「沒有結尾」的標籤時，
 * 每個開頭都會往後掃到檔尾，時間複雜度變成 O(n²)，惡意網頁可讓監控卡住數十秒以上（ReDoS／演算法複雜度 DoS）。
 * 這裡的每個函式都只往前走、不回頭；某種結尾標籤一旦找不到，就記住「後面都沒有」，不再重複掃描。
 */
final class HtmlScan {
    private HtmlScan() {}

    /** 不分大小寫（只處理 ASCII）找 needle（needle 須為小寫）。 */
    static int indexOfIgnoreCase(String s, String needle, int from) {
        int n = s.length(), m = needle.length();
        char first = needle.charAt(0);
        for (int i = Math.max(0, from); i <= n - m; i++) {
            if (lower(s.charAt(i)) != first) continue;
            int k = 1;
            while (k < m && lower(s.charAt(i + k)) == needle.charAt(k)) k++;
            if (k == m) return i;
        }
        return -1;
    }

    static char lower(char c) { return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c; }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ':';
    }

    // ------------------------------------------------------------------ 去除標籤與實體

    /** 去除 HTML 標籤、解碼常見實體（只解碼一次）、合併空白。 */
    static String stripTags(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        boolean noMoreGt = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '<' && !noMoreGt) {
                int gt = s.indexOf('>', i + 1);
                if (gt < 0) { noMoreGt = true; out.append(c); i++; continue; }
                out.append(' ');
                i = gt + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return collapse(decodeEntities(out.toString()));
    }

    /** 解碼 &amp;amp; &amp;lt; &amp;gt; &amp;quot; &amp;#39; &amp;nbsp; 與數字實體；只掃一次，不會二次解碼。 */
    static String decodeEntities(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        out.append(s, 0, amp);
        int i = amp, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') { out.append(c); i++; continue; }
            int semi = -1;
            for (int k = i + 1; k < n && k <= i + 10; k++) {
                if (s.charAt(k) == ';') { semi = k; break; }
            }
            if (semi < 0) { out.append(c); i++; continue; }
            String name = s.substring(i + 1, semi);
            String rep = entity(name);
            if (rep == null) { out.append(c); i++; continue; }
            out.append(rep);
            i = semi + 1;
        }
        return out.toString();
    }

    private static String entity(String name) {
        switch (name) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": case "#39": return "'";
            case "nbsp": return " ";
            default: break;
        }
        if (name.length() > 1 && name.charAt(0) == '#') {
            try {
                int cp = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                        ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                if (cp > 0 && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) return new String(Character.toChars(cp));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /** 連續空白（含換行、Tab）合併成一個空格並去頭尾。 */
    static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ' ') { space = true; continue; }
            if (space && out.length() > 0) out.append(' ');
            space = false;
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 全文：去除註解與雜訊區塊

    /** 移除 &lt;!-- --&gt; 註解（沒有結尾的註解：其後全部移除，與瀏覽器行為一致）。 */
    static String removeComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (true) {
            int a = s.indexOf("<!--", i);
            if (a < 0) { out.append(s, i, s.length()); break; }
            out.append(s, i, a).append(' ');
            int b = s.indexOf("-->", a + 4);
            if (b < 0) break;
            i = b + 3;
        }
        return out.toString();
    }

    private static final String[] NOISE = {"script", "style", "noscript", "svg", "head", "nav", "footer", "header", "aside", "form"};

    /** 移除 script、style、nav、footer 等區塊（含內容）。沒有結尾標籤的開頭標籤保留，之後再當一般標籤去除。 */
    static String removeNoiseBlocks(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean[] noClose = new boolean[NOISE.length];
        int i = 0, n = s.length();
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) { out.append(s, i, n); break; }
            out.append(s, i, lt);
            int tag = noiseTagAt(s, lt + 1);
            if (tag < 0 || noClose[tag]) { out.append('<'); i = lt + 1; continue; }
            int end = closingTagEnd(s, NOISE[tag], lt + 1 + NOISE[tag].length());
            if (end < 0) { noClose[tag] = true; out.append('<'); i = lt + 1; continue; }
            out.append(' ');
            i = end;
        }
        return out.toString();
    }

    private static int noiseTagAt(String s, int p) {
        for (int t = 0; t < NOISE.length; t++) {
            String name = NOISE[t];
            int e = p + name.length();
            if (e > s.length() || !s.regionMatches(true, p, name, 0, name.length())) continue;
            if (e < s.length() && isNameChar(s.charAt(e))) continue;
            return t;
        }
        return -1;
    }

    /** 從 from 開始找 &lt;/name 空白* &gt;，回傳結尾之後的位置；找不到回傳 -1。 */
    static int closingTagEnd(String s, String name, int from) {
        String needle = "</" + name;
        int p = from;
        while (true) {
            int a = indexOfIgnoreCase(s, needle, p);
            if (a < 0) return -1;
            int k = a + needle.length();
            while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
            if (k < s.length() && s.charAt(k) == '>') return k + 1;
            p = a + needle.length();
        }
    }

    // ------------------------------------------------------------------ 連結

    /** 一個 &lt;a href&gt; 連結：href 原始值與連結文字（尚未去標籤）。 */
    record Anchor(String href, String inner) {}

    /** 找出所有 &lt;a ... href="..."&gt;...&lt;/a&gt;。 */
    static List<Anchor> anchors(String s) {
        List<Anchor> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0 || lt + 2 >= n) break;
            char c1 = s.charAt(lt + 1), c2 = s.charAt(lt + 2);
            if ((c1 != 'a' && c1 != 'A') || !Character.isWhitespace(c2)) { i = lt + 1; continue; }
            int gt = s.indexOf('>', lt + 2);
            if (gt < 0) break;                                   // 後面已經沒有 >
            String href = attr(s.substring(lt + 2, gt), "href");
            int close = indexOfIgnoreCase(s, "</a>", gt + 1);
            if (close < 0) break;                                // 後面已經沒有 </a>
            if (href != null) out.add(new Anchor(href, s.substring(gt + 1, close)));
            i = close + 4;
        }
        return out;
    }

    /** 從標籤屬性字串取出指定屬性的值（需有引號）；沒有回傳 null。 */
    static String attr(String tagBody, String name) {
        int p = 0;
        while (true) {
            int a = indexOfIgnoreCase(tagBody, name, p);
            if (a < 0) return null;
            p = a + name.length();
            if (a > 0 && isNameChar(tagBody.charAt(a - 1))) continue;
            int k = p;
            while (k < tagBody.length() && Character.isWhitespace(tagBody.charAt(k))) k++;
            if (k >= tagBody.length() || tagBody.charAt(k) != '=') continue;
            k++;
            while (k < tagBody.length() && Character.isWhitespace(tagBody.charAt(k))) k++;
            if (k >= tagBody.length()) return null;
            char q = tagBody.charAt(k);
            if (q != '"' && q != '\'') continue;
            int e = tagBody.indexOf(q, k + 1);
            if (e < 0) return null;
            return tagBody.substring(k + 1, e);
        }
    }

    // ------------------------------------------------------------------ JSON-LD

    /** 取出所有 &lt;script type="application/ld+json"&gt; 的內容。 */
    static List<String> ldJsonBlocks(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int a = indexOfIgnoreCase(s, "<script", i);
            if (a < 0) break;
            int gt = s.indexOf('>', a + 7);
            if (gt < 0) break;
            int close = indexOfIgnoreCase(s, "</script", gt + 1);
            if (close < 0) break;
            String type = attr(s.substring(a + 7, gt), "type");
            if (type != null && type.trim().equalsIgnoreCase("application/ld+json")) out.add(s.substring(gt + 1, close));
            i = close + 8;
        }
        return out;
    }
}
