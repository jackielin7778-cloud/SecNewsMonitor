package com.secnews;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 監控頻道（v1.5）：每個頻道有自己的網站列表、關鍵字、排程、Google Chat Webhook 與已讀紀錄，
 * 例如「資安新聞」推到資安聊天室、「AI 新聞」推到 AI 聊天室。
 *
 * 檔案放在 資料夾/channels/頻道ID/：
 *   channel.properties  名稱、啟用、Webhook（加密）、推播設定、排程、各網站是否已建立基準
 *   sites.tsv           網站列表
 *   keywords.txt        關鍵字
 *   seen.txt            已讀紀錄（每行：網站名稱<TAB>鍵值）
 *
 * 同一則新聞在不同頻道各自去重，因此命中多個頻道時，每個頻道都會推播。
 */
public final class Channel {
    private final Store store;          // 取得登入後的加密金鑰
    private final String id;
    private final Path dir;
    private final Path propsFile, sitesFile, keywordsFile, seenFile;
    private Properties props = new Properties();
    private final Map<String, LinkedHashSet<String>> seenBySite = new LinkedHashMap<>();
    private final LinkedHashSet<String> legacySeen = new LinkedHashSet<>();

    Channel(Store store, String id, Path dir) throws IOException {
        this.store = store;
        this.id = id;
        this.dir = dir;
        Files.createDirectories(dir);
        propsFile = dir.resolve("channel.properties");
        sitesFile = dir.resolve("sites.tsv");
        keywordsFile = dir.resolve("keywords.txt");
        seenFile = dir.resolve("seen.txt");
        loadProps();
        loadSeen();
        if (!Files.exists(sitesFile)) saveSites(List.of());
        if (!Files.exists(keywordsFile)) saveKeywords(List.of());
    }

    public String id() { return id; }
    public Path dir() { return dir; }

    @Override public String toString() { return getName(); }

    // ---------- 基本資料 ----------
    public synchronized String getName() {
        return props.getProperty("name", id);
    }

    public synchronized void setName(String name) throws IOException {
        props.setProperty("name", name.trim());
        saveProps();
    }

    /** 頻道停用時不會自動排程執行（仍可手動執行）。 */
    public synchronized boolean isEnabled() {
        return Boolean.parseBoolean(props.getProperty("enabled", "true"));
    }

    public synchronized void setEnabled(boolean b) throws IOException {
        props.setProperty("enabled", String.valueOf(b));
        saveProps();
    }

    // ---------- 頻道類型 ----------
    /** NEWS：新聞網站＋關鍵字；PRODUCT：購物網站商品監控（v1.6，目前支援 PChome 24h）。 */
    public enum Type { NEWS, PRODUCT }

    public synchronized Type getType() {
        return "PRODUCT".equalsIgnoreCase(props.getProperty("type", "NEWS")) ? Type.PRODUCT : Type.NEWS;
    }

    public boolean isProduct() { return getType() == Type.PRODUCT; }

    synchronized void setType(Type t) throws IOException {
        props.setProperty("type", t.name());
        saveProps();
    }

    // ---------- 商品監控 ----------
    /** PChome 24h 搜尋介面（網站內部使用的 JSON 介面，非官方公開 API；網站改版時可在 channel.properties 修改）。 */
    public static final String DEFAULT_PRODUCT_SEARCH_URL =
            "https://ecshweb.pchome.com.tw/search/v3.3/all/results?q={q}&page={page}&sort=rnk/dc";
    public static final String DEFAULT_PRODUCT_LINK_URL = "https://24h.pchome.com.tw/prod/{id}";

    public synchronized String getProductSearchUrl() {
        return props.getProperty("product.searchUrl", DEFAULT_PRODUCT_SEARCH_URL);
    }

    public synchronized String getProductLinkUrl() {
        return props.getProperty("product.linkUrl", DEFAULT_PRODUCT_LINK_URL);
    }

    /** 通知條件：new（新商品）、below（低於價格上限）、drop（降價）。 */
    public synchronized boolean isNotify(String kind) {
        return Boolean.parseBoolean(props.getProperty("product.notify." + kind, "true"));
    }

