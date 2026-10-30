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
 *   config.properties：帳號、紀錄保留天數、頻道順序
 *   channels/頻道ID/：各頻道的網站、關鍵字、排程、Webhook、已讀紀錄（見 {@link Channel}）
 *   logs/：執行紀錄
 *   macOS   : ~/Library/Application Support/SecNewsMonitor
 *   Windows : %APPDATA%\SecNewsMonitor
 *   其他    : ~/.secnewsmonitor
 * 可用 -Dsecnews.home=路徑 覆寫。
 */
public final class Store {

    private final Path dir;
    private final Path configFile, channelsDir, logsDir;
    private Properties config = new Properties();
    /**
     * 登入成功後才有，用來加解密 Webhook。
     * v1.7.1：改為 volatile、讀取不加鎖。原本 Channel.getWebhookUrl（持有頻道鎖）會再去取 Store 鎖，
     * 而 changePassword／resetAccount（持有 Store 鎖）會去取頻道鎖，兩者同時發生會死結。
     */
    private volatile SecretKey sessionKey;
    private final List<Channel> channels = new ArrayList<>();

    public Store() throws IOException {
        this.dir = resolveDir();
        Files.createDirectories(dir);
        restrictPermissions(dir);
        configFile = dir.resolve("config.properties");
        channelsDir = dir.resolve("channels");
        logsDir = dir.resolve("logs");
        loadConfig();
        loadChannels();
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
        Pending p = prepareAccount(user, password);
        // v1.8.0：新帳號使用新的金鑰，頻道內既有的密文（例如上次重設帳號中途失敗留下的）一定無法解密，先清除
        for (Channel c : channels) c.clearSecrets();
        commitAccount(p);
    }

    /** 新帳號資料（尚未寫入）。 */
    private static final class Pending {
        final Properties values = new Properties();
        SecretKey key;
    }

    private static Pending prepareAccount(String user, char[] password) {
        Pending p = new Pending();
        byte[] salt = Crypto.randomBytes(16);
        byte[] keySalt = Crypto.randomBytes(16);
        p.values.setProperty("account.user", user);
        p.values.setProperty("account.salt", Crypto.b64(salt));
        p.values.setProperty("account.iterations", String.valueOf(Crypto.ITERATIONS));
        p.values.setProperty("account.hash", Crypto.b64(Crypto.pbkdf2(password, salt, Crypto.ITERATIONS, 256)));
        p.values.setProperty("account.keySalt", Crypto.b64(keySalt));
        p.values.setProperty("account.keyIterations", String.valueOf(Crypto.ITERATIONS));
        p.key = Crypto.deriveKey(password, keySalt, Crypto.ITERATIONS);
        return p;
    }

    private void commitAccount(Pending p) throws IOException {
        Properties backup = (Properties) config.clone();
        config.stringPropertyNames().stream().filter(k -> k.startsWith("account.")).toList().forEach(config::remove);
        config.putAll(p.values);
        try {
            saveConfig();
        } catch (IOException e) {
            config = backup;
            throw e;
        }
        sessionKey = p.key;
    }

    /** 加密金鑰的 PBKDF2 次數（v1.7.1 以前固定 210,000 次，未記錄在設定檔）。 */
    private int keyIterations() {
        try {
            return Integer.parseInt(config.getProperty("account.keyIterations", String.valueOf(Crypto.LEGACY_ITERATIONS)));
        } catch (NumberFormatException e) {
            return Crypto.LEGACY_ITERATIONS;
        }
    }

