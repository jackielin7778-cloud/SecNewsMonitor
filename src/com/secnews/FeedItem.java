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
        this.title = title == null ? "" : title.trim();
        this.link = link == null ? "" : link.trim();
        this.summary = summary == null ? "" : summary.trim();
        this.published = published;
    }

    /** 去重用的鍵值：優先用連結，沒有連結就用標題。 */
    public String key() {
        return link.isEmpty() ? siteName + "|" + title : link;
    }
}
