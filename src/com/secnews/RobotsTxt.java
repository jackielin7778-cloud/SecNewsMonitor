package com.secnews;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * robots.txt 簡易判斷（只看 User-agent: * 的群組，Allow／Disallow 以最長前綴為準，支援 * 與 $）。
 * 商品監控在每次抓取前檢查，網站不允許時就不抓取。
 */
final class RobotsTxt {
    private final List<String> allow = new ArrayList<>();
    private final List<String> disallow = new ArrayList<>();

    /** 只讀取前 MAX_TEXT_CHARS 個字元、最多 MAX_RULES 條規則、每條最多 MAX_RULE_CHARS 字（v1.8.0）。 */
    static final int MAX_TEXT_CHARS = 512 * 1024, MAX_RULES = 2000, MAX_RULE_CHARS = 1000;

    static RobotsTxt parse(String text) {
        if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);
        RobotsTxt r = new RobotsTxt();
        boolean inStar = false, lastWasAgent = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (!lastWasAgent) inStar = false;     // 新群組開始
                if (val.equals("*")) inStar = true;
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            if (!inStar) continue;
            if (val.isEmpty() || val.length() > MAX_RULE_CHARS) continue;            // 過長的規則略過
            if (r.allow.size() + r.disallow.size() >= MAX_RULES) break;               // 規則數上限
            val = val.replaceAll("\\*{2,}", "*");                                     // 連續的 * 合併
            if (key.equals("disallow")) r.disallow.add(val);
            else if (key.equals("allow")) r.allow.add(val);
        }
        return r;
    }

    /** path 含查詢字串，例如 /search/v3.3/all/results?q=x */
    boolean allowed(String path) {
        int best = -1;
        boolean result = true;
        for (String d : disallow) {
            if (matches(d, path) && d.length() > best) { best = d.length(); result = false; }
        }
        for (String a : allow) {
            if (matches(a, path) && a.length() >= best) { best = a.length(); result = true; }
        }
        return result;
    }

    /**
     * 萬用字元比對（v1.8.0 安全修正）：原本把 * 轉成 regex 的 .*，外部 robots.txt 可用多個 * 造成指數級回溯，
     * 讓監控卡住（7 個 * 約 11 秒、8 個超過 60 秒）。改用不回溯的貪婪比對，最壞 O(規則長度 × 網址長度)。
     */
    static boolean matches(String rule, String path) {
        boolean anchored = rule.endsWith("$");
        String pat = anchored ? rule.substring(0, rule.length() - 1) : rule + "*";
        int p = 0, s = 0, star = -1, mark = 0, m = pat.length(), n = path.length();
        while (s < n) {
            if (p < m && pat.charAt(p) == '*') { star = p++; mark = s; }
            else if (p < m && pat.charAt(p) == path.charAt(s)) { p++; s++; }
            else if (star >= 0) { p = star + 1; s = ++mark; }
            else return false;
        }
        while (p < m && pat.charAt(p) == '*') p++;
        return p == m;
    }
}
