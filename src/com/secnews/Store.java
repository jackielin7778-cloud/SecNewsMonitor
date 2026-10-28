package com.secnews;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 本機資料儲存。全部是純文字檔，放在使用者自己的資料夾：
 *   macOS   : ~/Library/Application Support/SecNewsMonitor
 *   Windows : %APPDATA%\SecNewsMonitor
 *   其他    : ~/.secnewsmonitor
 * 可用 -Dsecnews.home=路徑 覆寫。
 */
public final class Store {

    private final Path dir;
    private final Path configFile, sitesFile, keywordsFile, seenFile, logsDir;
    private Properties config = new Properties();
    private SecretKey sessionKey;              // 登入成功後才有，用來加解密 Webhook
    private final java.util.Map<String, LinkedHashSet<String>> seenBySite = new java.util.LinkedHashMap<>();
    private final LinkedHashSet<String> legacySeen = new LinkedHashSet<>();

    public Store() throws IOException {
        this.dir = resolveDir();
        Files.createDirectories(dir);
        restrictPermissions(dir);
        configFile = dir.resolve("config.properties");
        sitesFile = dir.resolve("sites.tsv");
        keywordsFile = dir.resolve("keywords.txt");
        seenFile = dir.resolve("seen.txt");
        logsDir = dir.resolve("logs");
        loadConfig();
        loadSeen();
        if (!Files.exists(sitesFile)) saveSites(DefaultSites.list());
        if (!Files.exists(keywordsFile)) saveKeywords(List.of("Java", "Tomcat", "OpenJDK"));
        migrateLegacyLog();
    }

    /** v1.0 的單一 monitor.log 搬到 logs/，依最後修改日期命名，之後就會套用保留天數自動刪除。 */
    private void migrateLegacyLog() {
        Path legacy = dir.resolve("monitor.log");
        if (!Files.exists(legacy)) return;
        try {
            Files.createDirectories(logsDir);
            java.time.LocalDate d = java.time.LocalDate.ofInstant(
                    Files.getLastModifiedTime(legacy).toInstant(), java.time.ZoneId.systemDefault());
            Path target = logsDir.resolve("monitor-" + d + ".log");
            if (Files.exists(target)) {
                Files.write(target, Files.readAllBytes(legacy), java.nio.file.StandardOpenOption.APPEND);
                Files.delete(legacy);
            } else {
                Files.move(legacy, target);
            }
        } catch (IOException ignored) {}
    }

