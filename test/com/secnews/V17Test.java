package com.secnews;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** v1.7：momo（JSON-LD）、露天（搜尋＋商品 JSON）、多網站比價、補貨通知。 */
public class V17Test {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    public static void main(String[] a) throws Exception {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("V17 PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static String momoItem(String code, String name, int price, String av) {
        return "{\"@type\":\"Product\",\"name\":\"" + name + "\",\"position\":1,\"url\":\"https://www.momoshop.com.tw/goods/GoodsDetail.jsp?i_code="
                + code + "&Area=search&kw=x\",\"offers\":{\"@type\":\"Offer\",\"price\":" + price
                + ",\"priceCurrency\":\"TWD\",\"availability\":\"https://schema.org/" + av + "\"}}";
    }

    static String momoPage(String... items) {
        return "<html><head><script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"WebPage\",\"@graph\":["
                + "{\"@type\":\"BreadcrumbList\",\"itemListElement\":[]},{\"@type\":\"ItemList\",\"name\":\"x\",\"itemListElement\":["
                + String.join(",", items) + "]}]}</script></head><body>…</body></html>";
    }

    static String rutenProd(String id, String name, int price, int qty) {
        return "{\"ProdId\":\"" + id + "\",\"ProdName\":\"" + name + "\",\"PriceRange\":[" + price + "," + price + "],\"StockQty\":" + qty + ",\"StockStatus\":1}";
    }

