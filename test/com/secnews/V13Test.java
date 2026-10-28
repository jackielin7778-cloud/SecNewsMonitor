package com.secnews;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** v1.3：排程執行時段、RSS 串流解析（大型 feed）。請以 -Xmx96m 執行，與正式環境相同。 */
public class V13Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    static final ZoneId TZ = ZoneId.of("Asia/Taipei");
    static ZonedDateTime at(int y, int mo, int d, int h, int mi) { return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, TZ); }

    public static void main(String[] a) throws Exception {
        // ================= 1. 排程：執行時段 =================
        // 2026-10-28 是週三
        ScheduleConfig c = new ScheduleConfig();
        c.mode = ScheduleConfig.Mode.INTERVAL; c.intervalMinutes = 60;
        c.windowEnabled = true; c.windowStart = LocalTime.of(8, 0); c.windowEnd = LocalTime.of(18, 0);
        c.intervalDays = EnumSet.allOf(DayOfWeek.class);
        check("in window normal", c.nextRun(at(2026,10,28,10,0)).equals(at(2026,10,28,11,0)));
        check("end inclusive", c.nextRun(at(2026,10,28,17,0)).equals(at(2026,10,28,18,0)));
        check("after end → next day start", c.nextRun(at(2026,10,28,17,30)).equals(at(2026,10,29,8,0)));
        check("night → today start", c.nextRun(at(2026,10,28,2,0)).equals(at(2026,10,28,8,0)));
        check("late night → tomorrow start", c.nextRun(at(2026,10,28,23,30)).equals(at(2026,10,29,8,0)));

        // 平日限定：週五 17:30 → 下週一 08:00
        c.intervalDays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        check("fri evening → mon", c.nextRun(at(2026,10,30,17,30)).equals(at(2026,11,2,8,0)));
        check("sat → mon", c.nextRun(at(2026,10,31,12,0)).equals(at(2026,11,2,8,0)));

        // 跨夜：22:00～06:00，每天
        c.intervalDays = EnumSet.allOf(DayOfWeek.class);
        c.windowStart = LocalTime.of(22, 0); c.windowEnd = LocalTime.of(6, 0);
        check("overnight before midnight", c.nextRun(at(2026,10,28,22,30)).equals(at(2026,10,28,23,30)));
        check("overnight after midnight", c.nextRun(at(2026,10,29,3,0)).equals(at(2026,10,29,4,0)));
        check("overnight ends → tonight 22:00", c.nextRun(at(2026,10,29,5,30)).equals(at(2026,10,29,22,0)));
        check("overnight daytime → 22:00", c.nextRun(at(2026,10,29,12,0)).equals(at(2026,10,29,22,0)));
        // 跨夜＋只有週五：週六凌晨屬於週五的時段
        c.intervalDays = EnumSet.of(DayOfWeek.FRIDAY);
        check("overnight sat early belongs to fri", c.isAllowed(at(2026,10,31,3,0)));
        check("overnight fri early not allowed (thu not selected)", !c.isAllowed(at(2026,10,30,3,0)));

        // 時段關閉，只限星期
        c.windowEnabled = false; c.intervalDays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        check("no window, weekend → mon 00:00", c.nextRun(at(2026,10,31,9,0)).equals(at(2026,11,2,0,0)));
        // 時段與星期都不限：與舊版相同
        c.intervalDays = EnumSet.allOf(DayOfWeek.class);
        check("unrestricted = old behaviour", c.nextRun(at(2026,10,28,23,30)).equals(at(2026,10,29,0,30)));
        // 開始＝結束：視為整天
        c.windowEnabled = true; c.windowStart = LocalTime.of(9, 0); c.windowEnd = LocalTime.of(9, 0);
        check("start==end → all day", c.nextRun(at(2026,10,28,23,30)).equals(at(2026,10,29,0,30)));
        // 沒選任何一天
        c.intervalDays = EnumSet.noneOf(DayOfWeek.class);
        check("no days → null", c.nextRun(at(2026,10,28,10,0)) == null);

        // 儲存／讀取、describe、sameAs
        ScheduleConfig s1 = new ScheduleConfig();
        s1.windowEnabled = true; s1.windowStart = LocalTime.of(8, 30); s1.windowEnd = LocalTime.of(19, 0);
        s1.intervalDays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        Properties p = new Properties(); s1.save(p);
        ScheduleConfig s2 = ScheduleConfig.load(p);
        check("roundtrip", s2.windowEnabled && s2.windowStart.equals(LocalTime.of(8, 30))
                && s2.windowEnd.equals(LocalTime.of(19, 0)) && s2.intervalDays.equals(s1.intervalDays));
        check("sameAs equal", s1.sameAs(s2));
        s2.windowEnd = LocalTime.of(20, 0);
        check("sameAs differs", !s1.sameAs(s2));
        check("describe " + s1.describe(), s1.describe().equals("每 60 分鐘，08:30～19:00，平日（週一～五）"));
        ScheduleConfig s3 = new ScheduleConfig(); s3.windowEnabled = true;
        s3.windowStart = LocalTime.of(22, 0); s3.windowEnd = LocalTime.of(6, 0);
        check("describe overnight", s3.describe().contains("（跨夜）"));
        // 舊設定檔（沒有 window 欄位）→ 不限時段、每天
        Properties old = new Properties();
        old.setProperty("schedule.mode", "INTERVAL"); old.setProperty("schedule.intervalMinutes", "60");
        ScheduleConfig legacy = ScheduleConfig.load(old);
        check("legacy config unrestricted", !legacy.windowEnabled && legacy.intervalDays.size() == 7);

        // ================= 2. RSS 串流解析 =================
        Site site = new Site("T", "https://x", Site.Type.RSS, true);
        // RDF + dc:date + content:encoded + 巢狀 Atom xhtml
        String rdf = "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns=\"http://purl.org/rss/1.0/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">"
                + "<channel><title>ch</title><link>https://ch</link></channel>"
                + "<item><title>RDF Java</title><link>https://r/1</link><dc:date>2026-10-27T02:00:00Z</dc:date>"
                + "<content:encoded><![CDATA[<p>Tomcat &amp; more</p>]]></content:encoded></item></rdf:RDF>";
        FeedItem ri = Fetcher.parseFeed(site, rdf.getBytes(StandardCharsets.UTF_8)).get(0);
        check("rdf title/link", ri.title.equals("RDF Java") && ri.link.equals("https://r/1"));
        check("rdf dc:date", Instant.parse("2026-10-27T02:00:00Z").equals(ri.published));
        check("rdf content:encoded summary " + ri.summary, ri.summary.equals("Tomcat & more"));
        String atomX = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>f</title><entry><title type=\"html\">A &lt;b&gt;x&lt;/b&gt;</title>"
                + "<link rel=\"self\" href=\"https://self\"/><link rel=\"alternate\" href=\"https://alt\"/>"
                + "<content type=\"xhtml\"><div xmlns=\"http://www.w3.org/1999/xhtml\"><p>OpenJDK <b>21</b></p></div></content>"
                + "<updated>2026-10-27T02:00:00Z</updated></entry></feed>";
        FeedItem ai = Fetcher.parseFeed(site, atomX.getBytes(StandardCharsets.UTF_8)).get(0);
        check("atom alternate link", ai.link.equals("https://alt"));
        check("atom xhtml content " + ai.summary, ai.summary.equals("OpenJDK 21"));
        check("atom updated used", ai.published != null);
        // RSS 的 <atom:link> 不應蓋掉 <link>
        String mixed = "<rss xmlns:atom=\"http://www.w3.org/2005/Atom\"><channel><item><title>t</title>"
                + "<atom:link href=\"https://wrong\"/><link>https://right</link></item></channel></rss>";
        check("atom:link ignored in rss", Fetcher.parseFeed(site, mixed.getBytes()).get(0).link.equals("https://right"));
        // 欄位截斷
        String huge = "<rss><channel><item><title>t</title><link>https://h</link><description>"
                + "x".repeat(100_000) + "</description></item></channel></rss>";
        check("field truncated", Fetcher.parseFeed(site, huge.getBytes()).get(0).summary.length() == Fetcher.MAX_FIELD_CHARS);
        // DOCTYPE 但沒有實體：可解析（舊 RSS 0.91 常見）
        String rss091 = "<?xml version=\"1.0\"?><!DOCTYPE rss PUBLIC \"-//Netscape Communications//DTD RSS 0.91//EN\" "
                + "\"http://my.netscape.com/publish/formats/rss-0.91.dtd\"><rss version=\"0.91\"><channel>"
                + "<item><title>old</title><link>https://o</link></item></channel></rss>";
        check("rss 0.91 doctype without fetching DTD", Fetcher.parseFeed(site, rss091.getBytes()).size() == 1);
        // XXE 參數實體
        String xxe2 = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY % p SYSTEM \"file:///etc/passwd\"> %p;]>"
                + "<rss><channel><item><title>t</title></item></channel></rss>";
        boolean leaked;
        try {
            List<FeedItem> li = Fetcher.parseFeed(site, xxe2.getBytes());
            leaked = !li.isEmpty() && li.get(0).title.contains("root:");
        } catch (Exception e) { leaked = false; }
        check("parameter entity XXE not resolved", !leaked);

        // ---- 大型 feed：本機伺服器以串流方式送出約 30 MB / 40,000 則 ----
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        final int N = 40_000;
        final String desc = "Microsoft security update for Windows Server. ".repeat(14);   // ~660 bytes
        srv.createContext("/big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=utf-8");
            ex.sendResponseHeaders(200, 0);   // chunked
            try (OutputStream os = ex.getResponseBody()) {
                os.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel>".getBytes());
                for (int i = 0; i < N; i++) {
                    String t = (i == 7 ? "Apache Tomcat CVE fix" : "Update " + i);
                    os.write(("<item><title>" + t + "</title><link>" + base + "/a/" + i + "</link><description>"
                            + desc + "</description><pubDate>Tue, 27 Oct 2026 02:00:00 GMT</pubDate></item>").getBytes());
                }
                os.write("</channel></rss>".getBytes());
            }
        });
        srv.createContext("/toobig", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write("<rss><channel>".getBytes());
                byte[] chunk = ("<item><title>x</title><description>" + "y".repeat(8000) + "</description></item>").getBytes();
                long sent = 0;
                while (sent < Fetcher.MAX_FEED_BYTES + 1_000_000) { os.write(chunk); sent += chunk.length; }
                os.write("</channel></rss>".getBytes());
            } catch (Exception ignored) { }   // 用戶端中斷連線
        });
        srv.start();

        Runtime rt = Runtime.getRuntime();
        Fetcher fetcher = new Fetcher();
        long t0 = System.currentTimeMillis();
        List<FeedItem> bigItems = fetcher.fetch(new Site("Big", base + "/big", Site.Type.RSS, true));
        long ms = System.currentTimeMillis() - t0;
        long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        System.out.println("big feed: " + bigItems.size() + " items, " + ms + " ms, heap used ~" + used + " MB of max "
                + rt.maxMemory() / 1024 / 1024 + " MB");
        check("big feed all items", bigItems.size() == N);
        check("big feed keyword item", bigItems.get(7).title.equals("Apache Tomcat CVE fix"));
        check("big feed dates", bigItems.get(N - 1).published != null);

        boolean limited = false; String msg = "";
        try { fetcher.fetch(new Site("Huge", base + "/toobig", Site.Type.RSS, true)); }
        catch (Exception e) { limited = e.getMessage() != null && e.getMessage().contains("50 MB"); msg = String.valueOf(e.getMessage()); }
        check("over 50 MB rejected: " + msg, limited);

        // 完整監控流程：大型 feed 不會造成重複推播
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("v13");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        AppLog.init(st.logsDir(), st::getLogRetentionDays);
        st.createAccount("u", "Passw0rd1".toCharArray());
        st.setChatEnabled(false);
        ScheduleConfig sc = st.getSchedule(); sc.baselineFirstRun = false; sc.maxAgeDays = 0; st.setSchedule(sc);
        st.saveKeywords(List.of("Tomcat"));
        st.saveSites(List.of(new Site("Big", base + "/big", Site.Type.RSS, true)));
        MonitorService ms1 = new MonitorService(st);
        check("monitor big hit 1", ms1.runOnce("big1").size() == 1);
        check("monitor big no dup", ms1.runOnce("big2").isEmpty());

        srv.stop(0);
        System.out.println("V13 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail > 0 ? 1 : 0);
    }
}
