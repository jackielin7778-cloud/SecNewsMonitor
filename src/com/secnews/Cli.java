package com.secnews;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * 命令列／無介面模式，主要給沒有圖形介面的 Linux 伺服器使用（Windows、macOS 也可用）。
 *
 * 密碼來源（依序）：
 *   --password-file 檔案 → 環境變數 SECNEWS_PASSWORD_FILE → systemd credential（$CREDENTIALS_DIRECTORY/secnews-password）
 *   → 環境變數 SECNEWS_PASSWORD → 終端機輸入
 */
final class Cli {
    enum Command { GUI, HELP, VERSION, STATUS, SETUP, SET_WEBHOOK, TEST_CHAT, RUN_ONCE, HEADLESS, ADD_CHANNEL, DELETE_CHANNEL }

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private static final PrintStream ERR = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    private static BufferedReader stdin;

    private Cli() {}

    static Command parse(String[] args) {
        if (args.length == 0) return Command.GUI;
        return switch (args[0]) {
            case "--headless", "--daemon" -> Command.HEADLESS;
            case "--setup" -> Command.SETUP;
            case "--set-webhook" -> Command.SET_WEBHOOK;
            case "--test-chat" -> Command.TEST_CHAT;
            case "--run-once" -> Command.RUN_ONCE;
            case "--status" -> Command.STATUS;
            case "--add-channel" -> Command.ADD_CHANNEL;
            case "--delete-channel" -> Command.DELETE_CHANNEL;
            case "--version", "-v" -> Command.VERSION;
            case "--gui" -> Command.GUI;
            default -> Command.HELP;
        };
    }

    static boolean has(String[] args, String flag) {
        return Arrays.asList(args).contains(flag);
    }

