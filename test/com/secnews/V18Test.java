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
            m.sender = (u, t) -> { throw new java.io.IOException("chat down"); };
            m.lineSender = (cc, t) -> got.add("LINE:" + t);
            m.mailSender = (cc, subj, t) -> got.add("MAIL:" + subj + "|" + t);
            check("run1 hit", m.runChannel(c, "t1").size() == 1);
            check("line + mail sent though chat failed", got.size() == 2);
            check("plain text: no bold markers", got.stream().noneMatch(x -> x.contains("*")) && got.get(0).contains("＊重大＊"));
            check("mail subject", got.get(1).startsWith("MAIL:[SecNewsMonitor] "));
            check("not re-pushed when one channel succeeded", m.runChannel(c, "t2").isEmpty());
            rss.set("<rss><channel><item><title>Tomcat 二號</title><link>https://ex.com/2</link></item></channel></rss>");
            m.lineSender = (cc, t) -> { throw new java.io.IOException("line down"); };
            m.mailSender = (cc, s, t) -> { throw new java.io.IOException("mail down"); };
            check("all fail run", m.runChannel(c, "t3").size() == 1);
            m.sender = (u, t) -> got.add("CHAT:" + t);
            check("re-pushed after all failed", m.runChannel(c, "t4").size() == 1 && got.get(got.size() - 1).startsWith("CHAT:"));
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
