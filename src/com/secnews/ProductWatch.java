package com.secnews;

import java.util.Locale;

/**
 * 商品監控項目：在購物網站搜尋關鍵字，追蹤搜尋結果中的商品。
 * products.tsv 每行：名稱<TAB>搜尋關鍵字<TAB>名稱需包含<TAB>價格上限<TAB>啟用<TAB>排除字<TAB>最低價格
 * （v1.6.1 起加入後兩欄，舊檔案仍可讀取）
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
    /** 排除字（空白分隔，商品名稱出現任一個就排除，不分大小寫），例如「保護貼 保護殼 殼 膜」。 */
    public String exclude;
    /** 最低價格：低於此價格的商品不列入（用來排除便宜的配件）。0 = 不設定。 */
    public int minPrice;

    public ProductWatch(String name, String query, String mustContain, int maxPrice, boolean enabled) {
        this(name, query, mustContain, maxPrice, enabled, "", 0);
    }

    public ProductWatch(String name, String query, String mustContain, int maxPrice, boolean enabled,
                        String exclude, int minPrice) {
        this.name = clean(name);
        this.query = clean(query);
        this.mustContain = clean(mustContain);
        this.maxPrice = Math.max(0, maxPrice);
        this.enabled = enabled;
        this.exclude = clean(exclude);
        this.minPrice = Math.max(0, minPrice);
    }

    public ProductWatch copy() { return new ProductWatch(name, query, mustContain, maxPrice, enabled, exclude, minPrice); }

    /** 商品名稱是否包含所有「名稱需包含」的字，且不含任何「排除字」。 */
    public boolean accepts(String productName) {
        return rejectReason(productName, Integer.MAX_VALUE) == null;
    }

    /** 商品名稱與價格都符合條件。 */
    public boolean accepts(String productName, int price) {
        return rejectReason(productName, price) == null;
    }

    /** 不符合的原因（給「測試搜尋」顯示）；符合回傳 null。 */
    public String rejectReason(String productName, int price) {
        String n = productName.toLowerCase(Locale.ROOT);
        for (String w : mustContain.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty() && !n.contains(w)) return "缺少「" + w + "」";
        }
        for (String w : exclude.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty() && n.contains(w)) return "含排除字「" + w + "」";
        }
        if (minPrice > 0 && price < minPrice) return "低於最低價格";
        return null;
    }

    public String toLine() {
        return name + "\t" + query + "\t" + mustContain + "\t" + maxPrice + "\t" + enabled
                + "\t" + exclude + "\t" + minPrice;
    }

    public static ProductWatch fromLine(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 2 || p[1].isBlank()) return null;
        int max = 0;
        if (p.length > 3) {
            try { max = Integer.parseInt(p[3].trim()); } catch (NumberFormatException ignored) {}
        }
        boolean en = p.length <= 4 || !"false".equalsIgnoreCase(p[4].trim());
        int min = 0;
        if (p.length > 6) {
            try { min = Integer.parseInt(p[6].trim()); } catch (NumberFormatException ignored) {}
        }
        String name = p[0].isBlank() ? p[1] : p[0];
        return new ProductWatch(name, p[1], p.length > 2 ? p[2] : "", max, en, p.length > 5 ? p[5] : "", min);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
