package com.secnews;

/** 一個被監控的資安新聞來源（Source）。 */
public final class Site {
    public enum Type { RSS, HTML }

    public String name;
    public String url;
    public Type type;
    public boolean enabled;
    /** 標題／摘要沒命中時，是否抓文章內文再比對一次。 */
    public boolean fullText;

    public Site(String name, String url, Type type, boolean enabled) {
        this(name, url, type, enabled, false);
    }

    public Site(String name, String url, Type type, boolean enabled, boolean fullText) {
        this.name = name;
        this.url = url;
        this.type = type;
        this.enabled = enabled;
        this.fullText = fullText;
    }

    public Site copy() {
        return new Site(name, url, type, enabled, fullText);
    }

    /** 以 Tab 分隔的一行文字，供 sites.tsv 儲存。 */
    String toLine() {
        return clean(name) + "\t" + clean(url) + "\t" + type.name() + "\t" + enabled + "\t" + fullText;
    }

    /** 相容舊版（4 欄）與新版（5 欄）格式。 */
    static Site fromLine(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 4) return null;
        Type t;
        try { t = Type.valueOf(p[2].trim()); } catch (IllegalArgumentException e) { t = Type.RSS; }
        boolean ft = p.length >= 5 && Boolean.parseBoolean(p[4].trim());
        return new Site(p[0], p[1], t, Boolean.parseBoolean(p[3].trim()), ft);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
