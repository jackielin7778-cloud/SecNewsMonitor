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

    static RobotsTxt parse(String text) {
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
            if (key.equals("disallow") && !val.isEmpty()) r.disallow.add(val);
            else if (key.equals("allow") && !val.isEmpty()) r.allow.add(val);
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

    private static boolean matches(String rule, String path) {
        StringBuilder re = new StringBuilder("^");
        for (int k = 0; k < rule.length(); k++) {
            char c = rule.charAt(k);
            if (c == '*') re.append(".*");
            else if (c == '$' && k == rule.length() - 1) re.append('$');
            else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        if (!rule.endsWith("$")) re.append(".*");
        return path.matches(re.toString());
    }
}
