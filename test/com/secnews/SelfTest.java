package com.secnews;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class SelfTest {
    static int pass, fail;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + name); } }

    public static void main(String[] a) throws Exception {
        ZoneId tz = ZoneId.of("Asia/Taipei");
        // --- Schedule ---
        ScheduleConfig c = new ScheduleConfig();
        ZonedDateTime now = ZonedDateTime.of(2026,10,27,19,56,0,0,tz); // Tue
        c.mode = ScheduleConfig.Mode.INTERVAL; c.intervalMinutes = 60;
        check("interval", c.nextRun(now).equals(now.plusMinutes(60)));
        c.intervalMinutes = 1;
        check("interval min 5", c.nextRun(now).equals(now.plusMinutes(5)));
        c.mode = ScheduleConfig.Mode.DAILY; c.time = LocalTime.of(9,0);
        check("daily tomorrow", c.nextRun(now).equals(ZonedDateTime.of(2026,10,28,9,0,0,0,tz)));
        c.time = LocalTime.of(21,0);
        check("daily today", c.nextRun(now).equals(ZonedDateTime.of(2026,10,27,21,0,0,0,tz)));
        c.mode = ScheduleConfig.Mode.WEEKLY; c.time = LocalTime.of(9,0); c.days = EnumSet.of(DayOfWeek.MONDAY);
        check("weekly next mon", c.nextRun(now).equals(ZonedDateTime.of(2026,11,2,9,0,0,0,tz)));
        c.days = EnumSet.of(DayOfWeek.TUESDAY); c.time = LocalTime.of(20,0);
        check("weekly today later", c.nextRun(now).equals(ZonedDateTime.of(2026,10,27,20,0,0,0,tz)));
        c.time = LocalTime.of(19,0);
        check("weekly same day passed -> next week", c.nextRun(now).equals(ZonedDateTime.of(2026,11,3,19,0,0,0,tz)));
        c.days = EnumSet.noneOf(DayOfWeek.class);
        check("weekly empty null", c.nextRun(now) == null);
        c.enabled = false; check("disabled null", c.nextRun(now) == null);
        Properties p = new Properties(); ScheduleConfig s2 = new ScheduleConfig(); s2.mode = ScheduleConfig.Mode.WEEKLY;
        s2.days = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY); s2.time = LocalTime.of(7,30); s2.save(p);
        ScheduleConfig s3 = ScheduleConfig.load(p);
        check("schedule roundtrip", s3.mode == s2.mode && s3.days.equals(s2.days) && s3.time.equals(s2.time));

        // --- Keyword ---
        KeywordMatcher m = new KeywordMatcher(List.of("Java", "Tomcat", "憑證", "OpenJDK"));
        check("java word", m.match(new FeedItem("s","Oracle Java 漏洞","l","")).equals(List.of("Java")));
        check("no javascript", m.match(new FeedItem("s","JavaScript 框架更新","l","")).isEmpty());
        check("case", m.match(new FeedItem("s","apache TOMCAT fix","l","")).equals(List.of("Tomcat")));
        check("cjk", m.match(new FeedItem("s","eMudhra 憑證外洩","l","")).equals(List.of("憑證")));
        check("java with CJK neighbor", m.match(new FeedItem("s","修補Java漏洞","l","")).equals(List.of("Java")));
        check("summary", m.match(new FeedItem("s","t","l","影響 OpenJDK 21")).equals(List.of("OpenJDK")));

        // --- RSS / Atom ---
        String rss = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>x</title>"
            + "<item><title>Java 緊急更新</title><link>https://ex.com/1</link><description><![CDATA[<p>JSSE &amp; TLS</p>]]></description></item>"
            + "<item><title>Other</title><guid>https://ex.com/2</guid></item></channel></rss>";
        Site site = new Site("T","https://ex.com/feed",Site.Type.RSS,true);
        List<FeedItem> items = Fetcher.parseFeed(site, rss.getBytes(StandardCharsets.UTF_8));
        check("rss count", items.size()==2);
        check("rss link", items.get(0).link.equals("https://ex.com/1") && items.get(0).summary.equals("JSSE & TLS"));
        check("rss guid", items.get(1).link.equals("https://ex.com/2"));
        String big5 = "<?xml version=\"1.0\" encoding=\"Big5\"?><rss><channel><item><title>資安新聞</title><link>https://ex.com/b</link></item></channel></rss>";
        check("rss big5", Fetcher.parseFeed(site, big5.getBytes("Big5")).get(0).title.equals("資安新聞"));
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>Tomcat CVE</title><link rel=\"alternate\" href=\"https://ex.com/a\"/><summary>s</summary></entry></feed>";
        List<FeedItem> ai = Fetcher.parseFeed(site, atom.getBytes(StandardCharsets.UTF_8));
        check("atom", ai.size()==1 && ai.get(0).link.equals("https://ex.com/a"));
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title></item></channel></rss>";
        boolean blocked; try { Fetcher.parseFeed(site, xxe.getBytes()); blocked = false; } catch (Exception e) { blocked = true; }
        check("xxe blocked", blocked);

        // --- HTML ---
        String html = "<html><head><meta charset=\"utf-8\"></head><body><a href=\"/\">首頁</a>"
            + "<a href=\"/news/123\">Fortinet 發布五大產品線資安強化更新</a><a href='https://o.com/x'><span>Node.js 與 Go 同步釋出緊急安全更新</span></a>"
            + "<a href=\"javascript:void(0)\">這是一個很長的假連結文字內容</a></body></html>";
        List<FeedItem> hi = Fetcher.parseHtml(new Site("H","https://site.tw/list/",Site.Type.HTML,true), html.getBytes(StandardCharsets.UTF_8), null);
        check("html count " + hi.size(), hi.size()==2);
        check("html resolve", hi.get(0).link.equals("https://site.tw/news/123"));
        check("html strip", hi.get(1).title.equals("Node.js 與 Go 同步釋出緊急安全更新"));

        // --- Chat ---
        check("json esc", GoogleChatClient.jsonString("a\"b\\c\nd\u0001").equals("\"a\\\"b\\\\c\\nd\\u0001\""));
        check("build url", GoogleChatClient.buildWebhookUrl("AAA","k k","t&t").equals("https://chat.googleapis.com/v1/spaces/AAA/messages?key=k+k&token=t%26t"));
        boolean rej; try { GoogleChatClient.validate("https://evil.com/x"); rej=false; } catch (IllegalArgumentException e) { rej=true; }
        check("reject non-google", rej);
        StringBuilder longText = new StringBuilder("head");
        for (int i=0;i<40;i++) longText.append("\n\n").append("x".repeat(200));
        List<String> parts = GoogleChatClient.split(longText.toString());
        check("split", parts.size()==3 && parts.stream().allMatch(x -> x.length()<=3500));

        // --- Store + crypto ---
        Path dir = Files.createTempDirectory("secnews");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        check("defaults sites", st.loadSites().size()==7);
        check("defaults kw", st.loadKeywords().equals(List.of("Java","Tomcat","OpenJDK")));
        st.createAccount("admin", "Passw0rd!".toCharArray());
        String hook = "https://chat.googleapis.com/v1/spaces/S/messages?key=K&token=SECRET";
        st.setWebhookUrl(hook);
        String cfg = Files.readString(dir.resolve("config.properties"));
        check("webhook encrypted on disk", !cfg.contains("SECRET") && !cfg.contains("Passw0rd"));
        Store st2 = new Store();
        check("bad login", !st2.login("admin", "wrong123".toCharArray()));
        check("bad user", !st2.login("root", "Passw0rd!".toCharArray()));
        check("good login", st2.login("admin", "Passw0rd!".toCharArray()));
        check("decrypt", st2.getWebhookUrl().equals(hook));
        st2.changePassword("NewPass99".toCharArray());
        Store st3 = new Store();
        check("old pw rejected", !st3.login("admin", "Passw0rd!".toCharArray()));
        check("new pw + webhook", st3.login("admin", "NewPass99".toCharArray()) && st3.getWebhookUrl().equals(hook));
        Site tab = new Site("名稱\t含tab", "https://x", Site.Type.HTML, false);
        st3.saveSites(List.of(tab));
        Site back = st3.loadSites().get(0);
        check("site tsv", back.name.equals("名稱 含tab") && back.type==Site.Type.HTML && !back.enabled);
        for (int i=0;i<5100;i++) st3.markSeen("S","k"+i);
        st3.flushSeen();
        check("seen per-site no global cap", Files.readAllLines(dir.resolve("seen.txt")).size()==5100 && st3.isSeen("S","k0") && st3.isSeen("S","k5099") && !st3.isSeen("T","k0"));

        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail>0) System.exit(1);
    }
}