    public synchronized void setNotify(String kind, boolean on) throws IOException {
        props.setProperty("product.notify." + kind, String.valueOf(on));
        saveProps();
    }

    public synchronized List<ProductWatch> loadProducts() throws IOException {
        List<ProductWatch> list = new ArrayList<>();
        Path f = dir.resolve("products.tsv");
        if (!Files.exists(f)) return list;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            ProductWatch w = ProductWatch.fromLine(line);
            if (w != null) list.add(w);
        }
        return list;
    }

    public synchronized void saveProducts(List<ProductWatch> list) throws IOException {
        StringBuilder sb = new StringBuilder("# name\tquery\tmustContain\tmaxPrice(0=none)\tenabled\n");
        for (ProductWatch w : list) sb.append(w.toLine()).append('\n');
        Store.writeAtomic(dir.resolve("products.tsv"), sb.toString());
    }

    /** 商品上次看到的價格：鍵為「監控名稱<TAB>商品ID」。 */
    private Map<String, Integer> productPrices;
    /** 上次看到的庫存狀態（只有提供庫存資訊的網站才有）：true = 有貨。 */
    private final Map<String, Boolean> productStock = new java.util.HashMap<>();

    public synchronized Boolean lastStock(String watch, String productId) throws IOException {
        prices();
        return productStock.get(norm(watch) + "\t" + norm(productId));
    }

    public synchronized void setStock(String watch, String productId, Boolean inStock) throws IOException {
        prices();
        String k = norm(watch) + "\t" + norm(productId);
        if (inStock == null) productStock.remove(k); else productStock.put(k, inStock);
    }

    /** 讀取頻道設定（給商品監控各網站的網址樣板用，可在 channel.properties 覆寫）。 */
    public synchronized String getProp(String key, String def) {
        return props.getProperty(key, def);
    }

    /** 商品頻道要搜尋的網站（pchome、momo、ruten），逗號分隔；預設只有 PChome。 */
    public synchronized List<String> getProductSites() {
        List<String> l = new ArrayList<>();
        for (String x : props.getProperty("product.sites", "pchome").split(",")) {
            x = x.trim().toLowerCase(java.util.Locale.ROOT);
            if (!x.isEmpty() && !l.contains(x)) l.add(x);
        }
        return l;
    }

    public synchronized void setProductSites(List<String> sites) throws IOException {
        props.setProperty("product.sites", String.join(",", sites));
        saveProps();
    }

    private Map<String, Integer> prices() throws IOException {
        if (productPrices != null) return productPrices;
        productPrices = new LinkedHashMap<>();
        Path f = dir.resolve("product-state.tsv");
        if (Files.exists(f)) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                try { productPrices.put(p[0] + "\t" + p[1], Integer.parseInt(p[2])); } catch (NumberFormatException ignored) {}
                if (p.length > 3 && !p[3].isEmpty()) productStock.put(p[0] + "\t" + p[1], "1".equals(p[3]));
            }
        }
        return productPrices;
    }

    /** 回傳上次價格；沒看過回傳 null。 */
    public synchronized Integer lastPrice(String watch, String productId) throws IOException {
        return prices().get(norm(watch) + "\t" + norm(productId));
    }

    public synchronized void setPrice(String watch, String productId, int price) throws IOException {
        Map<String, Integer> m = prices();
        String k = norm(watch) + "\t" + norm(productId);
        m.remove(k);
        m.put(k, price);       // 移到最後 = 最近
    }

    /** 每個監控項目最多保留最近 RECENT_EXTRA 個商品；刪除已不存在的監控項目。 */
    public synchronized void pruneProducts(java.util.Collection<String> watchNames) throws IOException {
        Map<String, Integer> m = prices();
        Set<String> keep = new HashSet<>();
        for (String w : watchNames) keep.add(norm(w));
        Map<String, Integer> count = new java.util.HashMap<>();
        List<String> keys = new ArrayList<>(m.keySet());
        java.util.Collections.reverse(keys);       // 由新到舊
        for (String k : keys) {
            String w = k.substring(0, k.indexOf('\t'));
            int c = count.merge(w, 1, Integer::sum);
            if (!keep.contains(w) || c > RECENT_EXTRA) { m.remove(k); productStock.remove(k); }
        }
    }

    public synchronized boolean hasProductBaseline(String watch) {
        return props.getProperty("product.baseline." + norm(watch)) != null;
    }

    public synchronized void markProductBaseline(String watch) {
        props.setProperty("product.baseline." + norm(watch), "1");
    }

    public synchronized void flushProducts() throws IOException {
        if (productPrices != null) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : productPrices.entrySet()) {
                Boolean st = productStock.get(e.getKey());
                sb.append(e.getKey()).append('\t').append(e.getValue())
                  .append('\t').append(st == null ? "" : st ? "1" : "0").append('\n');
            }
            Store.writeAtomic(dir.resolve("product-state.tsv"), sb.toString());
        }
        saveProps();
    }

    public synchronized int productStateCount() throws IOException { return prices().size(); }

    // ---------- Google Chat ----------
    public synchronized String getWebhookUrl() {
        javax.crypto.SecretKey k = store.sessionKey();
        if (k == null) return "";
        return Crypto.decrypt(k, props.getProperty("chat.webhook.enc", ""));
    }

    public synchronized void setWebhookUrl(String url) throws IOException {
        javax.crypto.SecretKey k = store.sessionKey();
        if (k == null) throw new IllegalStateException("尚未登入");
        props.setProperty("chat.webhook.enc", Crypto.encrypt(k, url == null ? "" : url.trim()));
        saveProps();
    }

    /** 是否已設定 Webhook（不需登入即可判斷，不解密）。 */
    public synchronized boolean hasWebhook() {
        return !props.getProperty("chat.webhook.enc", "").isEmpty();
    }

    /** 忘記密碼重設帳號時：舊金鑰加密的 Webhook 已無法解密，直接清除。 */
    synchronized void clearWebhook() throws IOException {
        props.remove("chat.webhook.enc");
        saveProps();
    }

    public synchronized boolean isChatEnabled() {
        return Boolean.parseBoolean(props.getProperty("chat.enabled", "true"));
    }

    public synchronized void setChatEnabled(boolean b) throws IOException {
        props.setProperty("chat.enabled", String.valueOf(b));
        saveProps();
    }

    public synchronized int getMaxPushPerRun() {
        try {
            return Math.max(1, Integer.parseInt(props.getProperty("chat.maxPerRun", String.valueOf(Store.DEFAULT_MAX_PER_RUN))));
        } catch (NumberFormatException e) {
            return Store.DEFAULT_MAX_PER_RUN;
        }
    }

    public synchronized void setMaxPushPerRun(int n) throws IOException {
        props.setProperty("chat.maxPerRun", String.valueOf(Math.max(1, n)));
        saveProps();
    }

    // ---------- 排程 ----------
    public synchronized ScheduleConfig getSchedule() {
        return ScheduleConfig.load(props);
    }

    public synchronized void setSchedule(ScheduleConfig s) throws IOException {
        s.save(props);
        saveProps();
    }

    // ---------- 網站 ----------
    public synchronized List<Site> loadSites() throws IOException {
        List<Site> list = new ArrayList<>();
        for (String line : Files.readAllLines(sitesFile, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            Site s = Site.fromLine(line);
            if (s != null) list.add(s);
        }
        return list;
    }

    public synchronized void saveSites(List<Site> sites) throws IOException {
        StringBuilder sb = new StringBuilder("# name\turl\ttype(RSS|HTML)\tenabled\tfullText\n");
        for (Site s : sites) sb.append(s.toLine()).append('\n');
        Store.writeAtomic(sitesFile, sb.toString());
    }

    // ---------- 關鍵字 ----------
    public synchronized List<String> loadKeywords() throws IOException {
        List<String> list = new ArrayList<>();
        for (String line : Files.readAllLines(keywordsFile, StandardCharsets.UTF_8)) {
            String k = line.trim();
            if (!k.isEmpty() && !list.contains(k)) list.add(k);
        }
        return list;
    }

    public synchronized void saveKeywords(List<String> keywords) throws IOException {
        Store.writeAtomic(keywordsFile, keywords.isEmpty() ? "" : String.join("\n", keywords) + "\n");
    }

    // ---------- 已讀紀錄（去重） ----------
    static final int RECENT_EXTRA = Store.RECENT_EXTRA;

    private static String norm(String key) {
        return key == null ? "" : key.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public synchronized boolean isSeen(String site, String key) {
        String k = norm(key);
        LinkedHashSet<String> s = seenBySite.get(site);
        return (s != null && s.contains(k)) || legacySeen.contains(k);
    }

    public synchronized void markSeen(String site, String key) {
        String k = norm(key);
        LinkedHashSet<String> s = seenBySite.computeIfAbsent(site, x -> new LinkedHashSet<>());
        s.remove(k);
        s.add(k);
    }

    public synchronized void retainSeen(String site, Collection<String> currentKeys) {
        LinkedHashSet<String> s = seenBySite.get(site);
        if (s == null) return;
        Set<String> current = new HashSet<>();
        for (String k : currentKeys) current.add(norm(k));
        List<String> others = new ArrayList<>();
        for (String k : s) if (!current.contains(k)) others.add(k);
        int drop = Math.max(0, others.size() - RECENT_EXTRA);
        for (int i = 0; i < drop; i++) s.remove(others.get(i));
    }

    public synchronized void dropLegacySeen(Collection<String> notFetchedSites) {
        legacySeen.clear();
        for (String s : notFetchedSites) props.remove("site.baseline." + s);
    }

    public synchronized boolean hasLegacySeen() { return !legacySeen.isEmpty(); }

    public synchronized void pruneSeenSites(Collection<String> existingSiteNames) {
        seenBySite.keySet().retainAll(new HashSet<>(existingSiteNames));
    }

    public synchronized int seenCount() {
        int n = legacySeen.size();
        for (LinkedHashSet<String> s : seenBySite.values()) n += s.size();
        return n;
    }

    synchronized void reloadSeen() throws IOException {
        legacySeen.clear();
        seenBySite.clear();
        loadSeen();
    }

    private void loadSeen() throws IOException {
        if (!Files.exists(seenFile)) return;
        for (String line : Files.readAllLines(seenFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) legacySeen.add(line);
            else seenBySite.computeIfAbsent(line.substring(0, tab), x -> new LinkedHashSet<>())
                    .add(line.substring(tab + 1));
        }
    }

    public synchronized boolean hasSeenAnyFrom(String siteName) {
        return props.getProperty("site.baseline." + siteName) != null;
    }

    public synchronized void markSiteBaselined(String siteName) {
        props.setProperty("site.baseline." + siteName, "1");
    }

    public synchronized void flushSeen() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String k : legacySeen) sb.append(k).append('\n');
        for (Map.Entry<String, LinkedHashSet<String>> e : seenBySite.entrySet()) {
            for (String k : e.getValue()) sb.append(e.getKey()).append('\t').append(k).append('\n');
        }
        Store.writeAtomic(seenFile, sb.toString());
        saveProps();
    }

    public synchronized void clearSeen() throws IOException {
        legacySeen.clear();
        seenBySite.clear();
        props.stringPropertyNames().stream()
                .filter(k -> k.startsWith("site.baseline.") || k.startsWith("product.baseline."))
                .toList().forEach(props::remove);
        productPrices = new LinkedHashMap<>();
        productStock.clear();
        Store.writeAtomic(dir.resolve("product-state.tsv"), "");
        flushSeen();
    }

    // ---------- 檔案 ----------
    /** 給 Store 轉移舊版設定用。 */
    synchronized Properties props() { return props; }

    synchronized void reloadProps() throws IOException { loadProps(); }

    private void loadProps() throws IOException {
        props = new Properties();
        if (Files.exists(propsFile)) {
            try (Reader r = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
    }

    synchronized void saveProps() throws IOException {
        Path tmp = propsFile.resolveSibling(propsFile.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(w, "SecNewsMonitor channel");
        }
        Store.move(tmp, propsFile);
    }
}
