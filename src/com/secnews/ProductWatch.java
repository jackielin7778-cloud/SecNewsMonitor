package com.secnews;

import java.util.Locale;

/**
 * 商品監控項目：在購物網站搜尋關鍵字，追蹤搜尋結果中的商品。
 * products.tsv 每行：名稱<TAB>搜尋關鍵字<TAB>名稱需包含<TAB>價格上限<TAB>啟用
 */
public final class ProductWatch {
    public String name;
    /** 送到購物網站的搜尋關鍵字，例如「iPhone 17 Pro 256GB」。 */
    public String query;
    /** 商品名稱必須包含的字（空白分隔，全部都要出現，不分大小寫）；空白 = 不限。用來排除搜尋結果中的配件等雜訊。 */
    public String mustContain;
    /** 價格上限（新台幣）；商品價格 ≤ 此值時推播「低於設定價格」。0 = 不設定。 */
    public int maxPrice;
    public boolean enabled;

    public ProductWatch(String name, String query, String mustContain, int maxPrice, boolean enabled) {
        this.name = clean(name);
        this.query = clean(query);
        this.mustContain = clean(mustContain);
        this.maxPrice = Math.max(0, maxPrice);
        this.enabled = enabled;
    }

    public ProductWatch copy() { return new ProductWatch(name, query, mustContain, maxPrice, enabled); }

    /** 商品名稱是否包含所有「名稱需包含」的字。 */
    public boolean accepts(String productName) {
        if (mustContain.isEmpty()) return true;
        String n = productName.toLowerCase(Locale.ROOT);
        for (String w : mustContain.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty() && !n.contains(w)) return false;
        }
        return true;
    }

    public String toLine() {
        return name + "\t" + query + "\t" + mustContain + "\t" + maxPrice + "\t" + enabled;
    }

    public static ProductWatch fromLine(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 2 || p[1].isBlank()) return null;
        int max = 0;
        if (p.length > 3) {
            try { max = Integer.parseInt(p[3].trim()); } catch (NumberFormatException ignored) {}
        }
        boolean en = p.length <= 4 || !"false".equalsIgnoreCase(p[4].trim());
        String name = p[0].isBlank() ? p[1] : p[0];
        return new ProductWatch(name, p[1], p.length > 2 ? p[2] : "", max, en);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
