package com.secnews;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.8.1：《SecNewsMonitor v1.8.0 資安風險修復稽核與驗證報告》未修復／部分緩解項目的回歸測試。
 * 每一項都用稽核報告的攻擊步驟重現（M1、M2、M3-c、M5、M8）。
 */
public class V181Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    public static void main(String[] a) throws Exception {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("V181 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        System.setProperty("secnews.allowPrivate", "true");
        m2();
        m1();
        m3c();
        m5();
        m8();
    }

    // ---------- M2：登入失敗鎖定存在設定檔，跨實例（畫面、命令列、重開程式）有效 ----------
    static void m2() throws Exception {
        Path dir = Files.createTempDirectory("v181m2");
        System.setProperty("secnews.home", dir.toString());
        Store s = new Store();
        s.createAccount("admin", "Passw0rd1".toCharArray());
        // 稽核步驟：連續 8 次錯誤密碼，每次都是新的 Store（等同新的行程），第 9 次輸入正確密碼
        for (int i = 0; i < 8; i++) new Store().login("admin", ("wrong" + i + "x1").toCharArray());
        Store s9 = new Store();
        check("M2 9th correct password refused while locked", !s9.login("admin", "Passw0rd1".toCharArray()));
        long rem = s9.loginLockRemainingMs();
        check("M2 lock persisted across instances (" + rem + " ms)", rem > 50_000 && rem <= Store.LOCK_MAX_MS);
        // 對話框關掉重開 = 新的 Store 物件讀同一份設定：仍鎖定
        check("M2 reopened instance still locked", new Store().loginLockRemainingMs() > 0);
        // 帳號不符也計入失敗
        Path d2 = Files.createTempDirectory("v181m2b");
        System.setProperty("secnews.home", d2.toString());
        Store u = new Store();
        u.createAccount("admin", "Passw0rd1".toCharArray());
        for (int i = 0; i < 4; i++) u.login("root", "Passw0rd1".toCharArray());
        check("M2 wrong user counted", u.loginFailCount() == 4);
        check("M2 success clears counter", u.login("admin", "Passw0rd1".toCharArray()) && new Store().loginFailCount() == 0);
        // 鎖定到期後可再登入；第二次鎖定時間加倍
        forceExpire(d2);
        for (int i = 0; i < 5; i++) new Store().login("admin", "bad".toCharArray());
        long first = new Store().loginLockRemainingMs();
        forceExpire(d2);
        check("M2 can login after lock expires", new Store().login("admin", "Passw0rd1".toCharArray()));
        for (int i = 0; i < 5; i++) new Store().login("admin", "bad".toCharArray());
        forceExpire(d2);
        for (int i = 0; i < 5; i++) new Store().login("admin", "bad".toCharArray());
        long second = new Store().loginLockRemainingMs();
        check("M2 repeated lockout doubles (" + first + " → " + second + ")", second > first + 50_000);
        // 時間被往回調：鎖定最多 15 分鐘
        setProp(d2, "login.lockedUntil", String.valueOf(System.currentTimeMillis() + 10L * 86_400_000L));
        check("M2 lock capped at 15 min", new Store().loginLockRemainingMs() <= Store.LOCK_MAX_MS);
        // 新帳號（含重設帳號）清除鎖定
        Store r = new Store();
        r.resetAndCreateAccount("owner", "Passw0rd2".toCharArray(), "命令列");
        check("M2 reset clears lock", new Store().loginLockRemainingMs() == 0 && new Store().login("owner", "Passw0rd2".toCharArray()));
    }

    static void forceExpire(Path dir) throws Exception {
        setProp(dir, "login.lockedUntil", "1");
    }

