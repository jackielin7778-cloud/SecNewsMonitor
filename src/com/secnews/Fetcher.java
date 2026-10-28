package com.secnews;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.util.HashMap;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 抓取 RSS / Atom / 一般 HTML 網頁，轉成 FeedItem 清單。 */
public class Fetcher {
    /** 一般網頁（HTML 列表頁、全文比對的文章頁）需整份讀進記憶體，上限 5 MB。 */
    static final long MAX_PAGE_BYTES = 5L * 1024 * 1024;
    /** RSS／Atom 改為串流解析、不整份載入記憶體，上限放寬到 50 MB（防止異常巨大的回應）。 */
    static final long MAX_FEED_BYTES = 50L * 1024 * 1024;
    /** 每則文章的標題／摘要最多保留的字數（避免單則內容過大）。 */
    static final int MAX_FIELD_CHARS = 8000;
    private static final String UA = "SecNewsMonitor/1.0 (+RSS reader)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 抓取網站（RSS／列表頁）遇到暫時性錯誤時的重試次數。 */
    static final int FETCH_RETRIES = 2;
    /** 重試等待的基準時間（毫秒）：第 1 次 3 秒、第 2 次 6 秒。測試時可縮短。 */
    static long retryBaseMs = 3000;

    public List<FeedItem> fetch(Site site) throws Exception {
        if (site.type == Site.Type.HTML) {
            try (InputStream in = new LimitedInputStream(open(site.url, FETCH_RETRIES, site.name), MAX_PAGE_BYTES)) {
                return parseHtml(site, in.readAllBytes(), null);
            }
        }
        try (InputStream in = open(site.url, FETCH_RETRIES, site.name)) {
            return parseFeed(site, new LimitedInputStream(in, MAX_FEED_BYTES));
        }
    }

    /** 開啟連線並回傳內容串流（呼叫端負責關閉），不重試。全文比對抓文章頁時使用，避免拖慢整體執行。 */
    InputStream open(String url) throws IOException, InterruptedException {
        return open(url, 0, null);
    }

