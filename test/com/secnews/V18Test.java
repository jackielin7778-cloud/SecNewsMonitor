package com.secnews;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** v1.8：LINE、Gmail 推播。 */
public class V18Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    public static void main(String[] a) throws Exception {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("V18 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static final String U1 = "U" + "0123456789abcdef".repeat(2), U2 = "U" + "fedcba9876543210".repeat(2);

    static void run() throws Exception {
        // ---------- 驗證 ----------
        LineClient.validateId(U1);
        boolean bad;
        try { LineClient.validateId("U123"); bad = false; } catch (IllegalArgumentException e) { bad = true; }
        check("line id invalid", bad);
        try { LineClient.validateToken("abc def"); bad = false; } catch (IllegalArgumentException e) { bad = true; }
        check("line token with space rejected", bad);
        check("app password normalized", GmailClient.normalizeAppPassword("abcd efgh ijkl mnop").equals("abcdefghijklmnop"));
        try { GmailClient.normalizeAppPassword("MyGooglePassword1!"); bad = false; } catch (IllegalArgumentException e) { bad = true; }
        check("login password rejected as app password", bad);
        try { GmailClient.validateAddress("a@b.com\r\nBcc: x@y.com"); bad = false; } catch (IllegalArgumentException e) { bad = true; }
        check("email header injection rejected", bad);
        check("splitList", Channel.splitList(" a@b.com, c@d.com;\na@b.com ").equals(List.of("a@b.com", "c@d.com")));

        // ---------- 設定加密、變更密碼、重設帳號 ----------
        System.setProperty("secnews.allowPrivate", "true");
        Path dir = Files.createTempDirectory("v18");
        System.setProperty("secnews.home", dir.toString());
        Store st = new Store();
        AppLog.init(st.logsDir(), st::getLogRetentionDays);
        st.createAccount("admin", "Passw0rd1".toCharArray());
        Channel ch = st.primary();
        ch.setLine(true, Channel.LineMode.PUSH, List.of(U1, U2), "TOKEN-abc_123");
        ch.setEmail(true, "me@gmail.com", List.of("a@ex.com", "b@ex.com"), "abcdefghijklmnop");
        String props = Files.readString(ch.dir().resolve("channel.properties"));
        check("secrets not stored in plain text", !props.contains("TOKEN-abc_123") && !props.contains("abcdefghijklmnop"));
        check("line ready", ch.isLineReady() && ch.hasAnyNotifier());
        check("email ready", ch.isEmailReady() && ch.getEmailTo().size() == 2);
        st.changePassword("Passw0rd2".toCharArray());
        Store s2 = new Store();
        check("login after change", s2.login("admin", "Passw0rd2".toCharArray()));
        check("line token survives password change", s2.primary().getLineToken().equals("TOKEN-abc_123"));
        check("gmail password survives password change", s2.primary().getEmailPassword().equals("abcdefghijklmnop"));
        s2.resetAndCreateAccount("admin2", "Passw0rd3".toCharArray());
        check("reset clears line/gmail secrets", !s2.primary().hasLineToken() && !s2.primary().hasEmailPassword()
                && !s2.primary().isLineReady() && !s2.primary().isEmailReady());

        // ---------- LINE API（本機假伺服器） ----------
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String[]> reqs = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger status = new AtomicInteger(200);
        AtomicInteger fail500 = new AtomicInteger(0);
        srv.createContext("/v2/bot/message/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reqs.add(new String[]{ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"), body,
                    ex.getRequestHeaders().getFirst("X-Line-Retry-Key")});
            int sc = fail500.getAndDecrement() > 0 ? 500 : status.get();
            byte[] b = (sc == 200 ? "{}" : "{\"message\":\"err\"}").getBytes();
            ex.sendResponseHeaders(sc, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
        LineClient.apiBase = "http://127.0.0.1:" + srv.getAddress().getPort();
        LineClient.backoffMs = 10;
        try {
            LineClient lc = new LineClient();
            String longText = ("段落" + "字".repeat(3000) + "\n\n").repeat(7);   // 7 段，每段約 3000 字 → 7 則 → 2 次請求
            lc.send("TK", Channel.LineMode.PUSH, List.of(U1, U2), longText);
            check("push requests (2 ids × 2 batches)", reqs.size() == 4 && reqs.stream().allMatch(r -> r[0].equals("/v2/bot/message/push")));
            check("bearer token", reqs.get(0)[1].equals("Bearer TK"));
            check("push body has to", reqs.get(0)[2].contains("\"to\":\"" + U1 + "\"") && reqs.get(2)[2].contains(U2));
            int msgs = reqs.get(0)[2].split("\"type\":\"text\"", -1).length - 1;
            check("max 5 messages per request (" + msgs + ")", msgs == 5);
            reqs.clear();
            lc.send("TK", Channel.LineMode.BROADCAST, List.of(), "hello");
            check("broadcast path", reqs.size() == 1 && reqs.get(0)[0].equals("/v2/bot/message/broadcast") && !reqs.get(0)[2].contains("\"to\""));
            reqs.clear();
            status.set(401);
            String msg = "";
            try { lc.send("TK", Channel.LineMode.PUSH, List.of(U1), "x"); } catch (java.io.IOException e) { msg = e.getMessage(); }
            check("401 message", msg.contains("token 無效") && reqs.size() == 1);
            reqs.clear();
            status.set(429);
            try { lc.send("TK", Channel.LineMode.PUSH, List.of(U1), "x"); } catch (java.io.IOException e) { msg = e.getMessage(); }
            check("429 not retried, quota hint", reqs.size() == 1 && msg.contains("200 則"));
            reqs.clear();
            status.set(200);
            fail500.set(1);
            lc.send("TK", Channel.LineMode.PUSH, List.of(U1), "x");
            check("500 retried with same retry key", reqs.size() == 2 && reqs.get(0)[3] != null && reqs.get(0)[3].equals(reqs.get(1)[3]));
        } finally {
            srv.stop(0);
            LineClient.apiBase = "https://api.line.me";
        }

        // ---------- Gmail SMTP（本機假伺服器，非 TLS） ----------
        AtomicReference<List<String>> session = new AtomicReference<>();
        AtomicReference<String> authReply = new AtomicReference<>("235 ok");
        ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread smtp = new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket c = ss.accept()) {
                    List<String> lines = new ArrayList<>();
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream out = c.getOutputStream();
                    java.util.function.Consumer<String> say = t -> { try { out.write((t + "\r\n").getBytes()); out.flush(); } catch (Exception ignored) {} };
                    say.accept("220 fake");
                    String l;
                    boolean data = false;
                    while ((l = in.readLine()) != null) {
                        lines.add(l);
                        if (data) { if (l.equals(".")) { data = false; say.accept("250 queued"); } continue; }
                        if (l.startsWith("EHLO")) { say.accept("250-fake"); say.accept("250 AUTH PLAIN"); }
                        else if (l.startsWith("AUTH")) { say.accept(authReply.get()); if (!authReply.get().startsWith("235")) break; }
                        else if (l.startsWith("MAIL") || l.startsWith("RCPT")) say.accept("250 ok");
                        else if (l.equals("DATA")) { say.accept("354 go"); data = true; }
                        else if (l.equals("QUIT")) { say.accept("221 bye"); break; }
                    }
                    session.set(lines);
                } catch (Exception ignored) {}
            }
        });
        smtp.setDaemon(true);
        smtp.start();
        GmailClient.host = "127.0.0.1";
        GmailClient.port = ss.getLocalPort();
        GmailClient.plainForTest = true;
        try {
            new GmailClient().send("me@gmail.com", "abcd efgh ijkl mnop", List.of("a@ex.com", "b@ex.com"),
                    "測試主旨\r\nBcc: evil@ex.com", "第一行\n.第二行以點開頭\n內容");
            Thread.sleep(200);
            List<String> L = session.get();
            String auth = new String(Base64.getDecoder().decode(L.stream().filter(x -> x.startsWith("AUTH PLAIN ")).findFirst().get().substring(11)), StandardCharsets.UTF_8);
            check("auth plain", auth.equals("\0me@gmail.com\0abcdefghijklmnop"));
            check("rcpt both", L.contains("RCPT TO:<a@ex.com>") && L.contains("RCPT TO:<b@ex.com>"));
            check("no injected Bcc header", L.stream().noneMatch(x -> x.toLowerCase().startsWith("bcc:")));
            String subj = L.stream().filter(x -> x.startsWith("Subject: ")).findFirst().orElse("");
            check("subject MIME encoded", subj.startsWith("Subject: =?UTF-8?B?"));
            int start = L.indexOf("Content-Transfer-Encoding: base64") + 2;
            StringBuilder b64 = new StringBuilder();
            for (int i = start; i < L.size() && !L.get(i).equals("."); i++) b64.append(L.get(i));
            String body = new String(Base64.getDecoder().decode(b64.toString()), StandardCharsets.UTF_8);
            check("body decoded", body.equals("第一行\r\n.第二行以點開頭\r\n內容"));
            authReply.set("535 bad");
            String msg = "";
            try { new GmailClient().send("me@gmail.com", "abcdefghijklmnop", List.of("a@ex.com"), "s", "b"); }
            catch (java.io.IOException e) { msg = e.getMessage(); }
            check("535 → app password hint", msg.contains("應用程式密碼"));
        } finally {
            GmailClient.plainForTest = false;
            GmailClient.host = "smtp.gmail.com";
            GmailClient.port = 465;
            ss.close();
        }
        // 主旨 MIME 編碼可還原
        String enc = GmailClient.encodeHeader("[SecNewsMonitor] 資安新聞：12 則新聞命中，包含很長很長很長很長很長的頻道名稱");
        StringBuilder dec = new StringBuilder();
        for (String w : enc.split("\r\n ")) dec.append(new String(Base64.getDecoder().decode(w.substring(10, w.length() - 2)), StandardCharsets.UTF_8));
        check("subject encode roundtrip", dec.toString().equals("[SecNewsMonitor] 資安新聞：12 則新聞命中，包含很長很長很長很長很長的頻道名稱"));

        // ---------- v1.8.0 資安審查修正（含 PoC） ----------
        // H1：robots.txt 萬用字元不再造成大量回溯
        RobotsTxt rt = RobotsTxt.parse("User-agent: *\nDisallow: /" + "*".repeat(20) + "Z\nDisallow: /" + "*a".repeat(200) + "Z\n");
        long t0 = System.nanoTime();
        boolean al = rt.allowed("/search/v3.3/all/results?q=iphone%2016%20pro%20max&page=1&sort=rnk/dc" + "a".repeat(2000));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("H1 robots wildcard fast (" + ms + " ms)", al && ms < 2000);
        RobotsTxt sem = RobotsTxt.parse("User-agent: *\nDisallow: /private\nDisallow: /*.json$\nAllow: /private/ok\nDisallow: /a*b*c\n");
        check("H1 semantics", !sem.allowed("/private/x") && sem.allowed("/private/ok/1") && !sem.allowed("/x/y.json")
                && sem.allowed("/x/y.json?z") && !sem.allowed("/aXXbYYc/d") && sem.allowed("/aXXcYYb") && sem.allowed("/public"));
        check("H1 rule limits", RobotsTxt.matches("/a*", "/abc") && RobotsTxt.matches("/x$", "/x") && !RobotsTxt.matches("/x$", "/xy"));

        // L1：RSS 連結中的換行不會偽造執行紀錄
        FeedItem fi = new FeedItem("s", "t", "https://evil.example/a\n2026-10-30 09:00:00  [資安新聞] 已推播", "");
        check("L1 link single line", !fi.link.contains("\n") && !fi.link.contains("\r"));

        // L2：Gmail 地址不先 trim 才驗證
        try { GmailClient.validateAddress(" a@b.com"); bad = false; } catch (IllegalArgumentException e) { bad = true; }
        check("L2 address with whitespace rejected", bad);
        check("L2 header uses validated value", GmailClient.message("me@gmail.com", List.of("c@d.com"), "s", "b").contains("To: c@d.com\r\n"));

        // M2：重設帳號中途失敗，舊帳號仍可用；重做可成功；殘留密文在建立帳號時清除
        Path dirR = Files.createTempDirectory("v18r");
        System.setProperty("secnews.home", dirR.toString());
        Store sr = new Store();
        sr.createAccount("admin", "Passw0rd1".toCharArray());
        Channel r2 = sr.createChannel("第二");
        sr.primary().setLine(true, Channel.LineMode.PUSH, List.of(U1), "TK1");
        r2.setLine(true, Channel.LineMode.PUSH, List.of(U1), "TK2");
        Path blocker = r2.dir().resolve("channel.properties.tmp");
        Files.createDirectories(blocker);                                     // 讓第二個頻道寫檔失敗
        boolean failedReset;
        try { sr.resetAndCreateAccount("new", "Passw0rd9".toCharArray()); failedReset = false; } catch (java.io.IOException e) { failedReset = true; }
        check("M2 reset failed midway", failedReset);
        Store sr2 = new Store();
        check("M2 old account still valid", sr2.hasAccount() && sr2.login("admin", "Passw0rd1".toCharArray()));
        Files.delete(blocker);
        sr2.resetAndCreateAccount("new", "Passw0rd9".toCharArray());
        check("M2 retry reset ok", new Store().login("new", "Passw0rd9".toCharArray()) && !sr2.channel("第二").hasLineToken());
        // 殘留密文（沒有帳號）→ 建立帳號時清除
        Store sr3 = new Store();
        sr3.login("new", "Passw0rd9".toCharArray());
        sr3.channel("第二").setLine(true, Channel.LineMode.PUSH, List.of(U1), "TK3");
        Properties cfg = new Properties();
        try (var rd = Files.newBufferedReader(dirR.resolve("config.properties"))) { cfg.load(rd); }
        cfg.stringPropertyNames().stream().filter(k -> k.startsWith("account.")).toList().forEach(cfg::remove);
        try (var w = Files.newBufferedWriter(dirR.resolve("config.properties"))) { cfg.store(w, null); }
        Store sr4 = new Store();
        sr4.createAccount("fresh", "Passw0rd8".toCharArray());
        check("M2 leftover ciphertext cleared on createAccount", !sr4.channel("第二").hasLineToken() && !sr4.channel("第二").isLineReady());
        // 無法解密的機密不讓變更密碼失敗
        sr4.primary().setLine(true, Channel.LineMode.PUSH, List.of(U1), "TK4");
        Path cp = sr4.channel("第二").dir().resolve("channel.properties");
        Properties cpp = new Properties();
        try (var rd = Files.newBufferedReader(cp)) { cpp.load(rd); }
        cpp.setProperty("line.token.enc", Crypto.encrypt(Crypto.deriveKey("x".toCharArray(), new byte[16], 1000), "junk"));
        try (var w = Files.newBufferedWriter(cp)) { cpp.store(w, null); }
        Store sr5 = new Store();
        sr5.login("fresh", "Passw0rd8".toCharArray());
        boolean changed;
        try { sr5.changePassword("Passw0rd7".toCharArray()); changed = true; } catch (Exception e) { changed = false; }
        check("M2 changePassword survives corrupt secret", changed && !sr5.channel("第二").hasLineToken()
                && sr5.primary().getLineToken().equals("TK4"));

        // M1、M3：LINE 慢速回應、連線錯誤以相同 Retry-Key 重試、無效 ID 先略過
        HttpServer ls = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String[]> lreq = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger dropFirst = new AtomicInteger(0);
        java.util.concurrent.atomic.AtomicBoolean slow = new java.util.concurrent.atomic.AtomicBoolean(false);
        ls.createContext("/v2/bot/message/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lreq.add(new String[]{body, ex.getRequestHeaders().getFirst("X-Line-Retry-Key")});
            if (dropFirst.getAndDecrement() > 0) { ex.close(); return; }       // 不回應就斷線
            if (slow.get()) {
                ex.sendResponseHeaders(200, 0);
                try { for (int i = 0; i < 30; i++) { ex.getResponseBody().write('x'); ex.getResponseBody().flush(); Thread.sleep(1000); } }
                catch (Exception ignored) {}
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, 2); ex.getResponseBody().write("{}".getBytes()); ex.close();
        });
        ls.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        ls.start();
        LineClient.apiBase = "http://127.0.0.1:" + ls.getAddress().getPort();
        LineClient.backoffMs = 10;
        LineClient.bodyTimeoutSec = 2;
        try {
            slow.set(true);
            long st0 = System.currentTimeMillis();
            new LineClient().send("TK", Channel.LineMode.PUSH, List.of(U1), "x");
            long el = System.currentTimeMillis() - st0;
            check("M1 slow LINE body bounded (" + el + " ms)", el < 8000);
            slow.set(false);
            lreq.clear();
            dropFirst.set(1);
            new LineClient().send("TK", Channel.LineMode.PUSH, List.of(U1), "x", "base-1");
            check("M3 connection error retried with same key", lreq.size() == 2 && lreq.get(0)[1].equals(lreq.get(1)[1])
                    && lreq.get(0)[1].equals(LineClient.retryKey("base-1", U1, 0)));
            lreq.clear();
            List<String> failedIds = new LineClient().send("TK", Channel.LineMode.PUSH, List.of(U1, "Ubad"), "x", "base-2");
            check("M3 invalid id skipped up front", lreq.size() == 1 && failedIds.isEmpty());
        } finally {
            ls.stop(0);
            LineClient.apiBase = "https://api.line.me";
            LineClient.bodyTimeoutSec = 30;
        }

        // M1：Gmail 整封寄送有總時限
        ServerSocket stall = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread stallT = new Thread(() -> {
            try (Socket c = stall.accept()) {
                OutputStream o = c.getOutputStream();
                for (int i = 0; i < 30; i++) { o.write("220-x\r\n".getBytes()); o.flush(); Thread.sleep(1000); }
            } catch (Exception ignored) {}
        });
        stallT.setDaemon(true);
        stallT.start();
        GmailClient.host = "127.0.0.1";
        GmailClient.port = stall.getLocalPort();
        GmailClient.plainForTest = true;
        GmailClient.totalTimeoutSec = 2;
        long gt0 = System.currentTimeMillis();
        String gmsg = "";
        try { new GmailClient().send("me@gmail.com", "abcdefghijklmnop", List.of("a@ex.com"), "s", "b"); }
        catch (java.io.IOException e) { gmsg = e.getMessage(); }
        finally {
            GmailClient.plainForTest = false; GmailClient.host = "smtp.gmail.com"; GmailClient.port = 465; GmailClient.totalTimeoutSec = 120;
            stall.close();
        }
        long gel = System.currentTimeMillis() - gt0;
        check("M1 gmail total timeout (" + gel + " ms, " + gmsg + ")", gel < 8000 && gmsg.contains("超過"));

        // ---------- 多管道推播 ----------
        Path dir3 = Files.createTempDirectory("v18m");
        System.setProperty("secnews.home", dir3.toString());
        Store s3 = new Store();
        s3.createAccount("admin", "Passw0rd1".toCharArray());
        HttpServer feed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> rss = new AtomicReference<>("<rss><channel><item><title>Tomcat *重大* 漏洞</title><link>https://ex.com/1</link></item></channel></rss>");
        feed.createContext("/rss", ex -> { byte[] b = rss.get().getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        feed.start();
        try {
            Channel c = s3.primary();
            c.saveSites(List.of(new Site("F", "http://127.0.0.1:" + feed.getAddress().getPort() + "/rss", Site.Type.RSS, true)));
            c.saveKeywords(List.of("Tomcat"));
            ScheduleConfig sc = c.getSchedule(); sc.baselineFirstRun = false; sc.maxAgeDays = 0; c.setSchedule(sc);
            c.setWebhookUrl("https://chat.googleapis.com/v1/spaces/A/messages?key=K&token=T");
            c.setLine(true, Channel.LineMode.PUSH, List.of(U1), "TK");
            c.setEmail(true, "me@gmail.com", List.of("a@ex.com"), "abcdefghijklmnop");
            MonitorService m = new MonitorService(s3);
            List<String> got = Collections.synchronizedList(new ArrayList<>());
            List<String> idems = Collections.synchronizedList(new ArrayList<>());
            m.sender = (u, t) -> { throw new java.io.IOException("chat down"); };
            m.lineSender = (cc, to, t, idem) -> { got.add("LINE:" + to + ":" + t); idems.add(idem); return List.of(); };
            m.mailSender = (cc, subj, t) -> got.add("MAIL:" + subj + "|" + t);
            check("run1 hit", m.runChannel(c, "t1").size() == 1);
            check("line + mail sent though chat failed", got.size() == 2);
            check("plain text: no bold markers", got.stream().noneMatch(x -> x.contains("*")) && got.get(0).contains("＊重大＊"));
            check("mail subject", got.get(1).startsWith("MAIL:[SecNewsMonitor] "));
            check("chat queued in outbox", Outbox.load(c).size() == 1 && Outbox.load(c).get(0).notifier.equals(Outbox.CHAT));
            // L3：下次執行只重送給失敗的 Google Chat，LINE、Gmail 不重複
            m.sender = (u, t) -> got.add("CHAT:" + t);
            check("run2 no new hits", m.runChannel(c, "t2").isEmpty());
            check("only chat re-sent", got.size() == 3 && got.get(2).startsWith("CHAT:") && got.get(2).contains("＊重大＊"));
            check("outbox empty after resend", Outbox.load(c).isEmpty());
            // LINE 部分收件人失敗：只對失敗的收件人重送，且沿用同一個重送基準（idempotency）
            c.setLine(true, Channel.LineMode.PUSH, List.of(U1, U2), "TK");
            rss.set("<rss><channel><item><title>Tomcat 二號</title><link>https://ex.com/2</link></item></channel></rss>");
            m.lineSender = (cc, to, t, idem) -> { got.add("LINE:" + to); idems.add(idem); return to.contains(U2) ? List.of(U2) : List.of(); };
            got.clear(); idems.clear();
            check("run3 hit", m.runChannel(c, "t3").size() == 1);
            check("line partial queued for U2 only", Outbox.load(c).size() == 1 && Outbox.load(c).get(0).to.equals(List.of(U2)));
            m.lineSender = (cc, to, t, idem) -> { got.add("LINE:" + to); idems.add(idem); return List.of(); };
            m.runChannel(c, "t4");
            check("resend only to U2", got.get(got.size() - 1).equals("LINE:[" + U2 + "]"));
            check("same idempotency base on resend", idems.size() == 2 && idems.get(0).equals(idems.get(1)));
            check("outbox empty", Outbox.load(c).isEmpty());
            // 全部失敗：三個管道都進佇列，命中項目不會重複偵測
            rss.set("<rss><channel><item><title>Tomcat 三號</title><link>https://ex.com/3</link></item></channel></rss>");
            m.sender = (u, t) -> { throw new java.io.IOException("chat down"); };
            m.lineSender = (cc, to, t, idem) -> { throw new java.io.IOException("line down"); };
            m.mailSender = (cc, s, t) -> { throw new java.io.IOException("mail down"); };
            check("all fail run", m.runChannel(c, "t5").size() == 1 && Outbox.load(c).size() == 3);
            check("hit not detected again", m.runChannel(c, "t6").isEmpty() && Outbox.load(c).size() == 3);
            // 過期：超過 3 天的捨棄
            List<Outbox.Entry> old = new ArrayList<>();
            for (Outbox.Entry e : Outbox.load(c)) old.add(new Outbox.Entry(e.id, e.notifier, e.created - 4 * 86400L, e.to, e.subject, e.text));
            Outbox.save(c, old);
            m.runChannel(c, "t7");
            check("expired entries dropped", Outbox.load(c).isEmpty());
            c.setChatEnabled(false);
            c.setLine(false, Channel.LineMode.PUSH, List.of(U1), "TK");
            c.setEmail(false, "me@gmail.com", List.of("a@ex.com"), "abcdefghijklmnop");
            check("no notifier", !c.hasAnyNotifier());
        } finally {
            feed.stop(0);
        }

        // ---------- 畫面可建立（無視窗模式） ----------
        try {
            Class<?> np = Class.forName("com.secnews.ui.NotifyPanel");
            var ctor = np.getDeclaredConstructor(Channel.class, MonitorService.class, Runnable.class);
            ctor.setAccessible(true);
            ctor.newInstance(s3.primary(), new MonitorService(s3), (Runnable) () -> {});
            pass++;
        } catch (java.awt.HeadlessException e) {
            pass++;
        }
    }
}
