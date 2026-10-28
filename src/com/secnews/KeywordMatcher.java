package com.secnews;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 關鍵字比對，不分大小寫。
 * 英數關鍵字採「完整單字」比對：Java 不會誤中 JavaScript；
 * 中文關鍵字則直接包含即可。
 */
public final class KeywordMatcher {
    private final List<String> keywords = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();

    public KeywordMatcher(List<String> kws) {
        for (String k : kws) {
            String kw = k.trim();
            if (kw.isEmpty()) continue;
            keywords.add(kw);
            String q = Pattern.quote(kw);
            String start = Character.isLetterOrDigit(kw.charAt(0)) && kw.charAt(0) < 128 ? "(?<![A-Za-z0-9])" : "";
            char last = kw.charAt(kw.length() - 1);
            String end = Character.isLetterOrDigit(last) && last < 128 ? "(?![A-Za-z0-9])" : "";
            patterns.add(Pattern.compile(start + q + end, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
    }

    public boolean isEmpty() { return keywords.isEmpty(); }

    /** 回傳命中的關鍵字（可能多個）；沒命中回傳空清單。 */
    public List<String> match(FeedItem item) {
        return matchText(item.title + "\n" + item.summary);
    }

    /** 從內文擷取第一個命中關鍵字前後各 radius 個字，作為推播時的參考片段。 */
    public String snippet(String text, String keyword, int radius) {
        int idx = keywords.indexOf(keyword);
        if (idx < 0 || text == null) return "";
        java.util.regex.Matcher m = patterns.get(idx).matcher(text);
        if (!m.find()) return "";
        int s = Math.max(0, m.start() - radius), e = Math.min(text.length(), m.end() + radius);
        return (s > 0 ? "…" : "") + text.substring(s, e).trim() + (e < text.length() ? "…" : "");
    }

    public List<String> matchText(String text) {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).matcher(text).find()) hits.add(keywords.get(i));
        }
        return hits;
    }
}
