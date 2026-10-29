package com.secnews;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** v1.6：商品監控頻道（PChome 搜尋 JSON）、robots.txt、JSON 解析。 */
public class V16Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    public static void main(String[] a) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            fail++;
        }
        System.out.println("V16 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static String prods(String... items) {
        return "{\"QTime\":12,\"totalRows\":" + items.length + ",\"totalPage\":1,\"prods\":[" + String.join(",", items) + "]}";
    }

    static String p(String id, String name, int price, int orig) {
        return "{\"Id\":\"" + id + "\",\"name\":\"" + name + "\",\"price\":" + price + ",\"originPrice\":" + orig + "}";
    }

    static void run() throws Exception {
        json();
        robots();

        ProductMonitor.delayMs = 0;
        Fetcher.retryBaseMs = 10;
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> robots = new AtomicReference<>("User-agent: *\nDisallow: /private/\n");
        AtomicReference<String> lastQuery = new AtomicReference<>();
        AtomicInteger searches = new AtomicInteger();
        srv.createContext("/robots.txt", ex -> {
            byte[] b = robots.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.createContext("/search/", ex -> {
            searches.incrementAndGet();
            lastQuery.set(ex.getRequestURI().getQuery());
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.createContext("/paged/", ex -> {
            String q = ex.getRequestURI().getQuery();
            int page = Integer.parseInt(q.replaceAll(".*page=(\\d+).*", "$1"));
            List<String> items = new ArrayList<>();
            if (page == 1) for (int i = 0; i < 20; i++) items.add(p("A" + i, "Duo 保護貼 " + i, 500 + i, 500));
            else if (page == 2) { items.add(p("PH", "Apple iPhone Duo 512GB", 59900, 62900)); items.add(p("A0", "Duo 保護貼 0", 500, 500)); }
            byte[] b = prods(items.toArray(new String[0])).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.start();
        try {
            Path dir = Files.createTempDirectory("v16");
            System.setProperty("secnews.home", dir.toString());
            Store st = new Store();
            AppLog.init(st.logsDir(), st::getLogRetentionDays);
            List<String> logs = Collections.synchronizedList(new ArrayList<>());
            AppLog.addListener(logs::add);
            st.createAccount("admin", "Passw0rd1".toCharArray());
            Channel ch = st.createChannel("商品比價", Channel.Type.PRODUCT);
            check("product channel type persisted", new Store().channel("商品比價").isProduct());
            check("product channel maxAge 0", ch.getSchedule().maxAgeDays == 0);
            ch.props().setProperty("product.searchUrl", base + "/search/v3.3/all/results?q={q}&page=1");
            ch.props().setProperty("product.linkUrl", "https://24h.pchome.com.tw/prod/{id}");
            ch.saveProps();
            ch.saveProducts(List.of(
                    new ProductWatch("iPhone", "iPhone 17 Pro 256GB", "iphone 256", 40000, true),
                    new ProductWatch("停用的", "xx", "", 0, false)));
            check("products round-trip", ch.loadProducts().size() == 2 && ch.loadProducts().get(0).maxPrice == 40000
                    && !ch.loadProducts().get(1).enabled);
            String hook = "https://chat.googleapis.com/v1/spaces/P/messages?key=K&token=T";
            ch.setWebhookUrl(hook);

            List<String[]> sent = Collections.synchronizedList(new ArrayList<>());
            MonitorService ms = new MonitorService(st);
            ms.sender = (url, text) -> sent.add(new String[]{url, text});

            // 第一次：建立基準。A 高於上限、B 已低於上限 → 只通知 B；保護殼被「名稱需包含」排除
            body.set(prods(p("A1", "Apple iPhone 17 Pro 256GB 黑", 42900, 42900),
                    p("B1", "Apple iPhone 17 Pro 256GB 白（福利品）", 38000, 42900),
                    p("C1", "iPhone 17 Pro 保護殼", 590, 990)));
            List<MonitorService.Hit> h1 = ms.runChannel(ch, "t1");
            check("first run: only below-threshold notified, got " + h1.size(), h1.size() == 1 && h1.get(0).item.title.contains("白"));
            check("query url-encoded", lastQuery.get() != null && lastQuery.get().contains("iPhone 17 Pro 256GB"));
            check("disabled watch not searched", searches.get() == 1);
            check("push format", sent.size() == 1 && sent.get(0)[0].equals(hook) && sent.get(0)[1].startsWith("🛒 *商品比價*")
                    && sent.get(0)[1].contains("NT$38,000（原價 NT$42,900）") && sent.get(0)[1].contains("低於設定價格 NT$40,000")
                    && sent.get(0)[1].contains("https://24h.pchome.com.tw/prod/B1"));

            // 第二次：同樣結果 → 不重複通知
            sent.clear();
            check("no duplicate", ms.runChannel(ch, "t2").isEmpty() && sent.isEmpty());

            // 第三次：A 降價但仍高於上限；新商品 D；B 漲價
            body.set(prods(p("A1", "Apple iPhone 17 Pro 256GB 黑", 41900, 42900),
                    p("B1", "Apple iPhone 17 Pro 256GB 白（福利品）", 39500, 42900),
                    p("D1", "Apple iPhone 17 Pro 256GB 藍", 42900, 42900)));
            List<MonitorService.Hit> h3 = ms.runChannel(ch, "t3");
            MonitorService.Hit ha = find(h3, "黑"), hd = find(h3, "藍");
            check("drop notified", ha != null && ha.keywords.contains("降價 NT$42,900 → NT$41,900"));
            check("new notified", hd != null && hd.keywords.equals(List.of("新商品")));
            check("price up / still below not re-notified", find(h3, "白") == null && h3.size() == 2);

            // 第四次：A 降到上限以下 → 降價＋低於設定價格（同一則）
            body.set(prods(p("A1", "Apple iPhone 17 Pro 256GB 黑", 39900, 42900)));
            List<MonitorService.Hit> h4 = ms.runChannel(ch, "t4");
            check("drop + below combined", h4.size() == 1 && h4.get(0).keywords.size() == 2
                    && h4.get(0).keywords.get(1).startsWith("低於設定價格"));

            // 通知條件關閉
            ch.setNotify("new", false);
            body.set(prods(p("E1", "Apple iPhone 17 Pro 256GB 金", 45000, 45000)));
            check("notify new off", ms.runChannel(ch, "t5").isEmpty());
            ch.setNotify("new", true);

            // 狀態存檔、重新載入
            Store re = new Store();
            Channel rc = re.channel("商品比價");
            check("state persisted", rc.lastPrice("iPhone", "A1") == 39900 && rc.hasProductBaseline("iPhone"));

            // 刪除監控項目 → 清除其紀錄
            rc.saveProducts(List.of(new ProductWatch("其他", "x", "", 0, false)));
            new MonitorService(re).runChannel(rc, "t6");
            check("pruned removed watch", rc.lastPrice("iPhone", "A1") == null);
            rc.saveProducts(List.of(new ProductWatch("iPhone", "iPhone 17 Pro 256GB", "iphone 256", 40000, true)));

            // robots.txt 不允許 → 不抓取
            robots.set("User-agent: *\nDisallow: /search/\n");
            int before = searches.get();
            logs.clear();
            MonitorService ms2 = new MonitorService(re);
            ms2.sender = (u, t) -> {};
            ms2.runChannel(rc, "t7");
            check("robots disallow respected", searches.get() == before
                    && logs.stream().anyMatch(l -> l.contains("robots.txt 不允許")));
            robots.set("User-agent: *\nDisallow:\n");

            // 不是 JSON（網站改版）→ 錯誤訊息清楚、不當機
            body.set("<html>maintenance</html>");
            logs.clear();
            ms2.runChannel(rc, "t8");
            check("non-json handled", logs.stream().anyMatch(l -> l.contains("失敗 1 個")));

            // 測試搜尋
            body.set(prods(p("A1", "Apple iPhone 17 Pro 256GB 黑", 39900, 42900), p("C1", "iPhone 17 Pro 保護殼", 590, 990)));
            List<String> t = ms2.testProduct(rc, rc.loadProducts().get(0));
            check("testProduct marks", t.size() == 2 && t.get(0).startsWith("✓") && t.get(1).startsWith("✗"));

            // v1.6.1：排除字、最低價格
            ProductWatch ex = new ProductWatch("Duo", "Apple iPhone Duo", "", 80000, true, "保護貼 保護殼 膜", 20000);
            check("exclude word", !ex.accepts("【大螢膜PRO】Apple iPhone Duo 摺疊保護貼", 30000));
            check("min price", !ex.accepts("Apple iPhone Duo 5G 一體成型", 699)
                    && "低於最低價格".equals(ex.rejectReason("Apple iPhone Duo 5G", 699)));
            check("accepted phone", ex.accepts("Apple iPhone Duo 512GB", 59900));
            check("reason text", ex.rejectReason("iPhone Duo 保護殼", 999).contains("保護殼"));
            ProductWatch rt = ProductWatch.fromLine(ex.toLine());
            check("tsv round-trip v1.6.1", rt.exclude.equals("保護貼 保護殼 膜") && rt.minPrice == 20000 && rt.maxPrice == 80000);
            ProductWatch old = ProductWatch.fromLine("A\tq\tm\t100\ttrue");
            check("old 5-column line", old.exclude.isEmpty() && old.minPrice == 0 && old.maxPrice == 100);
            rc.saveProducts(List.of(ex));
            ch.props();
            body.set(prods(p("P1", "【大螢膜PRO】Apple iPhone Duo 摺疊保護貼", 2380, 2380),
                    p("P2", "Apple iPhone Duo 5G 一體成型-保護殼", 699, 699),
                    p("P3", "Apple iPhone Duo 512GB 黑", 59900, 62900)));
            List<String> tr = ms2.testProduct(rc, ex);
            check("test marks reasons", tr.get(0).contains("含排除字") && tr.get(1).startsWith("✗") && tr.get(2).startsWith("✓"));
            List<String[]> sent2 = Collections.synchronizedList(new ArrayList<>());
            ms2.sender = (u, x) -> sent2.add(new String[]{u, x});
            List<MonitorService.Hit> hx = ms2.runChannel(rc, "t9");
            check("only phone notified", hx.size() == 1 && hx.get(0).item.title.contains("512GB"));

            // 多頁搜尋：第 1 頁都是配件，手機在第 2 頁
            ProductMonitor.pageDelayMs = 0;
            rc.props().setProperty("product.searchUrl", base + "/paged/results?q={q}&page={page}&sort=rnk/dc");
            List<String> pg = ms2.testProduct(rc, ex);
            check("paged search finds page 2, got " + pg.size(), pg.size() == 21 && pg.stream().anyMatch(x -> x.startsWith("✓") && x.contains("512GB")));
            logs.clear();
            rc.clearSeen();
            ms2.runChannel(rc, "t10");
            check("log shows reject reasons", logs.stream().anyMatch(l -> l.contains("排除：含排除字「保護貼」 20 件")));

            // 清除已讀紀錄 → 商品基準也清除
            rc.clearSeen();
            check("clearSeen clears product state", !rc.hasProductBaseline("iPhone") && rc.lastPrice("iPhone", "A1") == null);

            // 新聞頻道不受影響
            check("news channel still news", !re.primary().isProduct());
        } finally {
            srv.stop(0);
        }
    }

    static MonitorService.Hit find(List<MonitorService.Hit> l, String s) {
        for (MonitorService.Hit h : l) if (h.item.title.contains(s)) return h;
        return null;
    }

    static void json() {
        Object o = MiniJson.parse("{\"a\":[1,2.5,-3e2,true,false,null],\"s\":\"x\\\"y\\u4e2d\\n\",\"o\":{}}");
        Map<?, ?> m = (Map<?, ?>) o;
        List<?> arr = (List<?>) m.get("a");
        check("json numbers", arr.get(0).equals(1.0) && arr.get(1).equals(2.5) && arr.get(2).equals(-300.0));
        check("json literals", arr.get(3).equals(true) && arr.get(4).equals(false) && arr.get(5) == null);
        check("json string escapes", "x\"y中\n".equals(m.get("s")));
        boolean bad = false;
        try { MiniJson.parse("{\"a\":1,}"); } catch (IllegalArgumentException e) { bad = true; }
        check("json rejects trailing comma", bad);
        // JSONP 與巢狀價格物件
        List<ProductMonitor.Product> ps = ProductMonitor.parse(
                "try{jsonp_prod({\"data\":{\"prods\":[{\"Id\":\"X-1\",\"Name\":\"商品&lt;b&gt;\",\"Price\":{\"M\":0,\"P\":11900}}]}});}catch(e){}");
        check("jsonp + nested price", ps.size() == 1 && ps.get(0).price == 11900 && ps.get(0).id.equals("X-1"));
        check("empty prods", ProductMonitor.parse("{\"prods\":null,\"totalRows\":0}").isEmpty());
        boolean notJson = false;
        try { ProductMonitor.parse("<html></html>"); } catch (IllegalArgumentException e) { notJson = true; }
        check("not json", notJson);
    }

    static void robots() {
        RobotsTxt r = RobotsTxt.parse("User-agent: Googlebot\nDisallow: /\n\nUser-agent: *\nDisallow: /search/\nAllow: /search/v3.3/\nDisallow: /*.pdf$\n");
        check("robots other agent ignored", r.allowed("/prod/A1"));
        check("robots disallow", !r.allowed("/search/v4/x?q=1"));
        check("robots longer allow wins", r.allowed("/search/v3.3/all/results?q=1"));
        check("robots wildcard $", !r.allowed("/a/b.pdf") && r.allowed("/a/b.pdfx"));
        check("robots empty disallow", RobotsTxt.parse("User-agent: *\nDisallow:\n").allowed("/x"));
    }
}
