package com.secnews;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次啟動時預設加入的台灣資安新聞來源（RSS）。
 * 網址查證來源見 README.md；使用者可在「網站列表」新增／修改／刪除。
 */
final class DefaultSites {
    private DefaultSites() {}

    static List<Site> list() {
        List<Site> l = new ArrayList<>();
        l.add(new Site("iThome 資安頻道", "https://www.ithome.com.tw/rss/security", Site.Type.RSS, true));
        l.add(new Site("iThome 新聞", "https://www.ithome.com.tw/rss", Site.Type.RSS, false));
        l.add(new Site("TWCERT/CC 資安新聞", "https://www.twcert.org.tw/tw/rss-104-1.xml", Site.Type.RSS, true));
        l.add(new Site("TWCERT/CC TVN 漏洞公告", "https://www.twcert.org.tw/tw/rss-132-1.xml", Site.Type.RSS, true));
        l.add(new Site("資安院 漏洞警訊公告", "https://www.nics.nat.gov.tw/RSS2.xml", Site.Type.RSS, true));
        l.add(new Site("資安趨勢部落格 (趨勢科技)", "https://blog.trendmicro.com.tw/?feed=rss2", Site.Type.RSS, true));
        l.add(new Site("TechNews 科技新報", "https://technews.tw/tn-rss/", Site.Type.RSS, false));
        return l;
    }
}