    /** Linux／macOS：資料夾只允許擁有者存取（rwx------），因為裡面有密碼雜湊與加密的 Webhook。 */
    private static void restrictPermissions(Path p) {
        try {
            if (Files.getFileStore(p).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        } catch (Exception ignored) {
            // Windows 或不支援的檔案系統：%APPDATA% 本身已限定使用者
        }
    }

    static Path resolveDir() {
        String override = System.getProperty("secnews.home");
        if (override == null || override.isBlank()) override = System.getenv("SECNEWS_HOME");
        if (override != null && !override.isBlank()) return Paths.get(override);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Paths.get(appData != null ? appData : home, "SecNewsMonitor");
        }
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "SecNewsMonitor");
        return Paths.get(home, ".secnewsmonitor");
    }

    public Path dir() { return dir; }
    public Path logsDir() { return logsDir; }

    // ---------- 紀錄檔保留天數 ----------
    public static final int DEFAULT_LOG_RETENTION_DAYS = 30;
    public static final int MAX_LOG_RETENTION_DAYS = 3650;

    /** 保留最近幾天的紀錄檔；0 表示永久保留。 */
    public synchronized int getLogRetentionDays() {
        try {
            int d = Integer.parseInt(config.getProperty("log.retentionDays", String.valueOf(DEFAULT_LOG_RETENTION_DAYS)));
            return Math.max(0, Math.min(MAX_LOG_RETENTION_DAYS, d));
        } catch (NumberFormatException e) {
            return DEFAULT_LOG_RETENTION_DAYS;
        }
    }

    public synchronized void setLogRetentionDays(int days) throws IOException {
        config.setProperty("log.retentionDays", String.valueOf(Math.max(0, Math.min(MAX_LOG_RETENTION_DAYS, days))));
        saveConfig();
    }

    // ---------- 帳號 ----------
    public synchronized boolean hasAccount() {
        return config.getProperty("account.user") != null;
    }

    public synchronized String accountUser() {
        return config.getProperty("account.user", "");
    }

    public synchronized void createAccount(String user, char[] password) throws IOException {
        byte[] salt = Crypto.randomBytes(16);
        byte[] keySalt = Crypto.randomBytes(16);
        config.setProperty("account.user", user);
        config.setProperty("account.salt", Crypto.b64(salt));
        config.setProperty("account.iterations", String.valueOf(Crypto.ITERATIONS));
        config.setProperty("account.hash", Crypto.b64(Crypto.pbkdf2(password, salt, Crypto.ITERATIONS, 256)));
        config.setProperty("account.keySalt", Crypto.b64(keySalt));
        sessionKey = Crypto.deriveKey(password, keySalt);
        saveConfig();
    }

    /** 驗證帳密；成功時同時推導出加密金鑰。 */
    public synchronized boolean login(String user, char[] password) {
        if (!hasAccount() || !accountUser().equals(user)) {
            // 帳號不符也跑一次 PBKDF2，讓回應時間一致
            Crypto.pbkdf2(password, new byte[16], Crypto.ITERATIONS, 256);
            return false;
        }
        byte[] salt = Crypto.unb64(config.getProperty("account.salt"));
        int it = Integer.parseInt(config.getProperty("account.iterations", String.valueOf(Crypto.ITERATIONS)));
        byte[] hash = Crypto.unb64(config.getProperty("account.hash"));
        if (!Crypto.verify(password, salt, it, hash)) return false;
        sessionKey = Crypto.deriveKey(password, Crypto.unb64(config.getProperty("account.keySalt")));
        return true;
    }

    /**
     * 忘記密碼時重設帳號：刪除帳號與加密的 Webhook（舊密碼不存在就無法解密）。
     * 網站清單、關鍵字、排程、已讀紀錄、執行紀錄都保留。
     */
    public synchronized void resetAccount() throws IOException {
        config.stringPropertyNames().stream()
                .filter(k -> k.startsWith("account.") || k.equals("chat.webhook.enc"))
                .toList().forEach(config::remove);
        sessionKey = null;
        saveConfig();
    }

    /** 變更密碼：重新雜湊，並用新金鑰重新加密 Webhook。 */
    public synchronized void changePassword(char[] newPassword) throws IOException {
        String webhook = getWebhookUrl();
        createAccount(accountUser(), newPassword);
        setWebhookUrl(webhook);
    }

    // ---------- Google Chat ----------
    public synchronized String getWebhookUrl() {
        if (sessionKey == null) return "";
        return Crypto.decrypt(sessionKey, config.getProperty("chat.webhook.enc", ""));
    }

    public synchronized void setWebhookUrl(String url) throws IOException {
        if (sessionKey == null) throw new IllegalStateException("尚未登入");
        config.setProperty("chat.webhook.enc", Crypto.encrypt(sessionKey, url == null ? "" : url.trim()));
        saveConfig();
    }

    /** 是否已設定 Webhook（不需登入即可判斷，不解密）。 */
    public synchronized boolean hasWebhook() {
        return !config.getProperty("chat.webhook.enc", "").isEmpty();
    }

    public synchronized boolean isChatEnabled() {
        return Boolean.parseBoolean(config.getProperty("chat.enabled", "true"));
    }

    public synchronized void setChatEnabled(boolean b) throws IOException {
        config.setProperty("chat.enabled", String.valueOf(b));
        saveConfig();
    }

    public static final int DEFAULT_MAX_PER_RUN = 20;

    /** 單次執行最多推播幾則（超過的只記在執行紀錄）。 */
    public synchronized int getMaxPushPerRun() {
        try {
            return Math.max(1, Integer.parseInt(config.getProperty("chat.maxPerRun", String.valueOf(DEFAULT_MAX_PER_RUN))));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_PER_RUN;
        }
    }

    public synchronized void setMaxPushPerRun(int n) throws IOException {
        config.setProperty("chat.maxPerRun", String.valueOf(Math.max(1, n)));
        saveConfig();
    }

    // ---------- 排程 ----------
    public synchronized ScheduleConfig getSchedule() {
        return ScheduleConfig.load(config);
    }

    public synchronized void setSchedule(ScheduleConfig s) throws IOException {
        s.save(config);
        saveConfig();
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
        writeAtomic(sitesFile, sb.toString());
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
        writeAtomic(keywordsFile, String.join("\n", keywords) + "\n");
    }

    // ---------- 已讀紀錄（去重） ----------
    // v1.2 起每個網站分開保存（seen.txt 每行：網站名稱<TAB>鍵值）。
    // 每次成功抓取後，只保留「目前 feed 裡還有的項目」＋最近 RECENT_EXTRA 筆，
    // 因此總量只跟各網站 feed 大小有關，不會再因全域上限擠掉別的網站而重複推播。
    static final int RECENT_EXTRA = 2000;

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
        s.add(k);   // 移到最後 = 最近
    }

    /**
     * 網站抓取成功後呼叫：保留目前 feed 中、已標記已讀的鍵值，
     * 另外保留不在 feed 中但最近標記的 RECENT_EXTRA 筆（避免文章暫時從列表消失又出現時重複推播）。
     */
    public synchronized void retainSeen(String site, java.util.Collection<String> currentKeys) {
        LinkedHashSet<String> s = seenBySite.get(site);
        if (s == null) return;
        java.util.Set<String> current = new java.util.HashSet<>();
        for (String k : currentKeys) current.add(norm(k));
        java.util.List<String> others = new java.util.ArrayList<>();
        for (String k : s) if (!current.contains(k)) others.add(k);
        int drop = Math.max(0, others.size() - RECENT_EXTRA);
        for (int i = 0; i < drop; i++) s.remove(others.get(i));   // 最舊的在前面
    }

    /**
     * 舊版（v1.1 以前）不分網站的已讀紀錄：所有啟用的網站都成功抓取過一次後即可丟棄。
     * 停用中的網站沒有轉移到新格式，因此清掉它們的「已建立基準」標記，
     * 重新啟用時會再建立一次基準，避免推播舊文章。
     */
    public synchronized void dropLegacySeen(java.util.Collection<String> notFetchedSites) {
        legacySeen.clear();
        for (String s : notFetchedSites) config.remove("site.baseline." + s);
    }

    public synchronized boolean hasLegacySeen() { return !legacySeen.isEmpty(); }

    /** 刪除已不在網站列表中的網站的已讀紀錄（網站被刪除或改名時）。 */
    public synchronized void pruneSeenSites(java.util.Collection<String> existingSiteNames) {
        seenBySite.keySet().retainAll(new java.util.HashSet<>(existingSiteNames));
    }

    public synchronized int seenCount() {
        int n = legacySeen.size();
        for (LinkedHashSet<String> s : seenBySite.values()) n += s.size();
        return n;
    }

    private void loadSeen() throws IOException {
        if (!Files.exists(seenFile)) return;
        for (String line : Files.readAllLines(seenFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) legacySeen.add(line);       // 舊格式：只有鍵值
            else seenBySite.computeIfAbsent(line.substring(0, tab), x -> new LinkedHashSet<>())
                    .add(line.substring(tab + 1));
        }
    }

    public synchronized boolean hasSeenAnyFrom(String siteName) {
        return config.getProperty("site.baseline." + siteName) != null;
    }

    public synchronized void markSiteBaselined(String siteName) {
        config.setProperty("site.baseline." + siteName, "1");
    }

    public synchronized void flushSeen() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String k : legacySeen) sb.append(k).append('\n');
        for (java.util.Map.Entry<String, LinkedHashSet<String>> e : seenBySite.entrySet()) {
            for (String k : e.getValue()) sb.append(e.getKey()).append('\t').append(k).append('\n');
        }
        writeAtomic(seenFile, sb.toString());
        saveConfig();
    }

    public synchronized void clearSeen() throws IOException {
        legacySeen.clear();
        seenBySite.clear();
        config.stringPropertyNames().stream().filter(k -> k.startsWith("site.baseline."))
                .toList().forEach(config::remove);
        flushSeen();
    }

    // ---------- 共用 ----------
    private void loadConfig() throws IOException {
        config = new Properties();
        if (Files.exists(configFile)) {
            try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                config.load(r);
            }
        }
    }

    private void saveConfig() throws IOException {
        Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            config.store(w, "SecNewsMonitor");
        }
        move(tmp, configFile);
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        move(tmp, target);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