    static String value(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) if (flag.equals(args[i])) return args[i + 1];
        return null;
    }

    static String version() {
        return AppInfo.version();
    }

    /** 執行命令列指令，回傳結束代碼。 */
    static int run(Command cmd, String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        switch (cmd) {
            case HELP: help(); return args.length == 0 || has(args, "--help") || has(args, "-h") ? 0 : 2;
            case VERSION: OUT.println("SecNewsMonitor " + version()); return 0;
            default: break;
        }

        // 先取得鎖再讀取資料（v1.5 升級時會搬移設定檔，不可在其他執行個體執行中進行）
        if (cmd != Command.STATUS && !App.acquireLock(Store.resolveDir())) {
            ERR.println("錯誤：SecNewsMonitor 已經在執行中（圖形介面或無介面模式）。");
            ERR.println("      請先停止它，例如：systemctl stop secnewsmonitor");
            return 3;
        }
        Store store = new Store();
        AppLog.init(store.logsDir(), store::getLogRetentionDays);
        App.logMigration(store);
        if (cmd == Command.STATUS) return status(store);
        return switch (cmd) {
            case SETUP -> setup(store, args);
            case SET_WEBHOOK -> setWebhook(store, args);
            case TEST_CHAT -> testChat(store, args);
            case RUN_ONCE -> runOnce(store, args);
            case HEADLESS -> headless(store, args);
            case ADD_CHANNEL -> addChannel(store, args);
            case DELETE_CHANNEL -> deleteChannel(store, args);
            default -> 2;
        };
    }

    // ------------------------------------------------------------------ 指令

    private static void help() {
        OUT.println("""
                SecNewsMonitor %s — 資安新聞監控

                用法：
                  run-linux.sh                  有圖形介面時開啟視窗（與 Windows／macOS 相同）
                  run-linux.sh --setup          建立管理員帳號並設定 Google Chat Webhook（第一次使用）
                  run-linux.sh --headless       無介面模式：常駐背景依排程執行（適合 systemd 服務）
                  run-linux.sh --run-once       立即執行一次監控後結束（全部啟用中的頻道）
                  run-linux.sh --set-webhook    變更 Google Chat Webhook
                  run-linux.sh --test-chat      傳送 Google Chat 測試訊息
                  run-linux.sh --status         顯示目前設定與所有頻道（不需密碼）
                  run-linux.sh --add-channel 名稱          新增頻道（之後編輯該頻道的 sites.tsv、keywords.txt，
                                                           再用 --set-webhook --channel 名稱 設定推播聊天室）
                  run-linux.sh --delete-channel 名稱 --yes 刪除頻道（含網站、關鍵字、Webhook、已讀紀錄）
                  run-linux.sh --version        顯示版本

                選項：
                  --password-file 檔案          從檔案讀取登入密碼（檔案權限請設為 600）
                  --reset                       與 --setup 併用：重設既有帳號（會清除所有頻道的 Webhook）
                  --channel 名稱或ID            指定頻道（--run-once、--set-webhook、--test-chat）；
                                                未指定時：--run-once 執行全部頻道，其他指令使用第一個頻道

                密碼來源（依序）：--password-file、環境變數 SECNEWS_PASSWORD_FILE、
                  systemd credential（$CREDENTIALS_DIRECTORY/secnews-password）、環境變數 SECNEWS_PASSWORD、終端機輸入。

                資料夾：環境變數 SECNEWS_HOME，預設 ~/.secnewsmonitor（Linux）
                頻道：新增／刪除頻道請用圖形介面；各頻道的網站列表 channels/頻道ID/sites.tsv、
                  關鍵字 channels/頻道ID/keywords.txt 可直接用文字編輯器修改（請先停止程式）。
                """.formatted(version()));
    }

    private static int status(Store store) throws IOException {
        boolean running = !App.acquireLock(store);
        if (!running) App.releaseLock();

        OUT.println("SecNewsMonitor " + version());
        OUT.println("資料夾        ：" + store.dir());
        OUT.println("執行狀態      ：" + (running ? "執行中" : "未執行"));
        OUT.println("帳號          ：" + (store.hasAccount() ? store.accountUser() : "（尚未建立，請執行 --setup）"));
        OUT.println("紀錄檔        ：" + store.logsDir() + "（保留 "
                + (store.getLogRetentionDays() == 0 ? "永久" : store.getLogRetentionDays() + " 天") + "）");
        OUT.println("頻道數        ：" + store.channels().size());
        for (Channel ch : store.channels()) {
            ScheduleConfig sc = ch.getSchedule();
            ZonedDateTime next = sc.nextRun(ZonedDateTime.now());
            List<Site> sites = ch.loadSites();
            long enabled = sites.stream().filter(s -> s.enabled).count();
            OUT.println();
            OUT.println("== 頻道「" + ch.getName() + "」（ID " + ch.id() + "）" + (ch.isEnabled() ? "" : "【停用】"));
            OUT.println("Google Chat   ：" + (ch.hasWebhook() ? "已設定" : "未設定")
                    + "，推播" + (ch.isChatEnabled() ? "啟用" : "停用") + "，單次最多 " + ch.getMaxPushPerRun() + " 則");
            OUT.println("排程          ：" + sc.describe()
                    + (next != null && sc.enabled && ch.isEnabled() ? "（若現在啟動，下次執行 " + next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "）" : ""));
            OUT.println("首次只建立基準：" + (sc.baselineFirstRun ? "是" : "否")
                    + "；只推播 " + (sc.maxAgeDays == 0 ? "不限天數" : sc.maxAgeDays + " 天內") + "的文章");
            OUT.println("關鍵字        ：" + String.join(", ", ch.loadKeywords()));
            OUT.println("網站          ：啟用 " + enabled + " / 共 " + sites.size());
            for (Site s : sites) {
                OUT.println("  " + (s.enabled ? "[✓]" : "[ ]") + (s.fullText ? "[全文]" : "      ") + " " + s.name + "  " + s.url);
            }
        }
        return 0;
    }

    private static int addChannel(Store store, String[] args) throws IOException {
        String name = args.length > 1 ? args[1] : null;
        if (name == null || name.isBlank() || name.startsWith("--")) {
            ERR.println("用法：--add-channel 頻道名稱");
            return 2;
        }
        try {
            Channel c = store.createChannel(name);
            AppLog.info("新增頻道：" + c.getName() + "（命令列）");
            OUT.println("已新增頻道「" + c.getName() + "」（ID " + c.id() + "）");
            OUT.println("網站列表：" + c.dir().resolve("sites.tsv"));
            OUT.println("關鍵字  ：" + c.dir().resolve("keywords.txt"));
            OUT.println("排程等設定：" + c.dir().resolve("channel.properties") + "（預設每 60 分鐘）");
            OUT.println("接著設定推播聊天室：--set-webhook --channel \"" + c.getName() + "\"");
            return 0;
        } catch (IllegalArgumentException e) {
            ERR.println(e.getMessage());
            return 1;
        }
    }

    private static int deleteChannel(Store store, String[] args) throws IOException {
        String name = args.length > 1 ? args[1] : null;
        if (name == null || name.startsWith("--")) { ERR.println("用法：--delete-channel 頻道名稱 --yes"); return 2; }
        Channel c = store.channel(name);
        if (c == null) { ERR.println("找不到頻道「" + name + "」"); return 2; }
        if (!has(args, "--yes")) {
            ERR.println("刪除頻道「" + c.getName() + "」會一併刪除它的網站、關鍵字、Webhook 與已讀紀錄，無法復原。");
            ERR.println("確定要刪除請加上 --yes");
            return 1;
        }
        try {
            store.deleteChannel(c);
        } catch (IllegalStateException e) {
            ERR.println(e.getMessage());
            return 1;
        }
        AppLog.info("刪除頻道：" + c.getName() + "（命令列）");
        OUT.println("已刪除頻道「" + c.getName() + "」");
        return 0;
    }

    /** 依 --channel 取得頻道；未指定回傳 fallback；指定了但找不到時印出錯誤並回傳 null。 */
    private static Channel channelArg(Store store, String[] args, Channel fallback) {
        String v = value(args, "--channel");
        if (v == null) return fallback;
        Channel c = store.channel(v);
        if (c == null) {
            ERR.println("找不到頻道「" + v + "」。目前的頻道：");
            for (Channel x : store.channels()) ERR.println("  " + x.id() + "  " + x.getName());
        }
        return c;
    }

    private static int setup(Store store, String[] args) throws Exception {
        if (store.hasAccount()) {
            if (!has(args, "--reset")) {
                ERR.println("已經有帳號「" + store.accountUser() + "」。");
                ERR.println("若忘記密碼要重設，請執行：--setup --reset（會清除已儲存的 Webhook，其他設定保留）");
                return 1;
            }
            store.resetAccount();
            AppLog.info("帳號已重設（命令列 --setup --reset），Webhook 已清除");
            OUT.println("已重設帳號。");
        }
        OUT.println("== 建立管理員帳號 ==");
        String user = readLine("帳號：");
        if (user == null || user.isBlank()) { ERR.println("帳號不可空白"); return 1; }
        user = user.trim();
        char[] pw = null;
        for (int attempt = 0; attempt < 3 && pw == null; attempt++) {
            char[] p1 = readPassword("密碼（至少 8 碼，含英文與數字）：");
            char[] p2 = readPassword("確認密碼：");
            if (p1 == null || p2 == null) { ERR.println("沒有輸入密碼"); return 1; }
            String err = PasswordRules.check(p1, p2);
            Arrays.fill(p2, '\0');
            if (err == null) pw = p1;
            else { Arrays.fill(p1, '\0'); ERR.println(err); }
        }
        if (pw == null) return 1;
        store.createAccount(user, pw);
        Arrays.fill(pw, '\0');
        AppLog.info("使用者 " + user + " 建立帳號（命令列）");
        OUT.println("帳號已建立。");

        String url = readLine("頻道「" + store.primary().getName() + "」的 Google Chat Webhook URL（可先留空，之後用 --set-webhook 設定）：");
        if (url != null && !url.isBlank()) {
            if (!saveWebhook(store.primary(), url.trim())) return 1;
        }
        OUT.println();
        OUT.println("完成。接下來可以：");
        OUT.println("  --test-chat   傳送測試訊息");
        OUT.println("  --run-once    立即執行一次（第一次只會建立基準、不推播）");
        OUT.println("  --headless    常駐執行，或設定成 systemd 服務（見 README）");
        return 0;
    }

    private static int setWebhook(Store store, String[] args) throws Exception {
        Channel ch = channelArg(store, args, store.primary());
        if (ch == null) return 2;
        if (!login(store, args)) return 1;
        String url = readLine("頻道「" + ch.getName() + "」的 Google Chat Webhook URL（留空表示清除）：");
        if (url == null) return 1;
        if (url.isBlank()) {
            ch.setWebhookUrl("");
            AppLog.info("[" + ch.getName() + "] Google Chat Webhook 已清除（命令列）");
            OUT.println("已清除。");
            return 0;
        }
        return saveWebhook(ch, url.trim()) ? 0 : 1;
    }

    private static boolean saveWebhook(Channel ch, String url) throws IOException {
        try {
            GoogleChatClient.validate(url);
        } catch (IllegalArgumentException e) {
            ERR.println(e.getMessage());
            return false;
        }
        ch.setWebhookUrl(url);
        AppLog.info("[" + ch.getName() + "] Google Chat Webhook 已儲存（命令列）");
        OUT.println("頻道「" + ch.getName() + "」的 Webhook 已加密儲存。");
        return true;
    }

    private static int testChat(Store store, String[] args) throws Exception {
        Channel ch = channelArg(store, args, store.primary());
        if (ch == null) return 2;
        if (!login(store, args)) return 1;
        if (!ch.hasWebhook()) { ERR.println("頻道「" + ch.getName() + "」尚未設定 Webhook，請先執行 --set-webhook"); return 1; }
        try {
            new MonitorService(store).sendTest(ch.getWebhookUrl(), ch.getName());
            AppLog.info("[" + ch.getName() + "] Google Chat 測試訊息傳送成功（命令列）");
            OUT.println("測試訊息已送出，請到 Google Chat 確認。");
            return 0;
        } catch (Exception e) {
            AppLog.error("Google Chat 測試傳送失敗", e);
            ERR.println("傳送失敗：" + e.getMessage());
            return 1;
        }
    }

    private static int runOnce(Store store, String[] args) throws Exception {
        Channel ch = channelArg(store, args, null);
        if (value(args, "--channel") != null && ch == null) return 2;
        if (!login(store, args)) return 1;
        AppLog.addListener(OUT::println);
        if (ch != null) new MonitorService(store).runChannel(ch, "命令列");
        else new MonitorService(store).runAll("命令列");
        return 0;
    }

    private static int headless(Store store, String[] args) throws Exception {
        if (!login(store, args)) return 1;
        AppLog.addListener(OUT::println);     // systemd 會把標準輸出收進 journal
        AppLog.info("以無介面模式啟動，版本 " + version() + "，資料夾：" + store.dir() + "，Java "
                + System.getProperty("java.version") + "，" + System.getProperty("os.name"));
        for (Channel ch : store.channels()) {
            if (ch.isEnabled() && !ch.hasWebhook()) {
                AppLog.info("[" + ch.getName() + "] [警告] 尚未設定 Google Chat Webhook，命中的文章只會記錄在紀錄檔");
            }
        }

        MonitorService monitor = new MonitorService(store);
        SchedulerService scheduler = new SchedulerService(store, monitor);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AppLog.info("收到停止訊號，程式結束");
            scheduler.shutdown();
            stopped.countDown();
        }));
        scheduler.start();
        stopped.await();              // 排程執行緒是 daemon，主執行緒在這裡等到收到停止訊號
        return 0;
    }

    // ------------------------------------------------------------------ 登入與輸入

    private static boolean login(Store store, String[] args) throws IOException {
        if (!store.hasAccount()) {
            ERR.println("尚未建立帳號，請先執行 --setup");
            return false;
        }
        char[] pw = passwordFromConfiguredSource(args);
        if (pw == null) pw = readPassword("帳號 " + store.accountUser() + " 的密碼：");
        if (pw == null) {
            ERR.println("沒有可用的密碼來源。無介面執行時請使用 --password-file、SECNEWS_PASSWORD_FILE 或 systemd credential。");
            return false;
        }
        try {
            if (store.login(store.accountUser(), pw)) return true;
            AppLog.info("[錯誤] 命令列登入失敗：密碼錯誤");
            ERR.println("密碼錯誤。忘記密碼請執行 --setup --reset");
            return false;
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    /** 從檔案、systemd credential 或環境變數取得密碼；都沒有回傳 null。 */
    static char[] passwordFromConfiguredSource(String[] args) throws IOException {
        String file = value(args, "--password-file");
        if (file == null) file = System.getenv("SECNEWS_PASSWORD_FILE");
        if (file == null) {
            String cred = System.getenv("CREDENTIALS_DIRECTORY");
            if (cred != null && Files.isReadable(Paths.get(cred, "secnews-password"))) {
                file = Paths.get(cred, "secnews-password").toString();
            }
        }
        if (file != null) {
            Path p = Paths.get(file);
            warnIfReadableByOthers(p);
            String s = Files.readString(p, StandardCharsets.UTF_8);
            int end = s.length();
            while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
            return s.substring(0, end).toCharArray();
        }
        String env = System.getenv("SECNEWS_PASSWORD");
        return env == null ? null : env.toCharArray();
    }

    private static void warnIfReadableByOthers(Path p) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                ERR.println("[警告] 密碼檔 " + p + " 其他使用者也能讀取，建議執行：chmod 600 " + p);
            }
        } catch (Exception ignored) {
            // Windows 或不支援 POSIX 權限
        }
    }

    private static String readLine(String prompt) throws IOException {
        Console c = System.console();
        if (c != null) return c.readLine("%s", prompt);
        OUT.print(prompt);
        OUT.flush();
        return stdin().readLine();
    }

    private static char[] readPassword(String prompt) throws IOException {
        Console c = System.console();
        if (c != null) return c.readPassword("%s", prompt);   // 不顯示輸入內容
        OUT.print(prompt);
        OUT.flush();
        String s = stdin().readLine();                        // 非互動（例如管線輸入）
        return s == null ? null : s.toCharArray();
    }

    private static BufferedReader stdin() {
        if (stdin == null) stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return stdin;
    }
}
