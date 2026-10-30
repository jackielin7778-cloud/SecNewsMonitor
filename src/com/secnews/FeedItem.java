package com.secnews;

import java.time.Instant;

/** 從網站抓到的一則新聞。 */
public final class FeedItem {
    public final String siteName;
    public final String title;
    public final String link;
    public final String summary;
    /** 發布時間；RSS／Atom 沒有提供或無法解析時為 null（HTML 模式一律為 null）。 */
    public final Instant published;

    public FeedItem(String siteName, String title, String link, String summary) {
        this(siteName, title, link, summary, null);
    }

    public FeedItem(String siteName, String title, String link, String summary, Instant published) {
        this.siteName = siteName;
        this.title = title == null ? "" : cleanLine(title);
        this.link = link == null ? "" : link.trim();
        this.summary = summary == null ? "" : summary.trim();
        this.published = published;
    }

    /** 標題：去除換行、控制字元並合併空白（v1.7.1，避免偽造執行紀錄的內容）。 */
    static String cleanLine(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') c = ' ';
            b.append(c);
        }
        return HtmlScan.collapse(b.toString());
    }

    /** 去重用的鍵值：優先用連結，沒有連結就用標題。 */
    public String key() {
        return link.isEmpty() ? siteName + "|" + title : link;
    }
}