    static void setProp(Path dir, String k, String v) throws Exception {
        java.util.Properties p = new java.util.Properties();
        Path f = dir.resolve("config.properties");
        try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
        p.setProperty(k, v);
        try (var w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { p.store(w, null); }
    }

    // ---------- M1：重設帳號留下紀錄（時間、途徑、原帳號），變更密碼不會抹掉 ----------
    static void m1() throws Exception {
        Path dir = Files.createTempDirectory("v181m1");
        System.setProperty("secnews.home", dir.toString());
        Store s = new Store();
        s.createAccount("admin", "Passw0rd1".toCharArray());
        check("M1 created time recorded", s.accountCreatedAt() > 0 && s.accountResetAt() == 0 && s.accountResetDescription().isEmpty());
        s.primary().setWebhookUrl("https://chat.googleapis.com/v1/spaces/X/messages?key=k&token=t");
        new Store().resetAndCreateAccount("hacker", "Passw0rd9".toCharArray(), "命令列");
        Store after = new Store();
        String d = after.accountResetDescription();
        check("M1 reset recorded (" + d + ")", after.accountResetAt() > 0 && d.contains("命令列") && d.contains("原帳號 admin"));
        check("M1 secrets still cleared on reset", !after.primary().hasWebhook());
        after.login("hacker", "Passw0rd9".toCharArray());
        after.changePassword("Passw0rd8".toCharArray());
        Store again = new Store();
        check("M1 change password keeps reset record", again.accountResetAt() == after.accountResetAt()
                && again.accountResetFrom().equals("admin") && again.accountCreatedAt() == after.accountCreatedAt());
        // 原帳號名稱含換行等字元也只顯示一行
        new Store().resetAndCreateAccount("x\nFAKE", "Passw0rd7".toCharArray(), "畫面");
        new Store().resetAndCreateAccount("y", "Passw0rd7".toCharArray(), "畫面");
        check("M1 description single line", !new Store().accountResetDescription().contains("\n"));
    }

    // ---------- M3-c：全文抓取失敗不永久標為已讀 ----------
    static void m3c() throws Exception {
        Path dir = Files.createTempDirectory("v181m3");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        st.createAccount("admin", "Passw0rd1".toCharArray());
        MonitorService.fullTextDelayMs = 0;
        AtomicInteger aCalls = new AtomicInteger(), zCalls = new AtomicInteger();
        int[] aFailUntil = {1};          // 文章 A 前幾次回 HTTP 500
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        srv.createContext("/rss", ex -> send(ex, 200, "<rss><channel>"
                + "<item><title>新聞 A</title><link>" + base + "/art1</link></item>"
                + "<item><title>新聞 B</title><link>" + base + "/art2</link></item>"
                + "<item><title>新聞 Z</title><link>" + base + "/artz</link></item></channel></rss>"));
        srv.createContext("/art1", ex -> {
            int n = aCalls.incrementAndGet();
            if (n <= aFailUntil[0]) send(ex, 500, "error"); else send(ex, 200, "<html><body><p>內文提到 SECRETKW 漏洞</p></body></html>");
        });
        srv.createContext("/art2", ex -> send(ex, 200, "<html><body><p>B 的內文 SECRETKW</p></body></html>"));
        srv.createContext("/artz", ex -> { zCalls.incrementAndGet(); send(ex, 500, "always broken"); });
        srv.start();
        try {
            Channel ch = st.primary();
            ch.saveSites(List.of(new Site("F", base + "/rss", Site.Type.RSS, true, true)));
            ch.saveKeywords(List.of("SECRETKW"));
            ScheduleConfig sc = ch.getSchedule(); sc.baselineFirstRun = false; sc.maxAgeDays = 0; ch.setSchedule(sc);
            MonitorService m = new MonitorService(st);
            List<String> sent = new ArrayList<>();
            m.sender = (u, t) -> sent.add(t);
            ch.setWebhookUrl("https://chat.googleapis.com/v1/spaces/X/messages?key=k&token=t");
            m.runChannel(ch, "t1");
            String seen1 = Files.readString(ch.dir().resolve("seen.txt"));
            check("M3c run1: failed article A not marked seen", !seen1.contains("/art1"));
            check("M3c run1: B pushed", sent.size() == 1 && sent.get(0).contains("新聞 B") && !sent.get(0).contains("新聞 A"));
            check("M3c run1: failure count persisted", Files.readString(ch.dir().resolve("fulltext-retry.tsv")).contains("/art1"));
            // 第 2 次：A 恢復正常 → 推播 A
            m.runChannel(ch, "t2");
            check("M3c run2: A pushed after recovery", sent.size() == 2 && sent.get(1).contains("新聞 A"));
            check("M3c run2: A now seen, retry record cleared", Files.readString(ch.dir().resolve("seen.txt")).contains("/art1")
                    && !Files.readString(ch.dir().resolve("fulltext-retry.tsv")).contains("/art1"));
            // Z 一直失敗：第 3 次放棄並標為已讀，之後不再抓
            m.runChannel(ch, "t3");
            int zAfter3 = zCalls.get();
            m.runChannel(ch, "t4");
            check("M3c permanently broken article given up after 3 tries (" + zAfter3 + "/" + zCalls.get() + ")",
                    zAfter3 == MonitorService.MAX_FULLTEXT_RETRIES && zCalls.get() == zAfter3
                    && Files.readString(ch.dir().resolve("seen.txt")).contains("/artz"));
            check("M3c no duplicates", sent.size() == 2);
            check("M3c retry file removed when empty", !Files.exists(ch.dir().resolve("fulltext-retry.tsv")));
        } finally {
            srv.stop(0);
        }
    }

    // ---------- M5：另一個執行個體持鎖時 --status 不得改動任何檔案 ----------
    static void m5() throws Exception {
        // 情境 1：v1.4 舊格式資料夾
        Path dir = Files.createTempDirectory("v181m5");
        Files.writeString(dir.resolve("sites.tsv"), "T\thttps://ex.com/rss\tRSS\ttrue\n");
        Files.writeString(dir.resolve("keywords.txt"), "Java\n");
        Files.writeString(dir.resolve("seen.txt"), "k1\n");
        Files.writeString(dir.resolve("monitor.log"), "old log\n");
        Files.writeString(dir.resolve("config.properties"), "schedule.mode=INTERVAL\nschedule.intervalMinutes=30\n");
        // 唯讀 Store：不改任何檔案、寫入一律拒絕
        System.setProperty("secnews.home", dir.toString());
        TreeMap<String, String> before = snapshot(dir);
        Store ro = Store.openReadOnly();
        check("M5 read-only detects legacy folder", ro.needsUpgrade());
        boolean refused;
        try { ro.createAccount("x", "Passw0rd1".toCharArray()); refused = false; } catch (IllegalStateException e) { refused = true; }
        check("M5 read-only refuses writes", refused);
        check("M5 read-only store changed nothing", snapshot(dir).equals(before));

        // 稽核步驟：另一個行程持有 .lock，再執行 --status（黑箱，獨立行程）
        try (FileChannel fc = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = fc.tryLock()) {
            check("M5 test holds lock", lock != null);
            before = snapshot(dir);
            String out = runStatus(dir);
            TreeMap<String, String> after = snapshot(dir);
            check("M5 legacy folder + other instance: no file changes", after.equals(before));
            check("M5 status explains (" + firstLine(out, "舊版格式") + ")", out.contains("執行中") && out.contains("舊版格式"));

            // 情境 1b：已升級資料夾、他人持鎖：顯示完整狀態、零異動
            Path d2 = Files.createTempDirectory("v181m5b");
            System.setProperty("secnews.home", d2.toString());
            Store up = new Store();
            up.createAccount("admin", "Passw0rd1".toCharArray());
            try (FileChannel fc2 = FileChannel.open(d2.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock l2 = fc2.tryLock()) {
                TreeMap<String, String> b2 = snapshot(d2);
                String out2 = runStatus(d2);
                check("M5 upgraded folder + other instance: no file changes", snapshot(d2).equals(b2));
                check("M5 upgraded folder: full status shown", out2.contains("執行狀態      ：執行中") && out2.contains("帳號          ：admin")
                        && out2.contains("頻道「資安新聞」"));
            }
        }
        // 沒有其他執行個體：--status 照常升級舊格式（行為不變）
        String out3 = runStatus(dir);
        check("M5 no other instance: legacy folder upgraded as before", out3.contains("未執行")
                && Files.exists(dir.resolve("channels/c1/sites.tsv")) && !Files.exists(dir.resolve("sites.tsv")));
    }

    static String firstLine(String s, String containing) {
        for (String l : s.split("\n")) if (l.contains(containing)) return l.length() > 40 ? l.substring(0, 40) + "…" : l;
        return "";
    }

    static String runStatus(Path home) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Dsecnews.home=" + home, "-Dstdout.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"), "com.secnews.App", "--status");
        pb.redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        p.getInputStream().transferTo(bo);
        p.waitFor();
        return bo.toString(StandardCharsets.UTF_8);
    }

    /** 資料夾內所有檔案（排除 .lock）的 SHA-256。 */
    static TreeMap<String, String> snapshot(Path dir) throws Exception {
        TreeMap<String, String> m = new TreeMap<>();
        try (var w = Files.walk(dir)) {
            for (Path p : w.toList()) {
                String rel = dir.relativize(p).toString();
                if (rel.equals(".lock")) continue;
                if (Files.isDirectory(p)) { m.put(rel + "/", "dir"); continue; }
                m.put(rel, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return m;
    }

    // ---------- M8：Windows ACL 的廣泛群組判斷 ----------
    static void m8() {
        for (String n : new String[]{"Everyone", "BUILTIN\\Users", "NT AUTHORITY\\Authenticated Users", "CORP\\Domain Users", "BUILTIN\\Guests"}) {
            check("M8 broad principal " + n, Cli.isBroadPrincipal(n));
        }
        for (String n : new String[]{"DESKTOP-1\\jackie", "NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators", "CORP\\UsersAdmin", null}) {
            check("M8 not broad " + n, !Cli.isBroadPrincipal(n));
        }
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", body.startsWith("<rss") ? "application/rss+xml; charset=UTF-8" : "text/html; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (var o = ex.getResponseBody()) { o.write(b); }
    }
}
