package com.secnews;

/** 程式資訊：版本號取自 jar 的 MANIFEST.MF（Implementation-Version，由 build.sh／build.bat 寫入）。 */
public final class AppInfo {
    public static final String NAME = "SecNewsMonitor";

    private AppInfo() {}

    /** 例如「1.4.1」；從原始碼直接執行（不是 jar）時為「開發版」。 */
    public static String version() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "開發版" : v;
    }

    /** 例如「v1.4.1」或「開發版」。 */
    public static String versionLabel() {
        String v = version();
        return Character.isDigit(v.charAt(0)) ? "v" + v : v;
    }
}
