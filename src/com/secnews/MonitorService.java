package com.secnews;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 執行一次監控：逐一抓取網站 → 比對關鍵字（標題／摘要，必要時全文）→ 去重 → 推播到 Google Chat。 */
public final class MonitorService {
    /** 全文比對：每次執行、每個網站最多抓幾篇內文。 */
    static final int MAX_FULLTEXT_PER_SITE = 30;
    /** 全文比對：兩次抓取之間的間隔（毫秒），避免對網站造成負擔。 */
    static long fullTextDelayMs = 1000;

    private final Store store;
    private final Fetcher fetcher;
    private final ProductMonitor products;
    private final GoogleChatClient chat = new GoogleChatClient();

    /** 推播方式（測試時可替換成本機假伺服器）。 */
    interface Sender { void send(String webhookUrl, String text) throws Exception; }
    Sender sender = chat::send;

    public MonitorService(Store store) { this(store, new Fetcher()); }

    MonitorService(Store store, Fetcher fetcher) {
        this.store = store;
        this.fetcher = fetcher;
        this.products = new ProductMonitor(fetcher);
    }

    public static final class Hit {
        final FeedItem item;
        final List<String> keywords;
        /** 全文比對命中時的內文片段；標題命中時為空字串。 */
        final String snippet;
        /** 商品頻道：推播失敗時把價格／庫存紀錄還原（v1.7.1）。 */
        Restore restore;
        Hit(FeedItem item, List<String> keywords, String snippet) {
            this.item = item; this.keywords = keywords; this.snippet = snippet;
        }
    }

    interface Restore { void run() throws Exception; }

    /** 執行第一個頻道一次（相容舊版與測試用）。 */
    public List<Hit> runOnce(String trigger) {
        return runChannel(store.primary(), trigger);
    }

    /** 依序執行所有啟用中的頻道（命令列 --run-once 用）。 */
    public void runAll(String trigger) {
        for (Channel c : store.channels()) {
            if (c.isEnabled()) runChannel(c, trigger);
            else AppLog.info("[" + c.getName() + "] 頻道已停用，略過");
            if (Thread.currentThread().isInterrupted()) return;
        }
    }