    /** 驗證帳密；成功時同時推導出加密金鑰。 */
    public synchronized boolean login(String user, char[] password) {
        if (!hasAccount() || !accountUser().equals(user)) {
            // 帳號不符也跑一次 PBKDF2，讓回應時間一致
            Crypto.pbkdf2(password, new byte[16], Crypto.ITERATIONS, 256);
            return false;
        }
        byte[] salt = Crypto.unb64(config.getProperty("account.salt"));
        int it = Integer.parseInt(config.getProperty("account.iterations", String.valueOf(Crypto.LEGACY_ITERATIONS)));
        byte[] hash = Crypto.unb64(config.getProperty("account.hash"));
        if (!Crypto.verify(password, salt, it, hash)) return false;
        sessionKey = Crypto.deriveKey(password, Crypto.unb64(config.getProperty("account.keySalt")), keyIterations());
        try {
            for (Channel c : channels) c.resolvePendingSecrets(sessionKey);     // 上次變更密碼中途中斷時的收尾
        } catch (Exception e) {
            AppLog.error("整理 Webhook 加密資料失敗", e);
        }
        if (it < Crypto.ITERATIONS || keyIterations() < Crypto.ITERATIONS) {
            try {
                changePassword(password);           // 自動升級為較高的 PBKDF2 次數（密碼不變）
                AppLog.info("密碼雜湊已升級為 PBKDF2 " + Crypto.ITERATIONS + " 次");
            } catch (Exception e) {
                AppLog.error("升級密碼雜湊失敗（不影響使用）", e);
            }
        }
        return true;
    }

    /**
     * 忘記密碼時重設帳號：刪除帳號與加密的 Webhook（舊密碼不存在就無法解密）。
     * 網站清單、關鍵字、排程、已讀紀錄、執行紀錄都保留。
     */
    public synchronized void resetAccount() throws IOException {
        // v1.8.0：先清除各頻道的機密，最後才刪除帳號。中途失敗時帳號仍在（舊密碼可用），可以重做。
        for (Channel c : channels) c.clearSecrets();
        config.stringPropertyNames().stream()
                .filter(k -> k.startsWith("account.") || k.equals("chat.webhook.enc"))
                .toList().forEach(config::remove);
        sessionKey = null;
        saveConfig();
    }

    /**
     * 重設帳號並立即建立新帳號（命令列 --setup --reset、畫面上的重設帳號）。
     * v1.8.0：先準備新帳號（驗證輸入），再清除各頻道機密，最後一次寫入新帳號；中途失敗時舊帳號仍在。
     */
    public synchronized void resetAndCreateAccount(String user, char[] password) throws IOException {
        Pending p = prepareAccount(user, password);
        for (Channel c : channels) c.clearSecrets();
        config.remove("chat.webhook.enc");
        commitAccount(p);
    }

