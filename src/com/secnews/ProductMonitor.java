package com.secnews;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商品監控（v1.6）：在 PChome 24h 搜尋每個監控項目的關鍵字，與上次的結果比較：
 *   新商品（之前沒看過）、低於價格上限（從高於變成低於，或第一次看到就低於）、降價（比上次便宜）。
 *
 * 使用的是 PChome 網站本身使用的搜尋 JSON 介面（非官方公開 API），網站改版時可能失效；
 * 每次抓取前先檢查 robots.txt，網站不允許時不抓取。
 */
final class ProductMonitor {
    /** 兩個監控項目之間的間隔（毫秒），避免對網站造成負擔。 */
    static long delayMs = 2000;

    private final Fetcher fetcher;
    private final Map<String, RobotsTxt> robotsCache = new HashMap<>();

    ProductMonitor(Fetcher fetcher) { this.fetcher = fetcher; }

    /** 搜尋結果中的一個商品。 */
    static final class Product {
        final String id, name;
        final int price, originPrice;
        Product(String id, String name, int price, int originPrice) {
            this.id = id; this.name = name; this.price = price; this.originPrice = originPrice;
        }
    }

    List<MonitorService.Hit> run(Channel ch, String tag, boolean baseline, int[] okFail) throws InterruptedException {
        List<MonitorService.Hit> hits = new ArrayList<>();
        List<ProductWatch> watches;
        try {
            watches = ch.loadProducts();
        } catch (Exception e) {
            AppLog.error(tag + "讀取商品清單失敗", e);
            return hits;
        }
        robotsCache.clear();
        boolean first = true;
        List<String> names = new ArrayList<>();
        for (ProductWatch w : watches) {
            names.add(w.name);
            if (!w.enabled) continue;
            if (!first) Thread.sleep(delayMs);
            first = false;
            try {
                List<Product> found = search(ch, w);
                boolean firstTime = baseline && !ch.hasProductBaseline(w.name);
                int matched = 0, n0 = hits.size();
                for (Product p : found) {
                    if (!w.accepts(p.name, p.price)) continue;
                    matched++;
                    Integer last = ch.lastPrice(w.name, p.id);
                    List<String> reasons = new ArrayList<>();
                    if (last == null) {
                        if (!firstTime && ch.isNotify("new")) reasons.add("新商品");
                    } else if (p.price < last && ch.isNotify("drop")) {
                        reasons.add("降價 NT$" + fmt(last) + " → NT$" + fmt(p.price));
                    }
                    if (w.maxPrice > 0 && p.price <= w.maxPrice && ch.isNotify("below")
                            && (last == null || last > w.maxPrice)) {
                        reasons.add("低於設定價格 NT$" + fmt(w.maxPrice));
                    }
                    ch.setPrice(w.name, p.id, p.price);
                    if (!reasons.isEmpty()) {
                        String priceText = "NT$" + fmt(p.price)
                                + (p.originPrice > p.price ? "（原價 NT$" + fmt(p.originPrice) + "）" : "");
                        String link = ch.getProductLinkUrl().replace("{id}", URLEncoder.encode(p.id, StandardCharsets.UTF_8));
                        hits.add(new MonitorService.Hit(new FeedItem(w.name, p.name, link, priceText), reasons, ""));
                    }
                }
                ch.markProductBaseline(w.name);
                okFail[0]++;
                AppLog.info(tag + "  ✓ " + w.name + "：搜尋到 " + found.size() + " 件，符合名稱條件 " + matched + " 件"
                        + (firstTime ? "（首次執行，已建立基準，只通知低於設定價格的商品）" : "")
                        + "，通知 " + (hits.size() - n0) + " 件");
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                okFail[1]++;
                AppLog.error(tag + "  ✗ " + w.name, e);
            }
        }
        try {
            ch.pruneProducts(names);
            ch.flushProducts();
        } catch (Exception e) {
            AppLog.error(tag + "儲存商品紀錄失敗", e);
        }
        return hits;
    }

    /** 搜尋一個監控項目（「測試搜尋」也用這個）。 */
    List<Product> search(Channel ch, ProductWatch w) throws Exception {
        String url = ch.getProductSearchUrl().replace("{q}", URLEncoder.encode(w.query, StandardCharsets.UTF_8).replace("+", "%20"));
        checkRobots(url);
        byte[] body = fetcher.getWithRetry(url, w.name);
        return parse(new String(body, StandardCharsets.UTF_8));
    }

