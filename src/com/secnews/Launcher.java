package com.secnews;

import javax.swing.JOptionPane;

/**
 * 啟動器：以 Java 8 也能載入的方式編譯（--release 8），
 * 先檢查 Java 版本，不足 17 時顯示清楚的提示，足夠才啟動主程式。
 */
public final class Launcher {
    private static final int REQUIRED = 17;

    public static void main(String[] args) throws Exception {
        String spec = System.getProperty("java.specification.version", "0");
        int major = spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec.split("\\.")[0]);
        if (major < REQUIRED) {
            String msg = "資安新聞監控需要 Java " + REQUIRED + " 以上版本。\n\n"
                    + "目前使用的 Java：" + System.getProperty("java.version") + "\n"
                    + "位置：" + System.getProperty("java.home") + "\n\n"
                    + "請安裝 JDK 17 或 21（可與現有的 Java 8 並存），\n"
                    + "再用 run-mac.command / run-windows.bat 啟動。";
            System.err.println(msg);
            try {
                JOptionPane.showMessageDialog(null, msg, "Java 版本不足", JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
                // 無圖形環境時只輸出到主控台
            }
            System.exit(1);
        }
        Class.forName("com.secnews.App").getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
