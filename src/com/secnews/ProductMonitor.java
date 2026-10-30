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
 * 商品監控（v1.6／v1.7）：在購物網站搜尋每個監控項目的關鍵字，與上次的結果比較：
 *   新商品、低於價格上限、降價、補貨（只有提供庫存資訊的網站：momo、露天）。
 *
 * 支援網站（皆為網站本身使用的資料，非官方公開 API，網站改版時可能失效）：
 *   pchome：搜尋 JSON 介面
 *   momo  ：搜尋頁內嵌的 schema.org JSON-LD（ItemList）
 *   ruten ：露天搜尋 JSON 介面（取得商品 ID）＋商品資料 JSON 介面（名稱、價格、庫存）
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
        /** 網站代碼（pchome／momo／ruten）。 */
        String site = "pchome";
        /** 商品頁網址；null = 依網站樣板產生。 */
        String link;
        /** 有貨 true、缺貨 false、不知道 null。 */
        Boolean inStock;
        Product(String id, String name, int price, int originPrice) {
            this.id = id; this.name = name; this.price = price; this.originPrice = originPrice;
        }
        /** 狀態檔中的鍵：PChome 沿用舊格式（只有 ID），其他網站加前綴。 */
        String stateId() { return site.equals("pchome") ? id : site + ":" + id; }
    }

    static final String[] SITES = {"pchome", "momo", "ruten"};

    static String siteLabel(String site) {
        return switch (site) { case "momo" -> "momo"; case "ruten" -> "露天"; default -> "PChome"; };
    }

    static final String MOMO_SEARCH = "https://www.momoshop.com.tw/search/{q}?searchType=1&cateLevel=-1&curPage={page}";
    static final String MOMO_LINK = "https://www.momoshop.com.tw/goods/GoodsDetail.jsp?i_code={id}";
    static final String RUTEN_SEARCH = "https://rtapi.ruten.com.tw/api/search/v4/index.php/core/prod?q={q}&type=direct&sort=rnk%2Fdc&limit=60&offset={offset}";
    static final String RUTEN_PROD = "https://rtapi.ruten.com.tw/api/prod/v3/index.php/prod?id={ids}";
    static final String RUTEN_LINK = "https://www.ruten.com.tw/item/show?{id}";

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
            for (String site : ch.getProductSites()) {
            String baseKey = site.equals("pchome") ? w.name : w.name + "@" + site;
            String label = w.name + (ch.getProductSites().size() > 1 ? "（" + siteLabel(site) + "）" : "");
            try {
                List<Product> found = search(ch, w, site);
                boolean firstTime = baseline && !ch.hasProductBaseline(baseKey);
                int matched = 0, n0 = hits.size();
                Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
                for (Product p : found) {
                    String why = w.rejectReason(p.name, p.price);
                    if (why != null) { rejected.merge(why, 1, Integer::sum); continue; }
                    matched++;
                    String sid = p.stateId();
                    Integer last = ch.lastPrice(w.name, sid);
                    Boolean lastStock = ch.lastStock(w.name, sid);
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
                    if (Boolean.FALSE.equals(lastStock) && Boolean.TRUE.equals(p.inStock) && ch.isNotify("restock")) {
                        reasons.add("補貨");
                    }
                    ch.setPrice(w.name, sid, p.price);
                    ch.setStock(w.name, sid, p.inStock);
                    if (!reasons.isEmpty()) {
                        String priceText = "NT$" + fmt(p.price)
                                + (p.originPrice > p.price ? "（原價 NT$" + fmt(p.originPrice) + "）" : "")
                                + "｜" + siteLabel(site) + (Boolean.FALSE.equals(p.inStock) ? "（缺貨）" : "");
                        hits.add(new MonitorService.Hit(new FeedItem(w.name, p.name, linkOf(ch, p), priceText), reasons, ""));
                    }
                }
                ch.markProductBaseline(baseKey);
                okFail[0]++;
                StringBuilder why = new StringBuilder();
                for (Map.Entry<String, Integer> e : rejected.entrySet()) {
                    why.append(why.length() == 0 ? "（排除：" : "、").append(e.getKey()).append(" ").append(e.getValue()).append(" 件");
                }
                if (why.length() > 0) why.append("）");
                AppLog.info(tag + "  ✓ " + label + "：搜尋到 " + found.size() + " 件，符合條件 " + matched + " 件" + why
                        + (firstTime ? "；首次執行，已建立基準，只通知低於設定價格的商品" : "")
                        + "；通知 " + (hits.size() - n0) + " 件");
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                okFail[1]++;
                AppLog.error(tag + "  ✗ " + label, e);
            }
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

    /** 各網站最多抓幾頁搜尋結果（PChome 每頁 20 件、momo 30 件、露天 60 件）。 */
    static final int MAX_PAGES = 5, MOMO_PAGES = 3, RUTEN_PAGES = 2;
    /** 兩頁之間的間隔（毫秒）。 */
    static long pageDelayMs = 1000;

    static String enc(String q) {
        return URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20");
    }

    String linkOf(Channel ch, Product p) {
        if (p.link != null && !p.link.isEmpty()) return p.link;
        String id = URLEncoder.encode(p.id, StandardCharsets.UTF_8);
        return switch (p.site) {
            case "momo" -> ch.getProp("product.momo.linkUrl", MOMO_LINK).replace("{id}", id);
            case "ruten" -> ch.getProp("product.ruten.linkUrl", RUTEN_LINK).replace("{id}", id);
            default -> ch.getProductLinkUrl().replace("{id}", id);
        };
    }

    /** 相容舊呼叫：搜尋第一個設定的網站。 */
    List<Product> search(Channel ch, ProductWatch w) throws Exception {
        return search(ch, w, ch.getProductSites().isEmpty() ? "pchome" : ch.getProductSites().get(0));
    }

    /** 搜尋一個監控項目（「測試搜尋」也用這個）。 */
    List<Product> search(Channel ch, ProductWatch w, String site) throws Exception {
        return switch (site) {
            case "momo" -> searchMomo(ch, w);
            case "ruten" -> searchRuten(ch, w);
            default -> searchPchome(ch, w);
        };
    }

    /** 抓多頁，某頁少於 perPage 件或沒有新商品就停止。 */
    private interface PageFetcher { List<Product> fetch(int page) throws Exception; }

    private List<Product> paged(int maxPages, int perPage, PageFetcher f) throws Exception {
        List<Product> all = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int page = 1; page <= maxPages; page++) {
            if (page > 1) Thread.sleep(pageDelayMs);
            List<Product> got = f.fetch(page);
            int added = 0;
            for (Product p : got) if (ids.add(p.id)) { all.add(p); added++; }
            if (got.size() < perPage || added == 0) break;
        }
        return all;
    }

    private String get(String url, String label) throws Exception {
        checkRobots(url);
        return new String(fetcher.getWithRetry(url, label), StandardCharsets.UTF_8);
    }

    private List<Product> searchPchome(Channel ch, ProductWatch w) throws Exception {
        String tpl = ch.getProductSearchUrl().replace("{q}", enc(w.query));
        int pages = tpl.contains("{page}") ? MAX_PAGES : 1;
        return paged(pages, 20, page -> parse(get(tpl.replace("{page}", String.valueOf(page)), w.name)));
    }

    private List<Product> searchMomo(Channel ch, ProductWatch w) throws Exception {
        String tpl = ch.getProp("product.momo.searchUrl", MOMO_SEARCH).replace("{q}", enc(w.query));
        int pages = tpl.contains("{page}") ? MOMO_PAGES : 1;
        return paged(pages, 30, page -> parseMomo(get(tpl.replace("{page}", String.valueOf(page)), w.name)));
    }

    private List<Product> searchRuten(Channel ch, ProductWatch w) throws Exception {
        String tpl = ch.getProp("product.ruten.searchUrl", RUTEN_SEARCH).replace("{q}", enc(w.query));
        String prodTpl = ch.getProp("product.ruten.prodUrl", RUTEN_PROD);
        return paged(RUTEN_PAGES, 60, page -> {
            List<String> ids = parseRutenSearch(get(tpl.replace("{offset}", String.valueOf((page - 1) * 60 + 1)), w.name));
            if (ids.isEmpty()) return List.of();
            Thread.sleep(pageDelayMs);
            return parseRutenProds(get(prodTpl.replace("{ids}", String.join(",", ids)), w.name));
        });
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

    private static final java.util.regex.Pattern LD_JSON = java.util.regex.Pattern.compile(
            "(?is)<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>");

    /** momo：搜尋頁的 schema.org ItemList（JSON-LD）。 */
    static List<Product> parseMomo(String html) {
        java.util.regex.Matcher m = LD_JSON.matcher(html);
        boolean anyLd = false;
        while (m.find()) {
            anyLd = true;
            Object root;
            try { root = MiniJson.parse(m.group(1).trim()); } catch (IllegalArgumentException e) { continue; }
            List<?> items = findItemList(root, 0);
            if (items == null) continue;
            List<Product> out = new ArrayList<>();
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> it)) continue;
                if (it.get("item") instanceof Map<?, ?> inner) it = inner;      // ListItem 包 Product 的寫法
                String name = str(it.get("name"));
                String url = str(it.get("url"));
                Object offers = it.get("offers");
                if (offers instanceof List<?> l && !l.isEmpty()) offers = l.get(0);
                int price = 0;
                Boolean stock = null;
                if (offers instanceof Map<?, ?> of) {
                    price = price(of.get("price"));
                    if (price <= 0) price = price(of.get("lowPrice"));
                    String av = str(of.get("availability")).toLowerCase(Locale.ROOT);
                    if (av.endsWith("instock") || av.endsWith("limitedavailability")) stock = true;
                    else if (av.endsWith("outofstock") || av.endsWith("soldout") || av.endsWith("discontinued")) stock = false;
                }
                java.util.regex.Matcher ic = java.util.regex.Pattern.compile("[?&]i_code=([^&#]+)").matcher(url);
                String id = ic.find() ? ic.group(1) : url;
                if (id.isEmpty() || name.isEmpty() || price <= 0) continue;
                Product p = new Product(id, Fetcher.stripTags(name).trim(), price, 0);
                p.site = "momo";
                p.inStock = stock;
                out.add(p);
            }
            return out;
        }
        if (!anyLd) throw new IllegalArgumentException("momo 頁面沒有商品資料（JSON-LD），網站可能已改版或被阻擋");
        return new ArrayList<>();
    }

    private static List<?> findItemList(Object node, int depth) {
        if (depth > 6 || node == null) return null;
        if (node instanceof Map<?, ?> m) {
            if ("ItemList".equals(m.get("@type")) && m.get("itemListElement") instanceof List<?> l) return l;
            for (Object v : m.values()) {
                List<?> r = findItemList(v, depth + 1);
                if (r != null) return r;
            }
        } else if (node instanceof List<?> l) {
            for (Object v : l) {
                List<?> r = findItemList(v, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 露天：搜尋結果只有商品 ID。 */
    static List<String> parseRutenSearch(String json) {
        Object root = MiniJson.parse(json.strip());
        List<String> ids = new ArrayList<>();
        if (root instanceof Map<?, ?> m && m.get("Rows") instanceof List<?> rows) {
            for (Object r : rows) {
                if (r instanceof Map<?, ?> row) {
                    String id = str(row.get("Id"));
                    if (!id.isEmpty()) ids.add(id);
                }
            }
        } else {
            throw new IllegalArgumentException("露天搜尋回應格式不符（網站可能已改版）");
        }
        return ids;
    }

    /** 露天：商品資料（名稱、價格、庫存）。 */
    static List<Product> parseRutenProds(String json) {
        Object root = MiniJson.parse(json.strip());
        List<Product> out = new ArrayList<>();
        if (!(root instanceof List<?> l)) throw new IllegalArgumentException("露天商品回應格式不符（網站可能已改版）");
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String id = str(m.get("ProdId"));
            String name = str(m.get("ProdName"));
            int price = 0;
            if (m.get("PriceRange") instanceof List<?> pr && !pr.isEmpty()) price = price(pr.get(0));
            if (id.isEmpty() || name.isEmpty() || price <= 0) continue;
            Product p = new Product(id, Fetcher.stripTags(name).trim(), price, 0);
            p.site = "ruten";
            Object q = m.get("StockQty");
            if (q instanceof Double d) p.inStock = d > 0;
            out.add(p);
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