    private void checkRobots(String url) throws Exception {
        URI u = URI.create(url);
        String origin = u.getScheme() + "://" + u.getRawAuthority();
        RobotsTxt r = robotsCache.get(origin);
        if (r == null && !robotsCache.containsKey(origin)) {
            try {
                r = RobotsTxt.parse(new String(fetcher.get(origin + "/robots.txt"), StandardCharsets.UTF_8));
            } catch (Fetcher.HttpStatusException e) {
                r = null;                       // 沒有 robots.txt（404 等）：不限制
            }
            robotsCache.put(origin, r);
        }
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        if (r != null && !r.allowed(path)) {
            throw new IllegalStateException("網站的 robots.txt 不允許自動抓取 " + u.getRawPath() + "，已略過");
        }
    }

    // ------------------------------------------------------------------ JSON

    /** 解析搜尋結果：找出第一個「元素含商品 ID、名稱、價格」的陣列。容許 JSONP 包裝。 */
    static List<Product> parse(String text) {
        Object root = null;
        boolean parsed = false;
        String t = text.strip();
        try {
            root = MiniJson.parse(t);
            parsed = true;
        } catch (IllegalArgumentException e) {
            // JSONP 等包裝：從每個 { 或 [ 嘗試解析，最多 20 次
            int from = 0;
            for (int n = 0; n < 20 && !parsed; n++) {
                int a = firstIndex(t, '{', '[', from);
                if (a < 0) break;
                try {
                    root = MiniJson.parseAt(t, a);
                    parsed = true;
                } catch (IllegalArgumentException ignored) {
                    from = a + 1;
                }
            }
        }
        if (!parsed) throw new IllegalArgumentException("回應不是 JSON（網站可能已改版或暫時無法使用）");
        List<Product> out = new ArrayList<>();
        List<?> arr = findProducts(root, 0);
        if (arr == null) return out;                  // 沒有搜尋結果
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String id = str(get(m, "id", "Id", "prodId", "ProdId"));
            String name = str(get(m, "name", "Name", "prodName"));
            int price = price(get(m, "price", "Price"));
            int orig = price(get(m, "originPrice", "OriginPrice"));
            if (id.isEmpty() || name.isEmpty() || price <= 0) continue;
            out.add(new Product(id, Fetcher.stripTags(name).trim(), price, orig));
        }
        return out;
    }

    private static int firstIndex(String s, char x, char y, int from) {
        int i = s.indexOf(x, from), j = s.indexOf(y, from);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    private static List<?> findProducts(Object node, int depth) {
        if (depth > 8 || node == null) return null;
        if (node instanceof Map<?, ?> m) {
            // 優先找名為 prods／products／items 的陣列
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                if ((k.equals("prods") || k.equals("products") || k.equals("items")) && e.getValue() instanceof List<?> l) {
                    return l;
                }
            }
            for (Object v : m.values()) {
                List<?> r = findProducts(v, depth + 1);
                if (r != null) return r;
            }
        } else if (node instanceof List<?> l) {
            if (!l.isEmpty() && l.get(0) instanceof Map<?, ?> first
                    && get(first, "id", "Id") != null && get(first, "name", "Name") != null) return l;
            for (Object v : l) {
                List<?> r = findProducts(v, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static Object get(Map<?, ?> m, String... keys) {
        for (String k : keys) if (m.containsKey(k)) return m.get(k);
        return null;
    }

    private static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(o).trim();
    }

    /** 價格可能是數字、字串，或 {"P": 11900, "M": ...} 這類物件（取 P）。 */
    private static int price(Object o) {
        if (o instanceof Double d) return (int) Math.round(d);
        if (o instanceof String s) {
            try { return (int) Math.round(Double.parseDouble(s.replace(",", "").trim())); } catch (NumberFormatException e) { return 0; }
        }
        if (o instanceof Map<?, ?> m) return price(get(m, "P", "p", "price"));
        return 0;
    }

    static String fmt(int n) { return String.format(Locale.ROOT, "%,d", n); }
}