    /** 執行一個頻道一次，回傳命中的文章。 */
    public List<Hit> runChannel(Channel ch, String trigger) {
        final String tag = "[" + ch.getName() + "] ";
        long start = System.currentTimeMillis();
        AppLog.info(tag + "開始監控（" + trigger + "）");
        if (ch.isProduct()) return runProducts(ch, tag, start);
        List<Site> sites;
        KeywordMatcher matcher;
        try {
            sites = ch.loadSites();
            matcher = new KeywordMatcher(ch.loadKeywords());
        } catch (Exception e) {
            AppLog.error(tag + "讀取設定失敗", e);
            return List.of();
        }
        if (matcher.isEmpty()) {
            AppLog.info(tag + "沒有設定任何關鍵字，略過本次監控");
            return List.of();
        }
        ScheduleConfig cfg = ch.getSchedule();
        boolean baseline = cfg.baselineFirstRun;
        java.time.Instant cutoff = cfg.maxAgeDays > 0
                ? java.time.Instant.now().minus(java.time.Duration.ofDays(cfg.maxAgeDays)) : null;
        List<Hit> hits = new ArrayList<>();
        int ok = 0, fail = 0;
        List<String> fetched = new ArrayList<>();
        boolean interrupted = false;

        for (Site site : sites) {
            if (!site.enabled) continue;
            try {
                List<FeedItem> items = fetcher.fetch(site);
                List<String> currentKeys = new ArrayList<>(items.size());
                for (FeedItem it : items) currentKeys.add(it.key());
                boolean firstTime = !ch.hasSeenAnyFrom(site.name);
                if (firstTime && baseline) {
                    for (FeedItem it : items) if (!it.title.isEmpty()) ch.markSeen(site.name, it.key());
                    ch.markSiteBaselined(site.name);
                    ch.retainSeen(site.name, currentKeys);
                    ok++;
                    fetched.add(site.name);
                    AppLog.info(tag + "  ✓ " + site.name + "：" + items.size() + " 則（首次執行，已建立基準）");
                    continue;
                }
                int titleHits = 0, fullHits = 0, fullFetched = 0, fullFailed = 0, deferred = 0, tooOld = 0;
                for (FeedItem it : items) {
                    if (it.title.isEmpty()) continue;
                    if (ch.isSeen(site.name, it.key())) {
                        ch.markSeen(site.name, it.key());   // 舊格式紀錄轉移到此網站
                        continue;
                    }
                    if (cutoff != null && it.published != null && it.published.isBefore(cutoff)) {
                        ch.markSeen(site.name, it.key());   // 太舊：不比對、不抓內文
                        tooOld++;
                        continue;
                    }
                    List<String> kw = matcher.match(it);
                    if (!kw.isEmpty()) {
                        hits.add(new Hit(it, kw, ""));
                        titleHits++;
                        ch.markSeen(site.name, it.key());
                        continue;
                    }
                    if (!site.fullText || it.link.isEmpty()) {
                        ch.markSeen(site.name, it.key());
                        continue;
                    }
                    if (fullFetched >= MAX_FULLTEXT_PER_SITE) {
                        deferred++;               // 不標記已讀，下次執行再比對內文
                        continue;
                    }
                    if (fullFetched > 0) Thread.sleep(fullTextDelayMs);
                    fullFetched++;
                    try {
                        String text = fetcher.fetchArticleText(it.link);
                        List<String> fkw = matcher.matchText(text);
                        if (!fkw.isEmpty()) {
                            hits.add(new Hit(it, fkw, matcher.snippet(text, fkw.get(0), 40)));
                            fullHits++;
                        }
                    } catch (Exception e) {
                        fullFailed++;
                    }
                    ch.markSeen(site.name, it.key());
                }
                ch.markSiteBaselined(site.name);
                ch.retainSeen(site.name, currentKeys);
                ok++;
                fetched.add(site.name);
                StringBuilder msg = new StringBuilder("  ✓ " + site.name + "：" + items.size() + " 則，標題命中 " + titleHits + " 則");
                if (site.fullText) {
                    msg.append("；全文比對 ").append(fullFetched).append(" 篇、命中 ").append(fullHits).append(" 則");
                    if (fullFailed > 0) msg.append("、抓取失敗 ").append(fullFailed).append(" 篇");
                    if (deferred > 0) msg.append("、").append(deferred).append(" 篇留待下次");
                }
                if (tooOld > 0) msg.append("；略過超過 ").append(cfg.maxAgeDays).append(" 天的舊文章 ").append(tooOld).append(" 則");
                AppLog.info(tag + msg.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AppLog.info(tag + "監控被中斷");
                interrupted = true;
                break;
            } catch (Exception e) {
                fail++;
                AppLog.error(tag + "  ✗ " + site.name, e);
            }
        }

        if (!hits.isEmpty()) {
            List<Hit> undelivered = notifyChat(ch, hits);
            for (Hit h : undelivered) ch.unmarkSeen(h.item.siteName, h.item.key());   // 下次執行再推播
        }
        List<String> allNames = new ArrayList<>();
        for (Site s : sites) allNames.add(s.name);
        ch.pruneSeenSites(allNames);
        if (ch.hasLegacySeen() && fail == 0 && !interrupted) {
            List<String> notFetched = new ArrayList<>();
            for (Site s : sites) if (!fetched.contains(s.name)) notFetched.add(s.name);
            ch.dropLegacySeen(notFetched);
            AppLog.info(tag + "已讀紀錄已轉換為新格式（每個網站分開保存）");
        }
        try { ch.flushSeen(); } catch (Exception e) { AppLog.error(tag + "儲存已讀紀錄失敗", e); }
        AppLog.info(tag + String.format("監控結束：成功 %d 個網站、失敗 %d 個、命中 %d 則，耗時 %.1f 秒",
                ok, fail, hits.size(), (System.currentTimeMillis() - start) / 1000.0));
        return hits;
    }

    private List<Hit> runProducts(Channel ch, String tag, long start) {
        int[] okFail = new int[2];
        List<Hit> hits;
        try {
            hits = products.run(ch, tag, ch.getSchedule().baselineFirstRun, okFail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLog.info(tag + "監控被中斷");
            return List.of();
        }
        if (!hits.isEmpty()) {
            List<Hit> undelivered = notifyChat(ch, hits);
            for (Hit h : undelivered) {
                if (h.restore != null) {
                    try { h.restore.run(); } catch (Exception e) { AppLog.error(tag + "還原商品紀錄失敗", e); }
                }
            }
        }
        try {
            ch.flushProducts();
        } catch (Exception e) {
            AppLog.error(tag + "儲存商品紀錄失敗", e);
        }
        AppLog.info(tag + String.format("監控結束：成功 %d 個商品、失敗 %d 個、通知 %d 件，耗時 %.1f 秒",
                okFail[0], okFail[1], hits.size(), (System.currentTimeMillis() - start) / 1000.0));
        return hits;
    }

    /** 商品頻道的「測試搜尋」。 */
    public List<String> testProduct(Channel ch, ProductWatch w) throws Exception {
        List<String> out = new ArrayList<>();
        List<String> sites = ch.getProductSites();
        List<String> errors = new ArrayList<>();
        for (String site : sites) {
            try {
                for (ProductMonitor.Product p : products.search(ch, w, site)) {
                    String why = w.rejectReason(p.name, p.price);
                    out.add((why == null ? "✓ " : "✗ ") + (sites.size() > 1 ? "[" + ProductMonitor.siteLabel(site) + "] " : "")
                            + "NT$" + ProductMonitor.fmt(p.price) + (Boolean.FALSE.equals(p.inStock) ? "（缺貨）" : "")
                            + "  " + p.name + (why == null ? "" : "（" + why + "）"));
                }
            } catch (Exception e) {
                if (sites.size() == 1) throw e;
                errors.add("[" + ProductMonitor.siteLabel(site) + "] 失敗：" + e.getMessage());
            }
        }
        out.sort((a, b) -> Boolean.compare(b.startsWith("✓"), a.startsWith("✓")));   // 符合的排前面
        out.addAll(0, errors);
        return out;
    }

    /**
     * 推播命中結果到此頻道啟用的管道（Google Chat、LINE、Gmail，v1.8 起可同時多個）。
     * 回傳「沒有送出、下次執行應再推播」的項目（v1.7.1）：所有管道都失敗 → 全部；超過單次上限 → 超過的部分。
     * 至少一個管道成功就視為已送出（避免成功的管道下次重複收到）；失敗的管道記錄在執行紀錄。
     * 沒有任何啟用的管道 → 空（只記錄）。
     */
    private List<Hit> notifyChat(Channel ch, List<Hit> hits) {
        String tag = "[" + ch.getName() + "] ";
        int max = ch.getMaxPushPerRun();
        List<Hit> pushed = hits.size() > max ? hits.subList(0, max) : hits;
        List<Hit> rest = hits.size() > max ? hits.subList(max, hits.size()) : List.of();
        String text = ch.isProduct() ? formatProducts(ch.getName(), pushed, rest.size())
                : format(ch.getName(), pushed, rest.size());

        boolean chat = ch.isChatEnabled() && ch.hasWebhook();
        boolean line = ch.isLineReady();
        boolean mail = ch.isEmailReady();
        if (!chat && !line && !mail) {
            if (ch.isChatEnabled() && !ch.hasWebhook() && !ch.isLineEnabled() && !ch.isEmailEnabled()) {
                AppLog.info(tag + "[警告] 此頻道尚未設定 Google Chat Webhook，命中文章如下：");
            } else if (ch.isLineEnabled() || ch.isEmailEnabled()) {
                AppLog.info(tag + "[警告] 此頻道啟用的推播管道設定不完整，命中文章如下：");
            } else {
                AppLog.info(tag + "Google Chat 推播已關閉，命中文章如下：");
            }
            logTitles(hits);
            return List.of();
        }
        List<String> ok = new ArrayList<>(), failed = new ArrayList<>();
        if (chat) {
            try { sender.send(ch.getWebhookUrl(), text); ok.add("Google Chat"); }
            catch (Exception e) { failed.add("Google Chat"); AppLog.error(tag + "推播到 Google Chat 失敗", e); }
        }
        if (line) {
            try { lineSender.send(ch, plain(text)); ok.add("LINE"); }
            catch (Exception e) { failed.add("LINE"); AppLog.error(tag + "推播到 LINE 失敗", e); }
        }
        if (mail) {
            String subject = "[SecNewsMonitor] " + ch.getName() + "：" + (hits.size()) + (ch.isProduct() ? " 件商品通知" : " 則新聞命中");
            try { mailSender.send(ch, subject, plain(text)); ok.add("Gmail"); }
            catch (Exception e) { failed.add("Gmail"); AppLog.error(tag + "寄送 Gmail 失敗", e); }
        }
        if (ok.isEmpty()) {
            AppLog.info(tag + "所有推播管道都失敗（下次執行會再推播），未推播的命中文章如下：");
            logTitles(hits);
            return new ArrayList<>(hits);
        }
        AppLog.info(tag + "已推播 " + pushed.size() + " 則到 " + String.join("、", ok)
                + (failed.isEmpty() ? "" : "（" + String.join("、", failed) + " 失敗，詳見上方紀錄）")
                + (rest.isEmpty() ? "" : "（另有 " + rest.size() + " 則超過單次上限 " + max + " 則，下次執行再推播）"));
        if (!rest.isEmpty()) logTitles(rest);
        return new ArrayList<>(rest);
    }

    /** LINE、Email 用的純文字：去掉 Google Chat 的粗體記號（外部文字中的 * 已在 ChatText 轉成全形）。 */
    static String plain(String chatText) {
        return chatText.replace("*", "");
    }

    /** LINE 推播方式（測試時可替換）。 */
    interface LineSender { void send(Channel ch, String text) throws Exception; }
    LineSender lineSender = (ch, text) -> new LineClient().send(ch.getLineToken(), ch.getLineMode(), ch.getLineTo(), text);

    /** Gmail 寄信方式（測試時可替換）。 */
    interface MailSender { void send(Channel ch, String subject, String text) throws Exception; }
    MailSender mailSender = (ch, subject, text) -> new GmailClient().send(ch.getEmailFrom(), ch.getEmailPassword(), ch.getEmailTo(), subject, text);

    /** 傳送 LINE 測試訊息。 */
    public void sendTestLine(String token, Channel.LineMode mode, List<String> to, String channelName) throws Exception {
        new LineClient().send(token, mode, to, "✅ SecNewsMonitor 測試訊息：LINE 串接成功。"
                + (channelName == null ? "" : "\n頻道：" + channelName));
    }

    /** 寄送 Gmail 測試信。 */
    public void sendTestEmail(String from, String appPassword, List<String> to, String channelName) throws Exception {
        new GmailClient().send(from, appPassword, to, "[SecNewsMonitor] 測試信" + (channelName == null ? "" : "：" + channelName),
                "✅ SecNewsMonitor 測試信：Gmail 串接成功。" + (channelName == null ? "" : "\n頻道：" + channelName));
    }

    private static void logTitles(List<Hit> hits) {
        for (Hit h : hits) {
            AppLog.info("    • [" + String.join(", ", h.keywords) + "] " + h.item.title
                    + "（" + h.item.siteName + "）" + h.item.link);
        }
    }

    /** 標題、商品名稱最多保留的字數。 */
    static final int TITLE_MAX = 300;
    /** 內文片段最多保留的字數。 */
    static final int SNIPPET_MAX = 300;

    /** 連結：合格輸出網址，不合格輸出說明（不輸出原始內容）。 */
    private static String linkLine(String link) {
        String l = ChatText.link(link);
        return l.isEmpty() ? "（連結格式不符，已略過）" : l;
    }

    /** v1.7.1：所有來自外部的文字都經過 ChatText 處理，避免 Chat 訊息注入。 */
    static String formatProducts(String channelName, List<Hit> hits, int omitted) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛒 *").append(ChatText.oneLine(channelName, 100)).append("* ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
          .append("｜共 ").append(hits.size() + omitted).append(" 件");
        if (omitted > 0) sb.append("（以下列出 ").append(hits.size()).append(" 件）");
        for (Hit h : hits) {
            sb.append("\n\n*").append(ChatText.oneLine(h.item.title, TITLE_MAX)).append("*\n")
              .append(ChatText.oneLine(h.item.summary, 200)).append("｜").append(ChatText.oneLine(String.join("、", h.keywords), 200))
              .append("｜監控：").append(ChatText.oneLine(h.item.siteName, 100))
              .append('\n').append(linkLine(h.item.link));
        }
        if (omitted > 0) sb.append("\n\n…另有 ").append(omitted).append(" 件未列出，請到程式的「執行紀錄」查看。");
        return sb.toString();
    }

    static String format(List<Hit> hits, int omitted) {
        return format(Store.DEFAULT_CHANNEL_NAME, hits, omitted);
    }

    static String format(String channelName, List<Hit> hits, int omitted) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔔 *").append(ChatText.oneLine(channelName, 100)).append("* ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
          .append("｜共 ").append(hits.size() + omitted).append(" 則");
        if (omitted > 0) sb.append("（以下列出 ").append(hits.size()).append(" 則）");
        for (Hit h : hits) {
            sb.append("\n\n*").append(ChatText.oneLine(h.item.title, TITLE_MAX)).append("*\n")
              .append("來源：").append(ChatText.oneLine(h.item.siteName, 100))
              .append("｜關鍵字：").append(ChatText.oneLine(String.join(", ", h.keywords), 200));
            if (h.item.published != null) {
                sb.append("｜發布：").append(java.time.LocalDate.ofInstant(h.item.published, java.time.ZoneId.systemDefault()));
            }
            if (!h.snippet.isEmpty()) sb.append("（內文命中）\n").append("內文：").append(ChatText.oneLine(h.snippet, SNIPPET_MAX));
            sb.append('\n').append(linkLine(h.item.link));
        }
        if (omitted > 0) {
            sb.append("\n\n…另有 ").append(omitted).append(" 則符合關鍵字的文章未列出，請到程式的「執行紀錄」查看。");
        }
        return sb.toString();
    }

    public void sendTest(String webhookUrl) throws Exception {
        sendTest(webhookUrl, null);
    }

    public void sendTest(String webhookUrl, String channelName) throws Exception {
        chat.send(webhookUrl, "✅ SecNewsMonitor 測試訊息：Google Chat 串接成功。"
                + (channelName == null ? "" : "\n頻道：" + channelName));
    }

    public List<FeedItem> testSite(Site s) throws Exception {
        return fetcher.fetch(s);
    }
}
