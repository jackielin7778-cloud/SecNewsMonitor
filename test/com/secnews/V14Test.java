package com.secnews;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** v1.4.1：抓取網站時的暫時性錯誤重試；版本資訊。 */
public class V14Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    public static void main(String[] a) throws Exception {
        Fetcher.retryBaseMs = 50;
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        AppLog.addListener(logs::add);

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        byte[] rss = "<rss><channel><item><title>Tomcat</title><link>https://x/1</link></item></channel></rss>"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, AtomicInteger> hits = new HashMap<>();
        // 前 n 次回傳 status，之後正常
        java.util.function.BiConsumer<String, int[]> route = (path, spec) -> {
            AtomicInteger c = new AtomicInteger();
            hits.put(path, c);
            srv.createContext(path, ex -> {
                int n = c.incrementAndGet();
                if (n <= spec[0]) {
                    if (spec[1] == 429) ex.getResponseHeaders().add("Retry-After", "1");
                    ex.sendResponseHeaders(spec[1], -1);
                } else {
                    ex.sendResponseHeaders(200, rss.length);
                    ex.getResponseBody().write(rss);
                }
                ex.close();
            });
        };
        route.accept("/502once", new int[]{1, 502});
        route.accept("/503twice", new int[]{2, 503});
        route.accept("/504always", new int[]{99, 504});
        route.accept("/404", new int[]{99, 404});
        route.accept("/429once", new int[]{1, 429});
        route.accept("/html502", new int[]{1, 502});
        srv.start();

        Fetcher f = new Fetcher();
        // 1 次 502 → 重試成功
        List<FeedItem> r1 = f.fetch(new Site("S502", base + "/502once", Site.Type.RSS, true));
        check("502 once → success", r1.size() == 1 && hits.get("/502once").get() == 2);
        check("retry logged", logs.stream().anyMatch(l -> l.contains("S502：HTTP 502") && l.contains("重試（第 1 次）")));
        // 2 次 503 → 第 2 次重試成功
        check("503 twice → success", f.fetch(new Site("S503", base + "/503twice", Site.Type.RSS, true)).size() == 1
                && hits.get("/503twice").get() == 3);
        // 一直 504 → 共 3 次後放棄，訊息含重試次數
        String msg = "";
        try { f.fetch(new Site("S504", base + "/504always", Site.Type.RSS, true)); } catch (Exception e) { msg = e.getMessage(); }
        check("504 gives up after 2 retries: " + msg, msg.equals("HTTP 504（已重試 2 次）") && hits.get("/504always").get() == 3);
        // 404 不重試
        msg = "";
        try { f.fetch(new Site("S404", base + "/404", Site.Type.RSS, true)); } catch (Exception e) { msg = e.getMessage(); }
        check("404 no retry", msg.equals("HTTP 404") && hits.get("/404").get() == 1);
        // 429 + Retry-After
        long t0 = System.currentTimeMillis();
        check("429 retry-after", f.fetch(new Site("S429", base + "/429once", Site.Type.RSS, true)).size() == 1);
        check("429 waited Retry-After ~1s", System.currentTimeMillis() - t0 >= 900);
        // HTML 模式也重試
        String html = "x";
        srv.removeContext("/html502");
        AtomicInteger hc = new AtomicInteger();
        srv.createContext("/html502", ex -> {
            if (hc.incrementAndGet() == 1) { ex.sendResponseHeaders(502, -1); ex.close(); return; }
            byte[] b = "<html><body><a href=\"/n/1\">一則夠長的資安新聞標題</a></body></html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        check("html mode retries", f.fetch(new Site("H", base + "/html502", Site.Type.HTML, true)).size() == 1 && hc.get() == 2);
        // 全文比對抓文章頁：不重試（避免拖慢）
        AtomicInteger ac = new AtomicInteger();
        srv.createContext("/article", ex -> { ac.incrementAndGet(); ex.sendResponseHeaders(502, -1); ex.close(); });
        boolean threw = false;
        try { f.fetchArticleText(base + "/article"); } catch (Exception e) { threw = true; }
        check("article fetch no retry", threw && ac.get() == 1);
        // 連線被拒 → 重試後放棄
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) { deadPort = s.getLocalPort(); }
        msg = "";
        try { f.fetch(new Site("Dead", "http://127.0.0.1:" + deadPort + "/x", Site.Type.RSS, true)); }
        catch (Exception e) { msg = e.getMessage(); }
        check("connection refused retried: " + msg, msg.contains("（已重試 2 次）")
                && logs.stream().anyMatch(l -> l.contains("Dead：") && l.contains("重試（第 2 次）")));
        srv.stop(0);

        // 版本
        check("version dev when not in jar", AppInfo.version().equals("開發版") && AppInfo.versionLabel().equals("開發版"));

        System.out.println("V14 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail > 0 ? 1 : 0);
    }
}
