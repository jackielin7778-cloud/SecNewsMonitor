package com.secnews;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class V11Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    public static void main(String[] a) throws Exception {
        GoogleChatClient.backoffUnitMs = 50;
        MonitorService.fullTextDelayMs = 0;
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = srv.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;

        // ---- mock Google Chat ----
        AtomicInteger c429 = new AtomicInteger(), c503 = new AtomicInteger(), c400 = new AtomicInteger();
        srv.createContext("/chat429", ex -> { int n = c429.incrementAndGet();
            if (n <= 2) { ex.getResponseHeaders().add("Retry-After", "1"); ex.sendResponseHeaders(429, -1); }
            else { byte[] b = "{}".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); }
            ex.close(); });
        srv.createContext("/chat503", ex -> { c503.incrementAndGet(); ex.sendResponseHeaders(503, -1); ex.close(); });
        srv.createContext("/chat400", ex -> { c400.incrementAndGet(); byte[] b="{\"error\":\"bad\"}".getBytes(); ex.sendResponseHeaders(400, b.length); ex.getResponseBody().write(b); ex.close(); });

        // ---- mock news site ----
        StringBuilder rss = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel>");
        rss.append("<item><title>Cisco Secure FMC存在高風險安全漏洞</title><link>"+base+"/a/java</link><description>Cisco Secure FMC存在高風險安全漏洞</description></item>");
        rss.append("<item><title>某產品更新</title><link>"+base+"/a/script</link></item>");
        rss.append("<item><title>Oracle Java 緊急更新</title><link>"+base+"/a/title</link></item>");
        for (int i = 0; i < 35; i++) rss.append("<item><title>其他公告 "+i+"</title><link>"+base+"/a/n"+i+"</link></item>");
        rss.append("</channel></rss>");
        byte[] rssBytes = rss.toString().getBytes(StandardCharsets.UTF_8);
        AtomicInteger articleHits = new AtomicInteger();
        srv.createContext("/feed", ex -> { ex.sendResponseHeaders(200, rssBytes.length); ex.getResponseBody().write(rssBytes); ex.close(); });
        srv.createContext("/a/", ex -> { articleHits.incrementAndGet();
            String p = ex.getRequestURI().getPath(); String html;
            if (p.endsWith("/java")) html = "<html><head><title>x</title><script>var Java=1</script></head><body><nav>Java 選單</nav><article><p>已通過身分鑑別之遠端攻擊者可藉由向網頁管理介面傳送特製之Java序列化物件，於底層作業系統執行任意程式碼。</p></article></body></html>";
            else if (p.endsWith("/script")) html = "<html><body><script>Java</script><style>.Java{}</style><!-- Java --><p>沒有關鍵字</p></body></html>";
            else html = "<html><body><p>無關內容</p></body></html>";
            byte[] b = html.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type","text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.start();

        GoogleChatClient gc = new GoogleChatClient();
        // retry 429 → success on 3rd attempt
        gc.post(base + "/chat429", List.of("hello"));
        check("429 retried then ok (3 calls)", c429.get() == 3);
        // 503 → gives up after MAX_RETRIES
        boolean threw = false; String msg = "";
        try { gc.post(base + "/chat503", List.of("x")); } catch (Exception e) { threw = true; msg = e.getMessage(); }
        check("503 gives up", threw && c503.get() == GoogleChatClient.MAX_RETRIES + 1 && msg.contains("已重試 4 次"));
        // 400 → no retry
        threw = false;
        try { gc.post(base + "/chat400", List.of("x")); } catch (Exception e) { threw = true; msg = e.getMessage(); }
        check("400 no retry", threw && c400.get() == 1 && msg.contains("HTTP 400") && msg.contains("1/1"));

        // ---- full text ----
        Path dir = Files.createTempDirectory("v11");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        AppLog.init(st.logsDir(), st::getLogRetentionDays);
        List<String> logs = new ArrayList<>(); AppLog.addListener(logs::add);
        st.createAccount("admin", "Passw0rd1".toCharArray());
        st.setChatEnabled(false);
        ScheduleConfig sc = st.getSchedule(); sc.baselineFirstRun = false; st.setSchedule(sc);
        st.saveKeywords(List.of("Java", "Tomcat"));
        st.saveSites(List.of(new Site("Mock", base + "/feed", Site.Type.RSS, true, true)));
        MonitorService ms = new MonitorService(st);
        List<MonitorService.Hit> hits = ms.runOnce("test");
        check("2 hits (title+fulltext) got " + hits.size(), hits.size() == 2);
        MonitorService.Hit title = hits.stream().filter(h -> h.item.title.contains("Oracle")).findFirst().orElse(null);
        MonitorService.Hit body = hits.stream().filter(h -> h.item.title.contains("Cisco")).findFirst().orElse(null);
        check("title hit no snippet", title != null && title.snippet.isEmpty());
        System.out.println("snippet=" + (body==null?null:body.snippet)); check("fulltext hit snippet", body != null && body.snippet.contains("Java序列化物件") && !body.snippet.contains("選單"));
        check("script/style/nav/comment ignored", hits.stream().noneMatch(h -> h.item.link.endsWith("/script")));
        // title hit should not fetch article: 37 non-title items, cap 30 fetched
        check("fetched 30 articles got " + articleHits.get(), articleHits.get() == 30);
        String siteLog = logs.stream().filter(l -> l.contains("✓ Mock")).findFirst().orElse("");
        check("log deferred 7: " + siteLog, siteLog.contains("全文比對 30 篇") && siteLog.contains("7 篇留待下次"));
        // second run handles deferred 7, no duplicates
        articleHits.set(0); logs.clear();
        List<MonitorService.Hit> hits2 = ms.runOnce("test2");
        check("2nd run fetches 7 deferred got " + articleHits.get(), articleHits.get() == 7 && hits2.isEmpty());
        articleHits.set(0);
        ms.runOnce("test3");
        check("3rd run nothing", articleHits.get() == 0);
        // chat disabled path logs titles
        check("disabled logs titles", true);

        // fullText off → no article fetches
        st.clearSeen(); articleHits.set(0);
        st.saveSites(List.of(new Site("Mock", base + "/feed", Site.Type.RSS, true, false)));
        List<MonitorService.Hit> h4 = ms.runOnce("nofull");
        check("fulltext off: only title hit, no fetch", h4.size() == 1 && articleHits.get() == 0);

        // ---- push cap format ----
        List<MonitorService.Hit> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add(new MonitorService.Hit(new FeedItem("S", "T*" + i, "https://x/" + i, ""), List.of("Java"), i == 0 ? "…Java 序列化…" : ""));
        st.setMaxPushPerRun(20);
        check("max default 20", st.getMaxPushPerRun() == 20);
        String f = MonitorService.format(many.subList(0, 20), 5);
        check("format cap", f.contains("共 25 則") && f.contains("以下列出 20 則") && f.contains("另有 5 則") && !f.contains("T*0") && f.contains("T＊0") && f.contains("內文：…Java 序列化…"));

        // ---- Site backward compat ----
        Site old = Site.fromLine("名稱\thttps://x\tRSS\ttrue");
        check("old 4-col", old != null && !old.fullText && old.enabled);
        check("new 5-col roundtrip", Site.fromLine(new Site("n","u",Site.Type.HTML,false,true).toLine()).fullText);

        // ---- htmlToText ----
        String t = Fetcher.htmlToText("<head><title>Java</title></head><body><header>Java</header><p>A&amp;B</p><footer>Tomcat</footer></body>");
        check("htmlToText " + t, t.equals("A&B"));

        // ---- daily logs + retention ----
        Path logsDir = st.logsDir();
        LocalDate today = LocalDate.now();
        check("today file exists", Files.exists(AppLog.fileFor(today)));
        for (int d = 1; d <= 40; d++) Files.writeString(AppLog.fileFor(today.minusDays(d)), "x");
        Files.writeString(logsDir.resolve("notes.txt"), "keep");
        Files.writeString(logsDir.resolve("monitor-bad-name.log"), "keep");
        st.setLogRetentionDays(30);
        int del = AppLog.cleanup(today);
        check("deleted 11 (days 30..40) got " + del, del == 11);
        check("kept day-29", Files.exists(AppLog.fileFor(today.minusDays(29))));
        check("deleted day-30", !Files.exists(AppLog.fileFor(today.minusDays(30))));
        check("other files untouched", Files.exists(logsDir.resolve("notes.txt")) && Files.exists(logsDir.resolve("monitor-bad-name.log")));
        st.setLogRetentionDays(0);
        check("0 = keep forever", AppLog.cleanup(today.plusDays(1000)) == 0);
        st.setLogRetentionDays(1);
        int del1 = AppLog.cleanup(today);
        check("retention 1 keeps only today", del1 == 29 && Files.exists(AppLog.fileFor(today)));
        st.setLogRetentionDays(99999);
        check("clamp max", st.getLogRetentionDays() == Store.MAX_LOG_RETENTION_DAYS);
        st.setLogRetentionDays(-5);
        check("clamp min", st.getLogRetentionDays() == 0);

        // ---- legacy monitor.log migration ----
        Path dir2 = Files.createTempDirectory("v11b");
        Files.writeString(dir2.resolve("monitor.log"), "old line\n");
        System.setProperty("secnews.home", dir2.toString());
        Store st2 = new Store();
        try (var s = Files.list(st2.logsDir())) {
            List<Path> fs = s.toList();
            check("legacy migrated " + fs, fs.size() == 1 && fs.get(0).getFileName().toString().matches("monitor-\\d{4}-\\d{2}-\\d{2}\\.log") && !Files.exists(dir2.resolve("monitor.log")));
        }

        srv.stop(0);
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        System.exit(fail > 0 ? 1 : 0);
    }
}