    static void run() throws Exception {
        // ---- 解析 ----
        List<ProductMonitor.Product> m = ProductMonitor.parseMomo(momoPage(
                momoItem("15685652", "【Apple】iPhone 18 Pro(256G/6.3吋)", 44900, "InStock"),
                momoItem("15684492", "【Apple】iPhone 18 Pro Max(256G/6.9吋)", 49900, "OutOfStock")));
        check("momo parse", m.size() == 2 && m.get(0).id.equals("15685652") && m.get(0).price == 44900
                && m.get(0).site.equals("momo") && Boolean.TRUE.equals(m.get(0).inStock) && Boolean.FALSE.equals(m.get(1).inStock));
        boolean noLd = false;
        try { ProductMonitor.parseMomo("<html>blocked</html>"); } catch (IllegalArgumentException e) { noLd = e.getMessage().contains("JSON-LD"); }
        check("momo no JSON-LD → clear error", noLd);
        check("ruten search ids", ProductMonitor.parseRutenSearch("{\"TotalRows\":2,\"Rows\":[{\"Id\":\"22611235364421\",\"DienData\":{\"Price\":25600}},{\"Id\":\"22613345935654\"}]}")
                .equals(List.of("22611235364421", "22613345935654")));
        List<ProductMonitor.Product> r = ProductMonitor.parseRutenProds("[" + rutenProd("22611235364421", "iPhone 17e 256", 25600, 5)
                + "," + rutenProd("2", "缺貨品", 100, 0) + "]");
        check("ruten prods", r.size() == 2 && r.get(0).price == 25600 && r.get(0).site.equals("ruten")
                && Boolean.TRUE.equals(r.get(0).inStock) && Boolean.FALSE.equals(r.get(1).inStock));
        check("state id prefix", r.get(0).stateId().equals("ruten:22611235364421") && new ProductMonitor.Product("A", "n", 1, 0).stateId().equals("A"));

        // ---- 本機假網站 ----
        ProductMonitor.delayMs = 0;
        ProductMonitor.pageDelayMs = 0;
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        AtomicReference<String> pchome = new AtomicReference<>("{\"prods\":[]}");
        AtomicReference<String> momo = new AtomicReference<>();
        AtomicReference<String> rutenProds = new AtomicReference<>();
        List<String> momoPages = Collections.synchronizedList(new ArrayList<>());
        srv.createContext("/pchome/", ex -> send(ex, pchome.get()));
        srv.createContext("/momo/", ex -> { momoPages.add(ex.getRequestURI().getQuery()); send(ex, momo.get()); });
        srv.createContext("/ruten/search", ex -> send(ex, "{\"TotalRows\":2,\"Rows\":[{\"Id\":\"R1\"},{\"Id\":\"R2\"}]}"));
        srv.createContext("/ruten/prod", ex -> send(ex, rutenProds.get()));
        srv.start();
        try {
            Path dir = Files.createTempDirectory("v17");
            System.setProperty("secnews.home", dir.toString());
            Store st = new Store();
            AppLog.init(st.logsDir(), st::getLogRetentionDays);
            st.createAccount("admin", "Passw0rd1".toCharArray());
            Channel ch = st.createChannel("比價", Channel.Type.PRODUCT);
            check("default site pchome", ch.getProductSites().equals(List.of("pchome")));
            ch.props().setProperty("product.searchUrl", base + "/pchome/r?q={q}&page={page}");
            ch.props().setProperty("product.momo.searchUrl", base + "/momo/{q}?searchType=1&curPage={page}");
            ch.props().setProperty("product.ruten.searchUrl", base + "/ruten/search?q={q}&offset={offset}");
            ch.props().setProperty("product.ruten.prodUrl", base + "/ruten/prod?id={ids}");
            ch.saveProps();
            ch.setProductSites(List.of("pchome", "momo", "ruten"));
            check("sites saved", new Store().channel("比價").getProductSites().equals(List.of("pchome", "momo", "ruten")));
            ch.setWebhookUrl("https://chat.googleapis.com/v1/spaces/X/messages?key=K&token=T");
            ch.saveProducts(List.of(new ProductWatch("iPhone 18 Pro", "iPhone 18 Pro", "iphone 18", 46000, true, "殼 貼", 20000)));

            pchome.set("{\"prods\":[{\"Id\":\"P1\",\"name\":\"Apple iPhone 18 Pro 256GB\",\"price\":45900,\"originPrice\":45900}]}");
            momo.set(momoPage(momoItem("M1", "【Apple】iPhone 18 Pro(256G/6.3吋)", 44900, "OutOfStock"),
                    momoItem("M2", "【Apple】iPhone 18 Pro(256G)(超值殼貼組)", 45200, "InStock")));
            rutenProds.set("[" + rutenProd("R1", "iPhone 18 Pro 256 全新", 43000, 0) + "," + rutenProd("R2", "iPhone 18 保護殼", 300, 9) + "]");

            List<String[]> sent = Collections.synchronizedList(new ArrayList<>());
            MonitorService ms = new MonitorService(st);
            ms.sender = (u, t) -> sent.add(new String[]{u, t});

            List<MonitorService.Hit> h1 = ms.runChannel(ch, "t1");
            check("first run: below-threshold on all 3 sites, got " + h1.size(), h1.size() == 3);
            check("momo page 1 only (fewer than 30)", momoPages.size() == 1 && momoPages.get(0).contains("curPage=1"));
            String msg = sent.get(0)[1];
            check("message shows site + out of stock", msg.contains("｜momo（缺貨）") && msg.contains("｜PChome") && msg.contains("｜露天（缺貨）")
                    && msg.contains("https://www.momoshop.com.tw/goods/GoodsDetail.jsp?i_code=M1")
                    && msg.contains("https://www.ruten.com.tw/item/show?R1") && msg.contains("https://24h.pchome.com.tw/prod/P1"));
            check("exclude applied across sites", !msg.contains("超值殼貼組") && !msg.contains("保護殼"));

            // 補貨：momo M1、露天 R1 變成有貨
            momo.set(momoPage(momoItem("M1", "【Apple】iPhone 18 Pro(256G/6.3吋)", 44900, "InStock")));
            rutenProds.set("[" + rutenProd("R1", "iPhone 18 Pro 256 全新", 43000, 3) + "]");
            List<MonitorService.Hit> h2 = ms.runChannel(ch, "t2");
            check("restock on momo + ruten, got " + h2.size(), h2.size() == 2 && h2.stream().allMatch(h -> h.keywords.equals(List.of("補貨"))));
            check("restock not repeated", ms.runChannel(ch, "t3").isEmpty());

            // 狀態檔：庫存保存；PChome 沿用舊格式
            Channel rc = new Store().channel("比價");
            check("stock persisted", Boolean.TRUE.equals(rc.lastStock("iPhone 18 Pro", "momo:M1")) && rc.lastPrice("iPhone 18 Pro", "P1") == 45900);
            check("per-site baseline", rc.hasProductBaseline("iPhone 18 Pro") && rc.hasProductBaseline("iPhone 18 Pro@momo")
                    && rc.hasProductBaseline("iPhone 18 Pro@ruten"));

            // 新增網站時，該網站第一次只建立基準（不會把現有商品當新商品）
            ch.setNotify("restock", false);
            ch.setProductSites(List.of("pchome"));
            ch.clearSeen();
            ms.runChannel(ch, "t4");
            ch.setProductSites(List.of("pchome", "momo"));
            momo.set(momoPage(momoItem("M9", "【Apple】iPhone 18 Pro(1TB)", 69900, "InStock")));
            check("newly added site baselines first", ms.runChannel(ch, "t5").isEmpty());

            // 測試搜尋：多網站顯示網站名稱、符合的排前面
            List<String> t = ms.testProduct(ch, ch.loadProducts().get(0));
            check("testProduct multi-site labels", t.stream().anyMatch(x -> x.contains("[momo]")) && t.stream().anyMatch(x -> x.contains("[PChome]")));

            // 某網站失敗不影響其他網站
            momo.set("<html>blocked</html>");
            List<String> logs = Collections.synchronizedList(new ArrayList<>());
            AppLog.addListener(logs::add);
            ms.runChannel(ch, "t6");
            check("one site failing doesn't stop others", logs.stream().anyMatch(l -> l.contains("iPhone 18 Pro（momo）") && l.contains("JSON-LD"))
                    && logs.stream().anyMatch(l -> l.contains("✓ iPhone 18 Pro（PChome）")));
        } finally {
            srv.stop(0);
        }
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
