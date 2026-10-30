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
    static final String UA = "SecNewsMonitor/"
            + (Character.isDigit(AppInfo.version().charAt(0)) ? AppInfo.version() : "dev") + " (+RSS reader)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)      // 自行處理轉址，每一站都做 SafeUrl 檢查
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
            try {
                return parseFeed(site, new LimitedInputStream(in, MAX_FEED_BYTES));
            } catch (Exception e) {
                if (in instanceof DeadlineInputStream d && d.expired) {
                    throw new IOException("讀取回應超過 " + bodyTimeoutSec + " 秒，已中斷", e);
                }
                throw e;
            }
        }
    }

    /** 開啟連線並回傳內容串流（呼叫端負責關閉），不重試。全文比對抓文章頁時使用，避免拖慢整體執行。 */
    InputStream open(String url) throws IOException, InterruptedException {
        return open(url, 0, null);
    }

    /** 最多跟隨幾次轉址（每次都會重新做 SafeUrl 檢查）。 */
    static final int MAX_REDIRECTS = 5;
    /** 讀取回應內容的總時限（秒）：超過就中斷，避免對方以極慢速度傳送讓監控卡住。測試時可縮短。 */
    static long bodyTimeoutSec = 90;

    /**
     * 開啟連線；遇到暫時性錯誤（HTTP 429／502／503／504、逾時、連線中斷）時，
     * 依 Retry-After 或 3、6 秒後重試，最多 retries 次。其他錯誤（例如 404）不重試。
     * v1.7.1：自行處理轉址（每一站都檢查不是內網／本機位址），回應內容有總讀取時限。
     */
    InputStream open(String url, int retries, String label) throws IOException, InterruptedException {
        URI first = SafeUrl.check(url);
        for (int attempt = 0; ; attempt++) {
            String problem;
            long wait = retryBaseMs << attempt;
            try {
                URI cur = first;
                for (int hop = 0; ; hop++) {
                    HttpRequest req = HttpRequest.newBuilder(cur)
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", UA)
                            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5")
                            .GET().build();
                    HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    int sc = resp.statusCode();
                    if (sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308) {
                        resp.body().close();
                        String loc = resp.headers().firstValue("Location").orElse("");
                        if (loc.isEmpty()) throw new HttpStatusException("HTTP " + sc + "（轉址但沒有 Location）");
                        if (hop >= MAX_REDIRECTS) throw new HttpStatusException("轉址次數過多（超過 " + MAX_REDIRECTS + " 次）");
                        URI next;
                        try {
                            next = cur.resolve(loc.trim().replace(" ", "%20"));
                        } catch (IllegalArgumentException e) {
                            throw new HttpStatusException("轉址網址格式錯誤");
                        }
                        try {
                            cur = SafeUrl.check(next);
                        } catch (IOException e) {
                            throw new HttpStatusException("轉址被拒絕：" + e.getMessage());
                        }
                        continue;
                    }
                    if (sc < 400) return new DeadlineInputStream(resp.body(), bodyTimeoutSec);
                    resp.body().close();
                    boolean transientError = sc == 429 || sc == 502 || sc == 503 || sc == 504;
                    if (!transientError || attempt >= retries) {
                        throw new HttpStatusException("HTTP " + sc + (attempt > 0 ? "（已重試 " + attempt + " 次）" : ""));
                    }
                    problem = "HTTP " + sc;
                    wait = retryAfterMs(resp).orElse(wait);
                    break;
                }
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

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "secnews-read-timeout");
                t.setDaemon(true);
                return t;
            });

    /** 超過總時限就自動關閉的串流（關閉會讓卡住的 read 立即丟出例外）。 */
    static final class DeadlineInputStream extends FilterInputStream {
        private final java.util.concurrent.ScheduledFuture<?> timer;
        private volatile boolean expired;

        DeadlineInputStream(InputStream in, long seconds) {
            super(in);
            timer = WATCHDOG.schedule(() -> {
                expired = true;
                try { in.close(); } catch (IOException ignored) {}
            }, seconds, java.util.concurrent.TimeUnit.SECONDS);
        }

        private IOException wrap(IOException e) {
            return expired ? new IOException("讀取回應超過 " + bodyTimeoutSec + " 秒，已中斷", e) : e;
        }

        @Override public int read() throws IOException {
            try { return super.read(); } catch (IOException e) { throw wrap(e); }
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            try { return super.read(b, off, len); } catch (IOException e) { throw wrap(e); }
        }

        @Override public void close() throws IOException {
            timer.cancel(false);
            super.close();
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

    /** 抓取 JSON／文字資料（暫時性錯誤會重試），上限 5 MB。 */
    byte[] getWithRetry(String url, String label) throws IOException, InterruptedException {
        try (InputStream in = new LimitedInputStream(open(url, FETCH_RETRIES, label), MAX_PAGE_BYTES)) {
            return in.readAllBytes();
        }
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
        XMLStreamReader r = f.createXMLStreamReader(new EntityFixInputStream(in));
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

    private static final Map<String, String> NS_PREFIX = Map.of(
            "http://purl.org/dc/elements/1.1/", "dc:",
            "http://purl.org/rss/1.0/modules/content/", "content:");

    /**
     * 元素名稱：RSS／Atom 本身的元素一律用名稱本身（不論文件用什麼前綴，例如 atom:entry → entry）；
     * Dublin Core、content 模組固定為 dc:、content:；其他命名空間保留原前綴。
     */
    private static String qname(XMLStreamReader r) {
        String ns = r.getNamespaceURI();
        String local = r.getLocalName();
        if (ns != null) {
            String fixed = NS_PREFIX.get(ns);
            if (fixed != null) return fixed + local;
            if (ns.equals("http://www.w3.org/2005/Atom") || ns.equals("http://purl.org/rss/1.0/")
                    || ns.equals("http://backend.userland.com/rss2") || ns.isEmpty()) return local;
        }
        String p = r.getPrefix();
        return p == null || p.isEmpty() ? local : p + ":" + local;
    }

    /**
     * 把 XML 未宣告的 HTML 實體（例如 &amp;nbsp;、&amp;copy;）在解析前換成數字實體；不認得的改成字面文字，
     * 避免單一則文章的實體讓整個 RSS 解析失敗（v1.7.1）。只處理 ASCII 相容編碼（UTF-8、Big5 等）；UTF-16 原樣通過。
     */
    static final class EntityFixInputStream extends FilterInputStream {
        private static final Map<String, Integer> HTML = Map.ofEntries(
                Map.entry("nbsp", 160), Map.entry("copy", 169), Map.entry("reg", 174), Map.entry("trade", 8482),
                Map.entry("hellip", 8230), Map.entry("mdash", 8212), Map.entry("ndash", 8211),
                Map.entry("lsquo", 8216), Map.entry("rsquo", 8217), Map.entry("ldquo", 8220), Map.entry("rdquo", 8221),
                Map.entry("laquo", 171), Map.entry("raquo", 187), Map.entry("bull", 8226), Map.entry("middot", 183),
                Map.entry("deg", 176), Map.entry("times", 215), Map.entry("euro", 8364), Map.entry("yen", 165),
                Map.entry("pound", 163), Map.entry("cent", 162), Map.entry("sect", 167), Map.entry("para", 182),
                Map.entry("eacute", 233), Map.entry("uuml", 252), Map.entry("ouml", 246), Map.entry("auml", 228));
        private byte[] pending = new byte[0];
        private int pos;
        private boolean first = true, passthrough;

        EntityFixInputStream(InputStream in) { super(new java.io.BufferedInputStream(in, 8192)); }

        @Override public int read() throws IOException {
            if (pos < pending.length) return pending[pos++] & 0xff;
            int c = in.read();
            if (first) {
                first = false;
                if (c == 0xFE || c == 0xFF || c == 0) passthrough = true;    // UTF-16／UTF-32
            }
            if (c != '&' || passthrough) return c;
            in.mark(16);
            StringBuilder name = new StringBuilder();
            int d;
            while ((d = in.read()) >= 0 && name.length() < 12
                    && ((d >= 'a' && d <= 'z') || (d >= 'A' && d <= 'Z') || (d >= '0' && d <= '9') || d == '#')) {
                name.append((char) d);
            }
            String n = name.toString();
            if (d != ';' || n.isEmpty() || n.startsWith("#") || n.equals("amp") || n.equals("lt") || n.equals("gt")
                    || n.equals("quot") || n.equals("apos")) {
                in.reset();
                return c;
            }
            Integer cp = HTML.get(n);
            String rep = cp != null ? "&#" + cp + ";" : "&amp;" + n + ";";
            pending = rep.substring(1).getBytes(StandardCharsets.US_ASCII);
            pos = 0;
            return '&';
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int i = 0;
            while (i < len) {
                int c = read();
                if (c < 0) return i == 0 ? -1 : i;
                b[off + i++] = (byte) c;
                if (pos >= pending.length && in.available() <= 0 && i > 0) break;
            }
            return i;
        }

        @Override public boolean markSupported() { return false; }
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
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 從網頁取出「文字夠長」的連結當作新聞標題（過濾選單、頁尾等短連結）。
     * v1.7.1：改為線性掃描（HtmlScan），並只接受 http／https 連結。
     */
    static List<FeedItem> parseHtml(Site site, byte[] body, Charset hint) {
        String html = decodeHtml(body, hint);
        Map<String, FeedItem> uniq = new LinkedHashMap<>();
        URI base = URI.create(site.url.trim());
        for (HtmlScan.Anchor a : HtmlScan.anchors(html)) {
            String title = stripTags(a.inner());
            if (title.codePointCount(0, title.length()) < 8) continue;
            String href = HtmlScan.decodeEntities(a.href()).trim();
            int hash = href.indexOf('#');
            if (hash >= 0) href = href.substring(0, hash);
            if (href.isEmpty()) continue;
            String abs;
            try { abs = base.resolve(href.replace(" ", "%20")).toString(); } catch (Exception e) { continue; }
            if (!ChatText.isWebUrl(abs)) continue;          // 排除 javascript:、mailto:、data: 等（不分大小寫）
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

    /** 下載文章網頁並轉成純文字（去除程式碼、樣式、選單、頁尾等雜訊）。 */
    public String fetchArticleText(String url) throws IOException, InterruptedException {
        return htmlToText(decodeHtml(get(url), null));
    }

    /** v1.7.1：改為線性掃描，避免惡意網頁造成 O(n²) 運算。 */
    static String htmlToText(String html) {
        String t = HtmlScan.removeComments(html);
        t = HtmlScan.removeNoiseBlocks(t);
        t = stripTags(t);
        return t.length() > MAX_TEXT_CHARS ? t.substring(0, MAX_TEXT_CHARS) : t;
    }

    /** 去除標籤、解碼實體（只解碼一次）、合併空白。 */
    static String stripTags(String s) {
        return HtmlScan.stripTags(s);
    }
}
