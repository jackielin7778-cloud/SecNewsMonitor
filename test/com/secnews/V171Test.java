package com.secnews;

import com.sun.net.httpserver.HttpServer;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** v1.7.1：外部安全報告各項目的回歸測試（含報告中的 PoC）。 */
public class V171Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } if (n.startsWith("H1") || n.startsWith("M7")) System.out.println("  " + n); }

    public static void main(String[] a) throws Exception {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("V171 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static long ms(Runnable r) { long t = System.nanoTime(); r.run(); return (System.nanoTime() - t) / 1_000_000; }

    static void run() throws Exception {
        System.setProperty("secnews.allowPrivate", "false");
        Site site = new Site("T", "https://ex.com/news/", Site.Type.HTML, true);

        // ---------- H1：線性時間 ----------
        String poc = "<a href=\"x\">".repeat(20000);                              // 報告 PoC：約 340 KB，原版 47 秒
        check("H1 PoC size", poc.length() > 200_000);
        long t1 = ms(() -> Fetcher.parseHtml(site, poc.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        check("H1 unterminated <a> fast (" + t1 + " ms)", t1 < 2000);
        String poc2 = "<a href=\"x\">abcdefghijk".repeat(20000) + "</a>";
        long t1b = ms(() -> Fetcher.parseHtml(site, poc2.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        check("H1 one closing at end fast (" + t1b + " ms)", t1b < 2000);
        String poc3 = "<script>".repeat(20000) + "<!--".repeat(20000) + "<".repeat(100000) + "<nav ".repeat(20000);
        long t1c = ms(() -> Fetcher.htmlToText(poc3));
        check("H1 htmlToText unterminated blocks fast (" + t1c + " ms)", t1c < 2000);
        String poc4 = "<script type=\"application/ld+json\">".repeat(20000);
        long t1d = ms(() -> { try { ProductMonitor.parseMomo(poc4); } catch (Exception ignored) {} });
        check("H1 momo JSON-LD unterminated fast (" + t1d + " ms)", t1d < 2000);
        // 行為不變
        List<FeedItem> hs = Fetcher.parseHtml(site, ("<ul><li><A HREF='/n/1#top'>Tomcat 重大漏洞修補公告</A></li>"
                + "<li><a class=x href=\"https://other.com/p?a=1&amp;b=2\"><b>Java</b> 更新 說明 文件</a></li>"
                + "<li><a href=\"JavaScript:alert(1)\">惡意連結文字很長很長</a></li>"
                + "<li><a href=\"data:text/html,x\">資料網址連結文字很長</a></li>"
                + "<li><a href=\"/short\">短</a></li></ul>").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        check("H1 anchors parsed", hs.size() == 2 && hs.get(0).link.equals("https://ex.com/n/1")
                && hs.get(0).title.equals("Tomcat 重大漏洞修補公告") && hs.get(1).link.equals("https://other.com/p?a=1&b=2")
                && hs.get(1).title.equals("Java 更新 說明 文件"));
        check("L5 JavaScript: / data: excluded (case-insensitive)", hs.stream().noneMatch(f -> f.title.contains("連結文字")));
        String txt = Fetcher.htmlToText("<html><head><title>x</title></head><body><!-- c --><NAV>menu</NAV><script>var a=1;</script >"
                + "<p>內文 Tomcat</p><style>p{}</style><footer>f</footer></body></html>");
        check("H1 htmlToText result", txt.equals("內文 Tomcat"));

        // ---------- L1：實體只解碼一次 ----------
        check("L1 no double decode", Fetcher.stripTags("&amp;lt;script&amp;gt;").equals("&lt;script&gt;"));
        check("L1 numeric entities", Fetcher.stripTags("A&#65;&#x42;&nbsp;C &unknown; D").equals("AAB C &unknown; D"));

        // ---------- L2／L3／L4：RSS ----------
        String rss = "<rss><channel><item><title>A&nbsp;B &copy; &foo; C\nInjected line</title><link>https://ex.com/1</link></item>"
                + "<item><title>Second</title><link>https://ex.com/2</link></item></channel></rss>";
        List<FeedItem> ri = Fetcher.parseFeed(site, rss.getBytes(StandardCharsets.UTF_8));
        check("L2 undeclared entities do not break feed", ri.size() == 2);
        check("L2/L3 title text", ri.get(0).title.equals("A B © &foo; C Injected line"));
        String atomP = "<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\"><atom:entry><atom:title>Prefixed</atom:title>"
                + "<atom:link href=\"https://ex.com/p\"/><atom:updated>2026-10-27T10:00:00Z</atom:updated></atom:entry></atom:feed>";
        List<FeedItem> ap = Fetcher.parseFeed(site, atomP.getBytes(StandardCharsets.UTF_8));
        check("L4 prefixed Atom", ap.size() == 1 && ap.get(0).title.equals("Prefixed") && ap.get(0).link.equals("https://ex.com/p")
                && ap.get(0).published != null);
        String rdf = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns=\"http://purl.org/rss/1.0/\" "
                + "xmlns:d=\"http://purl.org/dc/elements/1.1/\"><item><title>RDF</title><link>https://ex.com/r</link>"
                + "<d:date>2026-10-27T10:00:00Z</d:date></item></rdf:RDF>";
        List<FeedItem> rd = Fetcher.parseFeed(site, rdf.getBytes(StandardCharsets.UTF_8));
        check("L4 RDF + custom dc prefix", rd.size() == 1 && rd.get(0).published != null);

        // ---------- H2：Chat 訊息注入 ----------
        FeedItem evil = new FeedItem("網站", "<users/all> 緊急 *粗體* <https://evil.example|https://bank.example> _x_ ~y~ `z`‮gnp.exe",
                "javascript:alert(1)", "");
        String msg = MonitorService.format("頻道", List.of(new MonitorService.Hit(evil, List.of("Java"), "內文\n\n<users/all> 第二段")), 0);
        check("H2 no mention", !msg.contains("<users/all>") && msg.contains("＜users/all＞"));
        check("H2 no custom link", !msg.contains("<https://") && !msg.contains("|https://bank"));
        check("H2 markup escaped", msg.contains("＊粗體＊") && msg.contains("＿x＿") && msg.contains("～y～") && msg.contains("｀z｀"));
        check("H2 bidi removed", !msg.contains("‮"));
        check("H2 javascript link dropped", !msg.toLowerCase().contains("javascript:") && msg.contains("連結格式不符"));
        check("H2 snippet single line", msg.contains("內文：內文 ＜users/all＞ 第二段"));
        check("H2 block count", GoogleChatClient.split(msg).size() == 1 || msg.split("\n\n").length == 2);
        check("H2 link encoded", ChatText.link("https://a.com/x y|<z>").equals("https://a.com/x%20y%7C%3Cz%3E"));
        check("H2 link scheme case", ChatText.link("HTTPS://A.com/").equals("HTTPS://A.com/") && ChatText.link("ftp://a").isEmpty());
        FeedItem pe = new FeedItem("iPhone", "<users/all>商品*", "https://24h.pchome.com.tw/prod/X", "NT$1｜PChome");
        String pm = MonitorService.formatProducts("比價", List.of(new MonitorService.Hit(pe, List.of("新商品"), "")), 0);
        check("H2 product name escaped", !pm.contains("<users/all>") && pm.contains("＜users/all＞商品＊"));
        check("H2 title length capped", ChatText.oneLine("長".repeat(5000), MonitorService.TITLE_MAX).length() <= MonitorService.TITLE_MAX + 1);

        // ---------- H3：SSRF ----------
        String[] blocked = {"http://127.0.0.1/", "http://localhost/", "http://10.1.2.3/", "http://172.16.0.1/", "http://192.168.1.1/",
                "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://[::ffff:127.0.0.1]/", "http://[fd00::1]/",
                "http://100.64.0.1/", "http://0.0.0.0/", "file:///etc/passwd", "ftp://example.com/", "gopher://x/", "http://u:p@8.8.8.8/"};
        for (String u : blocked) {
            boolean b;
            try { SafeUrl.check(u); b = false; } catch (java.io.IOException e) { b = true; }
            check("H3 blocked " + u, b);
        }
        check("H3 public IP allowed", SafeUrl.check("https://8.8.8.8/").getHost().equals("8.8.8.8"));
        check("H3 public v6 allowed", !SafeUrl.isBlocked(InetAddress.getByName("2001:4860:4860::8888")));

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = srv.getAddress().getPort();
        AtomicInteger loops = new AtomicInteger();
        AtomicBoolean internalHit = new AtomicBoolean();
        srv.createContext("/redir-internal", ex -> { ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/secret"); ex.sendResponseHeaders(302, -1); ex.close(); });
        srv.createContext("/secret", ex -> { internalHit.set(true); send(ex, "secret"); });
        srv.createContext("/loop", ex -> { loops.incrementAndGet(); ex.getResponseHeaders().add("Location", "/loop"); ex.sendResponseHeaders(301, -1); ex.close(); });
        srv.createContext("/ok", ex -> { ex.getResponseHeaders().add("Location", "/final"); ex.sendResponseHeaders(302, -1); ex.close(); });
        srv.createContext("/final", ex -> send(ex, "<rss><channel><item><title>T</title><link>https://ex.com/f</link></item></channel></rss>"));
        srv.createContext("/slow", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("<rss>".getBytes()); ex.getResponseBody().flush();
            try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
            ex.close();
        });
        srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        srv.start();
        Fetcher f = new Fetcher();
        Fetcher.retryBaseMs = 10;
        SafeUrl.testAllowHosts = java.util.Set.of("localhost");
        String lb = "http://localhost:" + port;
        try {
            boolean rej;
            String why = "";
            try { f.fetch(new Site("R", lb + "/redir-internal", Site.Type.RSS, true)); rej = false; }
            catch (Exception e) { rej = true; why = String.valueOf(e.getMessage()); }
            check("H3 redirect to internal rejected (" + why + ")", rej && why.contains("轉址被拒絕") && !internalHit.get());
            try { f.fetch(new Site("L", lb + "/loop", Site.Type.RSS, true)); rej = false; } catch (Exception e) { rej = true; why = e.getMessage(); }
            check("H3 redirect loop capped", rej && why.contains("轉址次數過多") && loops.get() == Fetcher.MAX_REDIRECTS + 1);
            List<FeedItem> okItems = f.fetch(new Site("OK", lb + "/ok", Site.Type.RSS, true));
            check("H3 normal redirect still works", okItems.size() == 1);
            boolean direct;
            try { f.fetchArticleText("http://127.0.0.1:" + port + "/secret"); direct = false; } catch (Exception e) { direct = true; }
            check("H3 full-text link to internal rejected", direct && !internalHit.get());

            // ---------- M7：讀取總時限 ----------
            Fetcher.bodyTimeoutSec = 2;
            long t0 = System.currentTimeMillis();
            boolean timedOut;
            try { f.fetch(new Site("S", lb + "/slow", Site.Type.RSS, true)); timedOut = false; }
            catch (Exception e) { timedOut = true; why = String.valueOf(e.getMessage()); }
            long el = System.currentTimeMillis() - t0;
            check("M7 slow body aborted (" + el + " ms, " + why + ")", timedOut && el < 10000);
        } finally {
            Fetcher.bodyTimeoutSec = 90;
            SafeUrl.testAllowHosts = java.util.Set.of();
            srv.stop(0);
        }

        // ---------- H4／M6／L7：帳號與金鑰 ----------
        System.setProperty("secnews.allowPrivate", "true");
        Path dir = Files.createTempDirectory("v171");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        AppLog.init(st.logsDir(), st::getLogRetentionDays);
        st.createAccount("admin", "Passw0rd1".toCharArray());
        Properties cfg = load(dir.resolve("config.properties"));
        check("L7 new account 600k", "600000".equals(cfg.getProperty("account.iterations")) && "600000".equals(cfg.getProperty("account.keyIterations")));
        Channel c1 = st.primary();
        Channel c2 = st.createChannel("第二");
        String hook = "https://chat.googleapis.com/v1/spaces/A/messages?key=K&token=T";
        c1.setWebhookUrl(hook);
        c2.setWebhookUrl(hook + "2");

        // H4：changePassword 與 getWebhookUrl 同時進行不會死結
        AtomicBoolean stop = new AtomicBoolean();
        Thread reader = new Thread(() -> { while (!stop.get()) { c1.getWebhookUrl(); c2.hasWebhook(); } });
        reader.setDaemon(true);
        reader.start();
        Thread changer = new Thread(() -> {
            try { for (int i = 0; i < 3; i++) st.changePassword(("Passw0rd" + (i + 2)).toCharArray()); } catch (Exception e) { e.printStackTrace(); }
        });
        changer.setDaemon(true);
        changer.start();
        changer.join(60000);
        stop.set(true);
        reader.join(5000);
        long[] dl = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        check("H4 no deadlock", !changer.isAlive() && dl == null);
        check("H4 hooks intact after password changes", c1.getWebhookUrl().equals(hook) && c2.getWebhookUrl().equals(hook + "2"));

        // M6 情境 A：第 1 階段後中斷（暫存欄位是新金鑰加密，但帳號沒換）→ 舊密碼登入，Webhook 正常、暫存丟棄
        c1.stagePendingWebhook(Crypto.encrypt(Crypto.deriveKey("Other1234".toCharArray(), new byte[16], 1000), "https://chat.googleapis.com/x"));
        Store s2 = new Store();
        check("M6-A login", s2.login("admin", "Passw0rd4".toCharArray()));
        check("M6-A webhook intact", s2.primary().getWebhookUrl().equals(hook)
                && !load(s2.primary().dir().resolve("channel.properties")).containsKey("chat.webhook.enc.pending"));
        // M6 情境 B：帳號已換、第 3 階段前中斷 → 新密碼登入時把暫存欄位換成正式欄位
        Properties before = load(s2.primary().dir().resolve("channel.properties"));
        String oldEnc = before.getProperty("chat.webhook.enc");
        s2.changePassword("Passw0rd9".toCharArray());
        Path cp = s2.primary().dir().resolve("channel.properties");
        Properties after = load(cp);
        after.setProperty("chat.webhook.enc.pending", after.getProperty("chat.webhook.enc"));
        after.setProperty("chat.webhook.enc", oldEnc);                                      // 模擬尚未換成新密文
        try (var w = Files.newBufferedWriter(cp, StandardCharsets.UTF_8)) { after.store(w, null); }
        Store s3 = new Store();
        check("M6-B login new password", s3.login("admin", "Passw0rd9".toCharArray()));
        check("M6-B webhook recovered", s3.primary().getWebhookUrl().equals(hook));

        // L7：舊帳號（210,000 次）登入時自動升級，Webhook 仍可解密
        Path dir2 = Files.createTempDirectory("v171legacy");
        System.setProperty("secnews.home", dir2.toString());
        Store ls = new Store();
        byte[] salt = Crypto.randomBytes(16), keySalt = Crypto.randomBytes(16);
        char[] pw = "Legacy123".toCharArray();
        Properties lc = load(dir2.resolve("config.properties"));
        lc.setProperty("account.user", "old");
        lc.setProperty("account.salt", Crypto.b64(salt));
        lc.setProperty("account.iterations", "210000");
        lc.setProperty("account.hash", Crypto.b64(Crypto.pbkdf2(pw, salt, 210000, 256)));
        lc.setProperty("account.keySalt", Crypto.b64(keySalt));
        try (var w = Files.newBufferedWriter(dir2.resolve("config.properties"), StandardCharsets.UTF_8)) { lc.store(w, null); }
        Path lcp = ls.primary().dir().resolve("channel.properties");
        Properties lch = load(lcp);
        lch.setProperty("chat.webhook.enc", Crypto.encrypt(Crypto.deriveKey(pw, keySalt, 210000), hook));
        try (var w = Files.newBufferedWriter(lcp, StandardCharsets.UTF_8)) { lch.store(w, null); }
        Store ls2 = new Store();
        check("L7 legacy login", ls2.login("old", pw));
        Properties up = load(dir2.resolve("config.properties"));
        check("L7 upgraded to 600k", "600000".equals(up.getProperty("account.iterations")) && "600000".equals(up.getProperty("account.keyIterations")));
        check("L7 webhook still decrypts", ls2.primary().getWebhookUrl().equals(hook));
        check("L7 login again after upgrade", new Store().login("old", pw));

        // ---------- M3：推播失敗不遺失 ----------
        System.setProperty("secnews.home", dir.toString());
        Store ms0 = new Store();
        ms0.login("admin", "Passw0rd9".toCharArray());
        HttpServer feed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        feed.createContext("/rss", ex -> send(ex, "<rss><channel><item><title>Tomcat 漏洞</title><link>https://ex.com/t1</link></item>"
                + "<item><title>Java 更新</title><link>https://ex.com/t2</link></item><item><title>Java 再更新</title><link>https://ex.com/t3</link></item></channel></rss>"));
        feed.start();
        try {
            Channel nc = ms0.primary();
            nc.saveSites(List.of(new Site("F", "http://127.0.0.1:" + feed.getAddress().getPort() + "/rss", Site.Type.RSS, true)));
            nc.saveKeywords(List.of("Tomcat", "Java"));
            ScheduleConfig sc = nc.getSchedule(); sc.baselineFirstRun = false; sc.maxAgeDays = 0; nc.setSchedule(sc);
            nc.setMaxPushPerRun(2);
            MonitorService m = new MonitorService(ms0);
            List<String> sent = new ArrayList<>();
            AtomicBoolean failNext = new AtomicBoolean(true);
            m.sender = (u, t) -> { if (failNext.getAndSet(false)) throw new java.io.IOException("Chat 503"); sent.add(t); };
            check("M3 run1 hits", m.runChannel(nc, "t1").size() == 3);
            check("M3 run1 nothing sent", sent.isEmpty());
            check("M3 run2 re-sends", m.runChannel(nc, "t2").size() == 3 && sent.size() == 1);
            check("M3 run3 overflow sent", m.runChannel(nc, "t3").size() == 1 && sent.size() == 2);
            check("M3 run4 nothing left", m.runChannel(nc, "t4").isEmpty());
        } finally {
            feed.stop(0);
        }

        // ---------- M8／L9：命令列 ----------
        Path pf = Files.createTempFile("pw", ".txt");
        Files.writeString(pf, "Passw0rd9\n");
        try {
            Files.setPosixFilePermissions(pf, PosixFilePermissions.fromString("rw-r--r--"));
            boolean refused;
            try { Cli.passwordFromConfiguredSource(new String[]{"--run-once", "--password-file", pf.toString()}); refused = false; }
            catch (java.io.IOException e) { refused = e.getMessage().contains("chmod 600"); }
            check("M8 world-readable password file refused", refused);
            Files.setPosixFilePermissions(pf, PosixFilePermissions.fromString("rw-------"));
            check("M8 600 accepted", new String(Cli.passwordFromConfiguredSource(new String[]{"--password-file", pf.toString()})).equals("Passw0rd9"));
        } catch (UnsupportedOperationException e) {
            pass++;   // 不支援 POSIX 權限的系統
        }
        check("L9 command anywhere", Cli.parse(new String[]{"--channel", "資安", "--run-once"}) == Cli.Command.RUN_ONCE);
        check("L9 value not taken as command", Cli.parse(new String[]{"--channel", "--status"}) == Cli.Command.HELP);
        // SpotBugs SECUNI：非 ASCII 主機名稱不可通過 Webhook 驗證
        for (String bad : new String[]{"https://chat.googleap\u0131s.com/v1/x", "https://chat.google\u212Apis.com/v1/x"}) {
            boolean rej;
            try { GoogleChatClient.validate(bad); rej = false; } catch (IllegalArgumentException e) { rej = true; }
            check("webhook unicode host rejected", rej);
        }
        check("L9 unknown → help", Cli.parse(new String[]{"--foo"}) == Cli.Command.HELP);
    }

    static Properties load(Path p) throws Exception {
        Properties x = new Properties();
        if (Files.exists(p)) try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { x.load(r); }
        return x;
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
