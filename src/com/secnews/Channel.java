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
        props.stringPropertyNames().stream().filter(k -> k.startsWith("site.baseline."))
                .toList().forEach(props::remove);
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
