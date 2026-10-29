package com.secnews;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class V12Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }
    static final Map<String, String> feeds = new HashMap<>();

    static String rss(List<String[]> items) {  // title, link, pubDate(nullable)
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel>");
        for (String[] i : items) {
            sb.append("<item><title>").append(i[0]).append("</title><link>").append(i[1]).append("</link>");
            if (i[2] != null) sb.append("<pubDate>").append(i[2]).append("</pubDate>");
            sb.append("</item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    public static void main(String[] a) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        srv.createContext("/f/", ex -> { byte[] b = feeds.getOrDefault(ex.getRequestURI().getPath(), "").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.start();

        // ---------- 1. 重現 bug：大量項目的網站不再擠掉別的網站的已讀紀錄 ----------
        Path dir = Files.createTempDirectory("v12");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        AppLog.init(st.logsDir(), st::getLogRetentionDays);
        List<String> logs = new ArrayList<>(); AppLog.addListener(logs::add);
        st.createAccount("u", "Passw0rd1".toCharArray());
        st.setChatEnabled(false);
        ScheduleConfig sc = st.getSchedule(); sc.baselineFirstRun = false; sc.maxAgeDays = 0; st.setSchedule(sc);
        st.saveKeywords(List.of("Java"));
        List<String[]> a1 = new ArrayList<>();
        for (int i = 0; i < 9; i++) a1.add(new String[]{"Oracle Java CPU " + i, base + "/oracle/" + i, null});
        feeds.put("/f/oracle", rss(a1));
        List<String[]> big = new ArrayList<>();
        for (int i = 0; i < 6000; i++) big.add(new String[]{"Windows update " + i, base + "/ms/" + i, null});
        feeds.put("/f/ms", rss(big));
        st.saveSites(List.of(new Site("Oracle", base + "/f/oracle", Site.Type.RSS, true),
                             new Site("MSRC", base + "/f/ms", Site.Type.RSS, true)));
        MonitorService ms = new MonitorService(st);
        check("run1 9 hits", ms.runOnce("r1").size() == 9);
        check("run2 no duplicates (bug fixed)", ms.runOnce("r2").isEmpty());
        check("run3 no duplicates", new MonitorService(new Store()).runOnce("r3").isEmpty());
        check("seen holds both sites", st.seenCount() >= 6009);

        // ---------- 2. retainSeen：文章掉出 feed 後只保留最近 2000 筆 ----------
        List<String[]> big2 = new ArrayList<>();
        for (int i = 6000; i < 6100; i++) big2.add(new String[]{"Windows update " + i, base + "/ms/" + i, null});
        feeds.put("/f/ms", rss(big2));
        ms.runOnce("r4");
        Store chk = new Store();
        int msCount = 0;
        for (String l : Files.readAllLines(dir.resolve("channels/c1/seen.txt"))) if (l.startsWith("MSRC\t")) msCount++;
        check("MSRC kept current 100 + recent 2000 = 2100, got " + msCount, msCount == 2100);
        check("most recent dropped-out item still seen", chk.isSeen("MSRC", base + "/ms/5999"));
        check("old dropped-out item pruned", !chk.isSeen("MSRC", base + "/ms/0"));
        check("oracle unaffected", chk.isSeen("Oracle", base + "/oracle/0"));

        // ---------- 3. 舊格式 seen.txt 轉換 ----------
        Path dir2 = Files.createTempDirectory("v12legacy");
        System.setProperty("secnews.home", dir2.toString());
        Store s2 = new Store();
        s2.createAccount("u", "Passw0rd1".toCharArray()); s2.setChatEnabled(false);
        ScheduleConfig c2 = s2.getSchedule(); c2.baselineFirstRun = true; c2.maxAgeDays = 0; s2.setSchedule(c2);
        s2.saveKeywords(List.of("Java"));
        s2.saveSites(List.of(new Site("Oracle", base + "/f/oracle", Site.Type.RSS, true),
                             new Site("Off", base + "/f/oracle", Site.Type.RSS, false)));
        // 模擬 v1.1：舊格式（無 tab），且兩個網站都已建立基準
        StringBuilder legacy = new StringBuilder();
        for (int i = 0; i < 9; i++) legacy.append(base).append("/oracle/").append(i).append('\n');
        Files.writeString(dir2.resolve("seen.txt"), legacy.toString());
        Files.writeString(dir2.resolve("config.properties"),
                Files.readString(dir2.resolve("config.properties")) + "site.baseline.Oracle=1\nsite.baseline.Off=1\n");
        Store s2b = new Store();
        check("legacy loaded", s2b.primary().hasLegacySeen() && s2b.isSeen("Oracle", base + "/oracle/3") && s2b.isSeen("Anything", base + "/oracle/3"));
        List<MonitorService.Hit> lh = new MonitorService(s2b).runOnce("legacy");
        check("legacy items not re-pushed", lh.isEmpty());
        check("legacy dropped", !s2b.primary().hasLegacySeen());
        List<String> lines = Files.readAllLines(dir2.resolve("channels/c1/seen.txt"));
        check("new format written", lines.size() == 9 && lines.stream().allMatch(l -> l.startsWith("Oracle\t")));
        check("disabled site baseline reset", !s2b.primary().hasSeenAnyFrom("Off") && s2b.primary().hasSeenAnyFrom("Oracle"));
        check("after migration still no dup", new MonitorService(new Store()).runOnce("legacy2").isEmpty());

        // ---------- 4. 日期過濾 ----------
        Path dir3 = Files.createTempDirectory("v12age");
        System.setProperty("secnews.home", dir3.toString());
        Store s3 = new Store();
        s3.createAccount("u", "Passw0rd1".toCharArray()); s3.setChatEnabled(false);
        ScheduleConfig c3 = s3.getSchedule(); c3.baselineFirstRun = false; c3.maxAgeDays = 30; s3.setSchedule(c3);
        s3.saveKeywords(List.of("Java"));
        String now = ZonedDateTime.now(ZoneOffset.UTC).minusDays(2).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        feeds.put("/f/age", rss(List.of(
                new String[]{"Oracle Java SE CPU - February 2012", base + "/old", "Tue, 14 Feb 2012 20:00:00 GMT"},
                new String[]{"Java 緊急更新 (new)", base + "/new", now},
                new String[]{"Java 公告 (no date)", base + "/nodate", null},
                new String[]{"Java 40 days", base + "/d40", ZonedDateTime.now(ZoneOffset.UTC).minusDays(40).format(DateTimeFormatter.RFC_1123_DATE_TIME)})));
        s3.saveSites(List.of(new Site("Age", base + "/f/age", Site.Type.RSS, true, true)));
        logs.clear();
        List<MonitorService.Hit> ah = new MonitorService(s3).runOnce("age");
        Set<String> links = new HashSet<>(); for (MonitorService.Hit h : ah) links.add(h.item.link.replace(base, ""));
        check("age filter hits new+nodate got " + links, links.equals(Set.of("/new", "/nodate")));
        check("age log", logs.stream().anyMatch(l -> l.contains("略過超過 30 天的舊文章 2 則")));
        check("old items marked seen (no fulltext fetch later)", s3.isSeen("Age", base + "/old"));
        // maxAge 0 = 不限
        s3.clearSeen(); ScheduleConfig c4 = s3.getSchedule(); c4.maxAgeDays = 0; c4.baselineFirstRun = false; s3.setSchedule(c4);
        s3.saveSites(List.of(new Site("Age", base + "/f/age", Site.Type.RSS, true, false)));
        check("maxAge 0 → all 4", new MonitorService(s3).runOnce("age0").size() == 4);
        check("maxAge persisted", new Store().getSchedule().maxAgeDays == 0);

        // ---------- 5. 日期格式 ----------
        Instant ref = Instant.parse("2026-10-27T02:00:00Z");
        check("rfc1123 GMT", ref.equals(Fetcher.parseDate("Tue, 27 Oct 2026 02:00:00 GMT")));
        check("rfc1123 +0800", ref.equals(Fetcher.parseDate("Tue, 27 Oct 2026 10:00:00 +0800")));
        check("rfc no weekday-ish spacing", ref.equals(Fetcher.parseDate("  Tue,  27 Oct 2026 02:00:00 GMT ")));
        check("EST zone", Fetcher.parseDate("Mon, 26 Oct 2026 22:00:00 EST") != null);
        check("iso offset", ref.equals(Fetcher.parseDate("2026-10-27T10:00:00+08:00")));
        check("iso Z", ref.equals(Fetcher.parseDate("2026-10-27T02:00:00Z")));
        check("local datetime", Fetcher.parseDate("2026-10-27 10:00:00") != null);
        check("date only", Fetcher.parseDate("2026/10/27") != null);
        check("garbage null", Fetcher.parseDate("not a date") == null && Fetcher.parseDate("") == null);
        // Atom published
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>t</title><link href=\"https://x/a\"/><published>2012-02-14T20:00:00Z</published><updated>2026-10-27T02:00:00Z</updated></entry></feed>";
        FeedItem ai = Fetcher.parseFeed(new Site("A", "https://x", Site.Type.RSS, true), atom.getBytes()).get(0);
        check("atom uses published", Instant.parse("2012-02-14T20:00:00Z").equals(ai.published));

        // ---------- 6. 推播格式含發布日 ----------
        String f = MonitorService.format(List.of(new MonitorService.Hit(
                new FeedItem("S", "T", "https://x", "", ref), List.of("Java"), "")), 0);
        check("format has date", f.contains("發布：2026-10-27"));

        // ---------- 7. 刪除網站的已讀紀錄會被清掉 ----------
        System.setProperty("secnews.home", dir.toString());
        Store s5 = new Store();
        s5.saveSites(List.of(new Site("Oracle", base + "/f/oracle", Site.Type.RSS, true)));
        new MonitorService(s5).runOnce("prune");
        check("deleted site MSRC pruned", Files.readAllLines(dir.resolve("channels/c1/seen.txt")).stream().noneMatch(l -> l.startsWith("MSRC\t")));

        srv.stop(0);
        System.out.println("V12 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail > 0 ? 1 : 0);
    }
}