    /**
     * 變更密碼：重新雜湊，並用新金鑰重新加密每個頻道的 Webhook。
     * v1.7.1：分三階段，任何一步中斷都不會讓 Webhook 無法解密：
     *   1. 每個頻道先把新金鑰加密的 Webhook 寫到暫存欄位（原本的保留）
     *   2. 寫入新的帳號資料（原子寫入）
     *   3. 每個頻道把暫存欄位換成正式欄位
     * 若在 2 之前中斷：舊密碼仍有效、暫存欄位在下次登入時丟棄；
     * 若在 2 之後、3 之前中斷：新密碼有效，下次登入時用新金鑰把暫存欄位換成正式欄位。
     */
    public synchronized void changePassword(char[] newPassword) throws IOException {
        // 每個頻道的所有加密欄位（Webhook、LINE Token、Gmail 應用程式密碼）
        java.util.Map<Channel, java.util.Map<String, String>> secrets = new java.util.LinkedHashMap<>();
        for (Channel c : channels) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (String k : Channel.SECRET_KEYS) {
                if (!c.hasSecret(k)) continue;
                try {
                    m.put(k, c.getSecret(k));
                } catch (IllegalStateException e) {
                    // v1.8.0：無法解密的機密（資料損毀）不讓整個變更密碼失敗；清除並提示重新設定
                    c.clearSecret(k);
                    AppLog.info("[" + c.getName() + "] [警告] " + Channel.secretLabel(k) + " 無法解密，已清除，請重新設定");
                }
            }
            if (!m.isEmpty()) secrets.put(c, m);
        }
        Pending p = prepareAccount(accountUser(), newPassword);
        for (java.util.Map.Entry<Channel, java.util.Map<String, String>> e : secrets.entrySet()) {
            for (java.util.Map.Entry<String, String> kv : e.getValue().entrySet()) {
                e.getKey().stagePendingSecret(kv.getKey(), Crypto.encrypt(p.key, kv.getValue()));
            }
        }
        commitAccount(p);
        for (Channel c : secrets.keySet()) c.resolvePendingSecrets(p.key);
    }

    // ---------- 頻道 ----------
    public synchronized List<Channel> channels() { return new ArrayList<>(channels); }

    /** 第一個頻道（升級前的設定會轉移到這裡）。 */
    public synchronized Channel primary() { return channels.get(0); }

    /** 依 ID 或名稱（不分大小寫）尋找頻道；找不到回傳 null。 */
    public synchronized Channel channel(String idOrName) {
        if (idOrName == null) return null;
        for (Channel c : channels) if (c.id().equals(idOrName)) return c;
        for (Channel c : channels) if (c.getName().equalsIgnoreCase(idOrName.trim())) return c;
        return null;
    }

    public synchronized boolean channelNameExists(String name, Channel except) {
        for (Channel c : channels) if (c != except && c.getName().equalsIgnoreCase(name.trim())) return true;
        return false;
    }

    /** 新增頻道：網站與關鍵字空白，排程使用預設值（每 60 分鐘），推播啟用。 */
    public synchronized Channel createChannel(String name) throws IOException {
        return createChannel(name, Channel.Type.NEWS);
    }

    public synchronized Channel createChannel(String name, Channel.Type type) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("頻道名稱不可空白");
        if (channelNameExists(name, null)) throw new IllegalArgumentException("頻道名稱已存在：" + name.trim());
        int n = 1;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Channel c : channels) used.add(c.id());
        while (used.contains("c" + n) || Files.exists(channelsDir.resolve("c" + n))) n++;
        Channel c = new Channel(this, "c" + n, channelsDir.resolve("c" + n));
        c.setName(name);
        if (type == Channel.Type.PRODUCT) {
            c.setType(type);
            ScheduleConfig sc = c.getSchedule();
            sc.maxAgeDays = 0;           // 商品沒有發布日期
            c.setSchedule(sc);
        }
        channels.add(c);
        saveChannelOrder();
        return c;
    }

    /** 刪除頻道（含網站、關鍵字、Webhook、已讀紀錄）。至少保留一個頻道。 */
    public synchronized void deleteChannel(Channel c) throws IOException {
        if (channels.size() <= 1) throw new IllegalStateException("至少要保留一個頻道");
        if (!channels.remove(c)) return;
        saveChannelOrder();
        deleteRecursively(c.dir());
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (java.util.stream.Stream<Path> w = Files.walk(p)) {
            for (Path x : w.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    private void saveChannelOrder() throws IOException {
        List<String> ids = new ArrayList<>();
        for (Channel c : channels) ids.add(c.id());
        config.setProperty("channels", String.join(",", ids));
        saveConfig();
    }

    SecretKey sessionKey() { return sessionKey; }

    /** 讀取頻道列表；第一次使用（或由舊版升級）時建立「資安新聞」頻道並轉移舊設定。 */
    private void loadChannels() throws IOException {
        Files.createDirectories(channelsDir);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : config.getProperty("channels", "").split(",")) {
            if (!id.isBlank() && Files.isDirectory(channelsDir.resolve(id.trim()))) ids.add(id.trim());
        }
        try (java.util.stream.Stream<Path> l = Files.list(channelsDir)) {
            l.filter(Files::isDirectory).map(x -> x.getFileName().toString()).sorted().forEach(ids::add);
        }
        for (String id : ids) channels.add(new Channel(this, id, channelsDir.resolve(id)));
        boolean fresh = channels.isEmpty();
        if (fresh) {
            Channel c = new Channel(this, "c1", channelsDir.resolve("c1"));
            c.setName(DEFAULT_CHANNEL_NAME);
            if (!Files.exists(dir.resolve("sites.tsv"))) c.saveSites(DefaultSites.list());
            if (!Files.exists(dir.resolve("keywords.txt"))) c.saveKeywords(List.of("Java", "Tomcat", "OpenJDK"));
            channels.add(c);
        }
        migrateLegacy(channels.get(0));
        String order = config.getProperty("channels", "");
        List<String> cur = new ArrayList<>();
        for (Channel c : channels) cur.add(c.id());
        if (!order.equals(String.join(",", cur))) saveChannelOrder();
    }

    public static final String DEFAULT_CHANNEL_NAME = "資安新聞";

    /**
     * v1.4 以前的單一設定（資料夾最上層的 sites.tsv、keywords.txt、seen.txt，
     * 以及 config.properties 中的排程、Google Chat 設定）轉移到第一個頻道。
     * 轉移前先把舊檔備份到 backup-before-1.5/。之後若有人依舊版說明把 sites.tsv 放回最上層，也會再匯入第一個頻道。
     */
    private void migrateLegacy(Channel target) throws IOException {
        String[] files = {"sites.tsv", "keywords.txt", "seen.txt"};
        List<String> legacyKeys = config.stringPropertyNames().stream()
                .filter(k -> k.startsWith("schedule.") || k.startsWith("chat.") || k.startsWith("site.baseline."))
                .sorted().toList();
        boolean anyFile = false;
        for (String f : files) anyFile |= Files.exists(dir.resolve(f));
        if (!anyFile && legacyKeys.isEmpty()) return;

        Path backup = dir.resolve("backup-before-1.5");
        if (!Files.exists(backup)) {
            Files.createDirectories(backup);
            for (String f : files) {
                if (Files.exists(dir.resolve(f))) Files.copy(dir.resolve(f), backup.resolve(f));
            }
            if (Files.exists(configFile)) Files.copy(configFile, backup.resolve("config.properties"));
        }
        for (String f : files) {
            Path src = dir.resolve(f);
            if (!Files.exists(src)) continue;
            Path dst = target.dir().resolve(f);
            move(src, dst);
            migrated.add(f);
        }
        if (!legacyKeys.isEmpty()) {
            Properties p = target.props();
            for (String k : legacyKeys) {
                p.setProperty(k, config.getProperty(k));
                config.remove(k);
            }
            target.saveProps();
            saveConfig();
            migrated.add("config.properties（排程、Google Chat）");
        }
        target.reloadSeen();
    }

    private final List<String> migrated = new ArrayList<>();

    /** 本次啟動轉移了哪些舊版設定（給啟動紀錄用）；沒有轉移回傳空列表。 */
    public synchronized List<String> migratedLegacy() { return new ArrayList<>(migrated); }

    // ---------- 相容用：以下都是「第一個頻道」的設定 ----------
    public String getWebhookUrl() { return primary().getWebhookUrl(); }
    public void setWebhookUrl(String url) throws IOException { primary().setWebhookUrl(url); }
    public boolean hasWebhook() { return primary().hasWebhook(); }
    public boolean isChatEnabled() { return primary().isChatEnabled(); }
    public void setChatEnabled(boolean b) throws IOException { primary().setChatEnabled(b); }
    public static final int DEFAULT_MAX_PER_RUN = 20;
    public int getMaxPushPerRun() { return primary().getMaxPushPerRun(); }
    public void setMaxPushPerRun(int n) throws IOException { primary().setMaxPushPerRun(n); }
    public ScheduleConfig getSchedule() { return primary().getSchedule(); }
    public void setSchedule(ScheduleConfig s) throws IOException { primary().setSchedule(s); }
    public List<Site> loadSites() throws IOException { return primary().loadSites(); }
    public void saveSites(List<Site> sites) throws IOException { primary().saveSites(sites); }
    public List<String> loadKeywords() throws IOException { return primary().loadKeywords(); }
    public void saveKeywords(List<String> k) throws IOException { primary().saveKeywords(k); }
    static final int RECENT_EXTRA = 2000;
    public boolean isSeen(String site, String key) { return primary().isSeen(site, key); }
    public void markSeen(String site, String key) { primary().markSeen(site, key); }
    public int seenCount() {
        int n = 0;
        for (Channel c : channels()) n += c.seenCount();
        return n;
    }
    public void flushSeen() throws IOException { primary().flushSeen(); }
    public void clearSeen() throws IOException { primary().clearSeen(); }

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

    static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        move(tmp, target);
    }

    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