    /**
     * 開啟連線；遇到暫時性錯誤（HTTP 429／502／503／504、逾時、連線中斷）時，
     * 依 Retry-After 或 3、6 秒後重試，最多 retries 次。其他錯誤（例如 404）不重試。
     */
    InputStream open(String url, int retries, String label) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5")
                .GET().build();
        for (int attempt = 0; ; attempt++) {
            String problem;
            long wait = retryBaseMs << attempt;
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int sc = resp.statusCode();
                if (sc < 400) return resp.body();
                resp.body().close();
                boolean transientError = sc == 429 || sc == 502 || sc == 503 || sc == 504;
                if (!transientError || attempt >= retries) {
                    throw new HttpStatusException("HTTP " + sc + (attempt > 0 ? "（已重試 " + attempt + " 次）" : ""));
                }
                problem = "HTTP " + sc;
                wait = retryAfterMs(resp).orElse(wait);
            } catch (HttpStatusException e) {
                throw e;
            } catch (IOException e) {          // 逾時、連線被拒、連線中斷等
                if (attempt >= retries) {
                    String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    throw new IOException(m + (attempt > 0 ? "（已重試 " + attempt + " 次）" : ""), e);
                }
                problem = e.getClass().getSimpleName().replace("Exception", "")
                        + (e.getMessage() != null ? "：" + e.getMessage() : "");
            }
            if (label != null) {
                AppLog.info("    " + label + "：" + problem + "，" + (wait / 1000.0) + " 秒後重試（第 " + (attempt + 1) + " 次）");
            }
            Thread.sleep(wait);
        }
    }

    /** HTTP 狀態碼錯誤（不屬於連線層的 IOException，不再重試）。 */
    static final class HttpStatusException extends IOException {
        HttpStatusException(String m) { super(m); }
    }

    private static java.util.Optional<Long> retryAfterMs(HttpResponse<?> resp) {
        return resp.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                return java.util.Optional.of(Math.min(60, Math.max(1, Long.parseLong(v.trim()))) * 1000);
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    byte[] get(String url) throws IOException, InterruptedException {
        try (InputStream in = new LimitedInputStream(open(url), MAX_PAGE_BYTES)) {
            return in.readAllBytes();
        }
    }

    /** 讀超過上限就丟出例外的串流。 */
    static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) { super(in); this.max = max; }

        private void add(long n) throws IOException {
            if (n > 0 && (count += n) > max) {
                throw new IOException("回應超過 " + (max / 1024 / 1024) + " MB 上限");
            }
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            add(n);
            return n;
        }
    }

    // ---------------- RSS / Atom（StAX 串流解析） ----------------
    static List<FeedItem> parseFeed(Site site, byte[] body) throws Exception {
        return parseFeed(site, new ByteArrayInputStream(body));
    }

    /**
     * 邊讀邊解析，記憶體用量只跟「文章數 × 每則保留的字數」有關，與檔案大小無關。
     * 防 XXE：不處理 DTD、不載入外部實體；未宣告的實體引用會直接報錯。
     * 編碼依 XML 宣告／BOM 自動判斷（UTF-8、Big5 等）。
     */
    static List<FeedItem> parseFeed(Site site, InputStream in) throws Exception {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader r = f.createXMLStreamReader(in);
        List<FeedItem> items = new ArrayList<>();
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String n = qname(r);
                if ("item".equals(n) || "entry".equals(n)) items.add(readItem(site, r));   // RSS 2.0／RDF、Atom
            }
        } finally {
            r.close();
        }
        return items;
    }

    /** 讀取一則 item／entry（游標在開始標籤上），讀到它的結束標籤為止。只看直屬子元素。 */
    private static FeedItem readItem(Site site, XMLStreamReader r) throws XMLStreamException {
        Map<String, String> fields = new HashMap<>();
        String atomLink = "";
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;            // item／entry 結束
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            String n = qname(r);
            if ("link".equals(n)) {                                     // Atom：<link rel="alternate" href="…"/>
                String href = r.getAttributeValue(null, "href");
                String rel = r.getAttributeValue(null, "rel");
                if (href != null && atomLink.isEmpty() && (rel == null || rel.isEmpty() || "alternate".equals(rel))) {
                    atomLink = href.trim();
                }
            }
            String text = readText(r);
            if (!text.isEmpty()) fields.putIfAbsent(n, text);
        }
        String link = firstNonEmpty(fields.getOrDefault("link", ""), firstNonEmpty(atomLink, fields.getOrDefault("guid", "")));
        String summary = firstNonEmpty(fields.getOrDefault("description", ""),
                firstNonEmpty(fields.getOrDefault("summary", ""),
                        firstNonEmpty(fields.getOrDefault("content:encoded", ""), fields.getOrDefault("content", ""))));
        String date = firstNonEmpty(fields.getOrDefault("pubDate", ""),
                firstNonEmpty(fields.getOrDefault("published", ""),
                        firstNonEmpty(fields.getOrDefault("updated", ""), fields.getOrDefault("dc:date", ""))));
        return new FeedItem(site.name, fields.getOrDefault("title", ""), link, stripTags(summary), parseDate(date));
    }

    /** 讀取目前元素內所有文字（含巢狀元素），讀到它的結束標籤為止；超過上限的部分捨棄。 */
    private static String readText(XMLStreamReader r) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            switch (r.next()) {
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    int room = MAX_FIELD_CHARS - sb.length();
                    if (room > 0) {
                        int len = Math.min(room, r.getTextLength());
                        sb.append(r.getTextCharacters(), r.getTextStart(), len);
                    }
                }
                default -> { }
            }
        }
        return sb.toString().trim();
    }

    /** 以「前綴:名稱」表示元素，例如 dc:date、content:encoded；沒有前綴就是名稱本身。 */
    private static String qname(XMLStreamReader r) {
        String p = r.getPrefix();
        return p == null || p.isEmpty() ? r.getLocalName() : p + ":" + r.getLocalName();
    }

    // ---------------- 日期解析 ----------------
    private static final java.time.format.DateTimeFormatter[] DATE_FORMATS = {
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,                    // Tue, 27 Oct 2026 10:00:00 GMT / +0800
            java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH),   // EST, PDT…
            java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm z", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,                  // 2026-10-27T10:00:00+08:00（Atom）
            java.time.format.DateTimeFormatter.ISO_INSTANT,                           // 2026-10-27T02:00:00Z
    };

    /** 解析 RSS／Atom 常見的日期格式；無法解析回傳 null（視為「日期不明」）。 */
    static java.time.Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        for (java.time.format.DateTimeFormatter f : DATE_FORMATS) {
            try { return java.time.ZonedDateTime.parse(t, f).toInstant(); } catch (Exception ignored) {}
        }
        // 沒有時區：2026-10-27 10:00:00、2026-10-27T10:00:00、2026/10/27 10:00、2026-10-27（以本機時區解讀）
        String u = t.replace('/', '-').replace(' ', 'T');
        try { return java.time.LocalDateTime.parse(u).atZone(java.time.ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
        try { return java.time.LocalDateTime.parse(u + ":00").atZone(java.time.ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
        try { return java.time.LocalDate.parse(u.length() >= 10 ? u.substring(0, 10) : u).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
        return null;
    }

    private static String firstNonEmpty(String a, String b) { return a.isEmpty() ? b : a; }

    // ---------------- HTML（無 RSS 的網站） ----------------
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*?href\\s*=\\s*[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    /** 從網頁取出「文字夠長」的連結當作新聞標題（過濾選單、頁尾等短連結）。 */
    static List<FeedItem> parseHtml(Site site, byte[] body, Charset hint) {
        String html = decodeHtml(body, hint);
        Map<String, FeedItem> uniq = new LinkedHashMap<>();
        URI base = URI.create(site.url.trim());
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String title = stripTags(m.group(2));
            if (title.codePointCount(0, title.length()) < 8) continue;
            String href = m.group(1).trim();
            if (href.startsWith("javascript:") || href.startsWith("mailto:")) continue;
            String abs;
            try { abs = base.resolve(href.replace(" ", "%20")).toString(); } catch (Exception e) { continue; }
            uniq.putIfAbsent(abs, new FeedItem(site.name, title, abs, ""));
        }
        return new ArrayList<>(uniq.values());
    }

    /** 依 &lt;meta charset&gt; 判斷編碼（預設 UTF-8），把位元組轉成字串。 */
    static String decodeHtml(byte[] body, Charset hint) {
        Charset cs = hint != null ? hint : StandardCharsets.UTF_8;
        if (hint == null) {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher mc = META_CHARSET.matcher(head);
            if (mc.find()) {
                try { cs = Charset.forName(mc.group(1)); } catch (Exception ignored) {}
            }
        }
        return new String(body, cs);
    }

    // ---------------- 全文比對：抓文章內文 ----------------
    private static final int MAX_TEXT_CHARS = 200_000;
    private static final Pattern NOISE_BLOCKS = Pattern.compile(
            "(?is)<(script|style|noscript|svg|head|nav|footer|header|aside|form)\\b.*?</\\1\\s*>");
    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");

    /** 下載文章網頁並轉成純文字（去除程式碼、樣式、選單、頁尾等雜訊）。 */
    public String fetchArticleText(String url) throws IOException, InterruptedException {
        return htmlToText(decodeHtml(get(url), null));
    }

    static String htmlToText(String html) {
        String t = COMMENTS.matcher(html).replaceAll(" ");
        t = NOISE_BLOCKS.matcher(t).replaceAll(" ");
        t = stripTags(t);
        return t.length() > MAX_TEXT_CHARS ? t.substring(0, MAX_TEXT_CHARS) : t;
    }

    static String stripTags(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }
}
