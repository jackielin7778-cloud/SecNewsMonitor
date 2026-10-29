package com.secnews;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** v1.5：多頻道、舊版設定轉移、關鍵字清單解析。 */
public class V15Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    public static void main(String[] a) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            fail++;
        }
        System.out.println("V15 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        MonitorService.fullTextDelayMs = 0;
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        String rss = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel>"
                + "<item><title>Java 套件發現 AI 供應鏈攻擊</title><link>" + base + "/a/1</link></item>"
                + "<item><title>Tomcat 更新</title><link>" + base + "/a/2</link></item>"
                + "<item><title>新的 LLM 模型發表</title><link>" + base + "/a/3</link></item>"
                + "<item><title>天氣晴</title><link>" + base + "/a/4</link></item>"
                + "</channel></rss>";
        byte[] rb = rss.getBytes(StandardCharsets.UTF_8);
        srv.createContext("/feed", ex -> { ex.sendResponseHeaders(200, rb.length); ex.getResponseBody().write(rb); ex.close(); });
        srv.start();
        try {
            legacyMigration();
            multiChannel(base);
            channelManagement();
            parseList();
        } finally {
            srv.stop(0);
        }
    }

    // ---------- 1. 由 v1.4 升級：舊設定轉移到「資安新聞」頻道 ----------
    static void legacyMigration() throws Exception {
        Path dir = Files.createTempDirectory("v15mig");
        System.setProperty("secnews.home", dir.toString());
        // 先用新版產生帳號與加密 Webhook，再把資料改成 v1.4 的檔案配置
        Store tmp = new Store();
        tmp.createAccount("admin", "Passw0rd1".toCharArray());
        String hook = "https://chat.googleapis.com/v1/spaces/OLD/messages?key=K&token=SECRET";
        tmp.primary().setWebhookUrl(hook);
        Properties cp = new Properties();
        try (var r = Files.newBufferedReader(dir.resolve("channels/c1/channel.properties"))) { cp.load(r); }
        String enc = cp.getProperty("chat.webhook.enc");
        deleteTree(dir.resolve("channels"));
        Properties cfg = new Properties();
        try (var r = Files.newBufferedReader(dir.resolve("config.properties"))) { cfg.load(r); }
        cfg.remove("channels");
        cfg.setProperty("chat.webhook.enc", enc);
        cfg.setProperty("chat.enabled", "false");
        cfg.setProperty("chat.maxPerRun", "9");
        cfg.setProperty("schedule.intervalMinutes", "45");
        cfg.setProperty("schedule.maxAgeDays", "14");
        cfg.setProperty("site.baseline.Oracle", "1");
        try (var w = Files.newBufferedWriter(dir.resolve("config.properties"))) { cfg.store(w, null); }
        Files.writeString(dir.resolve("sites.tsv"), "Oracle\thttps://example.com/o.xml\tRSS\ttrue\tfalse\n");
        Files.writeString(dir.resolve("keywords.txt"), "Java\nWebLogic\n");
        Files.writeString(dir.resolve("seen.txt"), "Oracle\thttps://example.com/1\n");

        Store st = new Store();
        Channel c = st.primary();
        check("one channel after migration", st.channels().size() == 1);
        check("default channel name", c.getName().equals("資安新聞"));
        check("sites migrated", c.loadSites().size() == 1 && c.loadSites().get(0).name.equals("Oracle"));
        check("keywords migrated", c.loadKeywords().equals(List.of("Java", "WebLogic")));
        check("seen migrated", c.isSeen("Oracle", "https://example.com/1") && c.hasSeenAnyFrom("Oracle"));
        check("schedule migrated", c.getSchedule().intervalMinutes == 45 && c.getSchedule().maxAgeDays == 14);
        check("chat settings migrated", !c.isChatEnabled() && c.getMaxPushPerRun() == 9 && c.hasWebhook());
        check("top-level files moved", !Files.exists(dir.resolve("sites.tsv")) && !Files.exists(dir.resolve("seen.txt"))
                && !Files.exists(dir.resolve("keywords.txt")));
        String g = Files.readString(dir.resolve("config.properties"));
        check("global config cleaned", !g.contains("schedule.") && !g.contains("chat.") && !g.contains("site.baseline"));
        check("backup kept", Files.exists(dir.resolve("backup-before-1.5/sites.tsv"))
                && Files.readString(dir.resolve("backup-before-1.5/config.properties")).contains("schedule.intervalMinutes"));
        check("migration reported", st.migratedLegacy().contains("sites.tsv"));
        check("login ok", st.login("admin", "Passw0rd1".toCharArray()));
        check("old webhook decrypts", c.getWebhookUrl().equals(hook));

        Store again = new Store();
        check("second start: no re-migration", again.migratedLegacy().isEmpty() && again.channels().size() == 1);

        // 依舊版說明把 sites.tsv 放回最上層 → 匯入第一個頻道
        Files.writeString(dir.resolve("sites.tsv"), "A\thttps://a.example/rss\tRSS\ttrue\nB\thttps://b.example/rss\tRSS\tfalse\n");
        Store third = new Store();
        check("re-import top-level sites.tsv", third.primary().loadSites().size() == 2 && !Files.exists(dir.resolve("sites.tsv")));
        check("backup not overwritten", Files.readString(dir.resolve("backup-before-1.5/sites.tsv")).startsWith("Oracle"));
    }

    // ---------- 2. 兩個頻道、同一網站：各自比對、各自推播 ----------
    static void multiChannel(String base) throws Exception {
        Path dir = Files.createTempDirectory("v15multi");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        AppLog.init(st.logsDir(), st::getLogRetentionDays);
        st.createAccount("admin", "Passw0rd1".toCharArray());
        Channel sec = st.primary();
        Channel ai = st.createChannel("AI 新聞");
        String hookSec = "https://chat.googleapis.com/v1/spaces/SEC/messages?key=K&token=T1";
        String hookAi = "https://chat.googleapis.com/v1/spaces/AI/messages?key=K&token=T2";
        for (Channel c : List.of(sec, ai)) {
            ScheduleConfig sc = c.getSchedule();
            sc.baselineFirstRun = false;
            c.setSchedule(sc);
            c.saveSites(List.of(new Site("Mock", base + "/feed", Site.Type.RSS, true, false)));
        }
        sec.saveKeywords(List.of("Java", "Tomcat"));
        ai.saveKeywords(List.of("AI", "LLM"));
        sec.setWebhookUrl(hookSec);
        ai.setWebhookUrl(hookAi);

        List<String[]> sent = Collections.synchronizedList(new ArrayList<>());
        MonitorService ms = new MonitorService(st);
        ms.sender = (url, text) -> sent.add(new String[]{url, text});

        List<MonitorService.Hit> hs = ms.runChannel(sec, "t");
        List<MonitorService.Hit> ha = ms.runChannel(ai, "t");
        check("security hits 2 (Java+Tomcat) got " + hs.size(), hs.size() == 2);
        check("AI hits 2 (AI+LLM) got " + ha.size(), ha.size() == 2);
        check("shared article pushed to both", hs.stream().anyMatch(h -> h.item.link.endsWith("/a/1"))
                && ha.stream().anyMatch(h -> h.item.link.endsWith("/a/1")));
        check("2 pushes", sent.size() == 2);
        check("security push → security webhook with channel title",
                sent.get(0)[0].equals(hookSec) && sent.get(0)[1].startsWith("🔔 *資安新聞*") && sent.get(0)[1].contains("Tomcat 更新"));
        check("AI push → AI webhook with channel title",
                sent.get(1)[0].equals(hookAi) && sent.get(1)[1].startsWith("🔔 *AI 新聞*") && sent.get(1)[1].contains("LLM"));
        check("seen stored per channel", Files.readString(ai.dir().resolve("seen.txt")).contains("Mock\t"));
        check("no duplicates on rerun", ms.runChannel(sec, "t2").isEmpty() && ms.runChannel(ai, "t2").isEmpty());

        // runAll 只跑啟用中的頻道
        ai.clearSeen();
        sec.clearSeen();
        ai.setEnabled(false);
        sent.clear();
        ms.runAll("all");
        check("runAll skips disabled channel", sent.size() == 1 && sent.get(0)[0].equals(hookSec));
        ai.setEnabled(true);

        // 沒有 Webhook：不推播、不出錯
        Channel empty = st.createChannel("沒有Webhook");
        empty.saveSites(List.of(new Site("Mock", base + "/feed", Site.Type.RSS, true, false)));
        empty.saveKeywords(List.of("天氣"));
        ScheduleConfig esc = empty.getSchedule(); esc.baselineFirstRun = false; empty.setSchedule(esc);
        sent.clear();
        check("channel without webhook still matches", ms.runChannel(empty, "t").size() == 1 && sent.isEmpty());

        // 變更密碼：每個頻道的 Webhook 都重新加密
        st.changePassword("NewPassw0rd".toCharArray());
        Store re = new Store();
        check("login new password", re.login("admin", "NewPassw0rd".toCharArray()));
        check("all webhooks re-encrypted", re.channel("資安新聞").getWebhookUrl().equals(hookSec)
                && re.channel("ai 新聞").getWebhookUrl().equals(hookAi));
        check("channel without webhook stays empty", !re.channel("沒有Webhook").hasWebhook());

        // 排程：每個頻道各自的下次執行時間；停用的頻道沒有
        ScheduleConfig s1 = sec.getSchedule(); s1.enabled = true; s1.mode = ScheduleConfig.Mode.INTERVAL; s1.intervalMinutes = 30;
        s1.windowEnabled = false; sec.setSchedule(s1);
        ScheduleConfig s2 = ai.getSchedule(); s2.enabled = true; s2.mode = ScheduleConfig.Mode.INTERVAL; s2.intervalMinutes = 120;
        s2.windowEnabled = false; ai.setSchedule(s2);
        empty.setEnabled(false);
        SchedulerService sch = new SchedulerService(st, ms);
        sch.reschedule();
        ZonedDateTime n1 = sch.nextRunAt(sec), n2 = sch.nextRunAt(ai);
        check("per-channel next run", n1 != null && n2 != null && n2.isAfter(n1.plusMinutes(60)));
        check("disabled channel not scheduled", sch.nextRunAt(empty) == null);
        check("earliest next run", n1.equals(sch.nextRunAt()));
        sch.shutdown();

        // 重設帳號：所有頻道的 Webhook 都清除，其他設定保留
        re.resetAccount();
        Store after = new Store();
        boolean anyHook = false;
        for (Channel c : after.channels()) anyHook |= c.hasWebhook();
        check("reset clears all webhooks", !anyHook);
        check("reset keeps channels and keywords", after.channels().size() == 3
                && after.channel("AI 新聞").loadKeywords().equals(List.of("AI", "LLM")));
    }

    // ---------- 3. 頻道管理 ----------
    static void channelManagement() throws Exception {
        Path dir = Files.createTempDirectory("v15mgmt");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        check("fresh install: default channel with default sites",
                st.channels().size() == 1 && st.primary().getName().equals("資安新聞") && st.primary().loadSites().size() == 7
                        && st.primary().loadKeywords().equals(List.of("Java", "Tomcat", "OpenJDK")));
        Channel b = st.createChannel("AI 新聞");
        Channel c = st.createChannel("Linux");
        check("new channel empty", b.loadSites().isEmpty() && b.loadKeywords().isEmpty() && b.isEnabled());
        boolean dupErr = false;
        try { st.createChannel("ai 新聞"); } catch (IllegalArgumentException e) { dupErr = true; }
        check("duplicate name rejected (case-insensitive)", dupErr);
        boolean blankErr = false;
        try { st.createChannel("  "); } catch (IllegalArgumentException e) { blankErr = true; }
        check("blank name rejected", blankErr);
        check("lookup by id and name", st.channel(b.id()) == b && st.channel("linux") == c && st.channel("nope") == null);
        b.setName("AI 最新發展");
        Store re = new Store();
        List<String> names = new ArrayList<>();
        for (Channel x : re.channels()) names.add(x.getName());
        check("order and rename persisted " + names, names.equals(List.of("資安新聞", "AI 最新發展", "Linux")));
        re.deleteChannel(re.channel("AI 最新發展"));
        check("deleted channel dir removed", !Files.exists(dir.resolve("channels").resolve(b.id())));
        Store re2 = new Store();
        check("delete persisted", re2.channels().size() == 2);
        Channel d = re2.createChannel("新頻道");
        check("id not reused after delete", !d.id().equals(c.id()));
        re2.deleteChannel(re2.channel("Linux"));
        re2.deleteChannel(re2.channel("新頻道"));
        boolean lastErr = false;
        try { re2.deleteChannel(re2.primary()); } catch (IllegalStateException e) { lastErr = true; }
        check("cannot delete last channel", lastErr && re2.channels().size() == 1);
    }

    // ---------- 4. 關鍵字清單（貼上匯入） ----------
    static void parseList() {
        List<String> l = KeywordMatcher.parseList("Java\r\nTomcat\n\n  OpenJDK  \njava\nSpring Boot, Log4j，WebLogic、JBoss;Struts；Nginx\n");
        check("parseList " + l, l.equals(List.of("Java", "Tomcat", "OpenJDK", "Spring Boot", "Log4j", "WebLogic", "JBoss", "Struts", "Nginx")));
        check("parseList empty", KeywordMatcher.parseList("  \n\n ").isEmpty() && KeywordMatcher.parseList(null).isEmpty());
    }

    static void deleteTree(Path p) throws Exception {
        try (var w = Files.walk(p)) {
            for (Path x : w.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
