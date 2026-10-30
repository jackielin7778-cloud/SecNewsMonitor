package com.secnews;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 對外連線前的網址檢查（v1.7.1，防 SSRF）。
 *
 * 只允許 http／https，且主機不可是本機、內網、連結本地（含雲端主機的 169.254.169.254 中繼資料服務）、
 * CGNAT、多播等位址。網址可能來自外部（RSS 裡的文章連結、網站的轉址），不檢查的話，
 * 惡意網站可讓本程式替它去存取公司內網或本機服務。每一次轉址都會重新檢查。
 *
 * 確實需要監控內網網站時：啟動參數加 -Dsecnews.allowPrivate=true，或設定環境變數 SECNEWS_ALLOW_PRIVATE=1。
 *
 * 限制（這是已知的殘餘風險）：檢查時解析的 IP 與實際連線時解析的 IP 可能不同（DNS rebinding）；
 * Java 內建 HttpClient 無法指定連線 IP，若要完全杜絕需在網路層（防火牆）限制。
 */
final class SafeUrl {
    private SafeUrl() {}

    /** 只給自動測試用：視為允許的主機名稱（例如 localhost），用來測試「轉址到內網」會被擋。 */
    static volatile java.util.Set<String> testAllowHosts = java.util.Set.of();

    static boolean allowPrivate() {
        String p = System.getProperty("secnews.allowPrivate");
        if (p != null) return Boolean.parseBoolean(p.trim());
        String e = System.getenv("SECNEWS_ALLOW_PRIVATE");
        return e != null && (e.trim().equals("1") || e.trim().equalsIgnoreCase("true"));
    }

    /** 檢查網址，不允許時丟出 IOException（訊息說明原因）。 */
    static URI check(String url) throws IOException {
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("網址格式錯誤：" + e.getMessage());
        }
        return check(u);
    }

    static URI check(URI u) throws IOException {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("只允許 http／https 網址（" + scheme + "）");
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new IOException("網址缺少主機名稱");
        if (u.getRawUserInfo() != null) throw new IOException("網址不可包含帳號密碼");
        if (allowPrivate() || testAllowHosts.contains(host.toLowerCase(Locale.ROOT))) return u;
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            throw new IOException("找不到主機 " + host);
        }
        for (InetAddress a : addrs) {
            if (isBlocked(a)) {
                throw new IOException("基於安全考量，不連線到內網／本機位址 " + host + "（" + a.getHostAddress()
                        + "）。若確實要監控內網網站，請參考說明書啟用 secnews.allowPrivate");
            }
        }
        return u;
    }

    static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0) return true;                                   // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;        // 100.64.0.0/10（CGNAT）
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return true; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;       // 198.18.0.0/15
            if (b0 >= 240) return true;                                 // 240.0.0.0/4、廣播
            return false;
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return true;                       // fc00::/7（ULA）
            // IPv4 對應／相容位址（::ffff:a.b.c.d、::a.b.c.d）：取出 IPv4 再判斷
            boolean zero10 = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { zero10 = false; break; }
            if (zero10 && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            if (b0 == 0x20 && (b[1] & 0xff) == 0x02) {                  // 2002::/16（6to4）內含 IPv4
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[]{b[2], b[3], b[4], b[5]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
