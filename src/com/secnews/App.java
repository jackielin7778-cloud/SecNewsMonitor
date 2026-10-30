package com.secnews;

import com.secnews.ui.LoginDialog;
import com.secnews.ui.MainFrame;

import javax.swing.*;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;

/**
 * 程式進入點。支援 Windows、macOS、Linux。
 * 不帶參數：圖形介面；帶參數（--headless、--setup、--run-once…）：命令列／無介面模式，見 {@link Cli}。
 */
public final class App {
    private static FileChannel lockChannel;
    private static FileLock instanceLock;   // 保持參照，避免被 GC 釋放

    public static void main(String[] args) throws Exception {
        Cli.Command cmd = Cli.parse(args);
        if (cmd != Cli.Command.GUI) {
            System.exit(Cli.run(cmd, args));
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("找不到圖形介面（例如沒有 DISPLAY 的 Linux 伺服器）。");
            System.err.println("請改用無介面模式：第一次先執行 --setup，之後用 --headless 常駐執行。詳見 --help。");
            System.exit(2);
            return;
        }

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            System.setProperty("apple.awt.application.name", "SecNewsMonitor");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
        }

        // 單一執行個體：避免開兩個程式造成重複推播。
        // 先取得鎖再讀取資料（v1.5 升級時會搬移設定檔，不可在舊版仍執行時進行）。
        if (!acquireLock(Store.resolveDir())) {
            JOptionPane.showMessageDialog(null, "資安新聞監控已經在執行中。\n（若剛升級版本，請先結束舊版程式，例如系統匣圖示 → Exit／結束）",
                    "SecNewsMonitor", JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
        }
        Store store = new Store();
        AppLog.init(store.logsDir(), store::getLogRetentionDays);

        SwingUtilities.invokeAndWait(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        });

        // 登入；若使用者選擇「重設帳號」，重新顯示（此時為建立帳號畫面）。
        // v1.7.1：建立新帳號成功時才真正清除舊帳號；在建立帳號畫面按取消，原帳號保持不變。
        final boolean[] ok = new boolean[1];
        final boolean[] reset = new boolean[1];
        do {
            SwingUtilities.invokeAndWait(() -> {
                LoginDialog d = new LoginDialog(store, reset[0]);
                ok[0] = d.showDialog();
                reset[0] = d.isResetRequested();
            });
        } while (!ok[0] && reset[0]);
        if (!ok[0]) System.exit(0);

        AppLog.info("程式啟動，版本 " + Cli.version() + "，資料夾：" + store.dir() + "，Java " + System.getProperty("java.version")
                + "，" + System.getProperty("os.name"));
        logMigration(store);
        MonitorService monitor = new MonitorService(store);
        SchedulerService scheduler = new SchedulerService(store, monitor);

        SwingUtilities.invokeAndWait(() -> {
            MainFrame f = new MainFrame(store, monitor, scheduler);
            f.setVisible(true);
        });
        scheduler.start();
        Runtime.getRuntime().addShutdownHook(new Thread(scheduler::shutdown));
    }

    /** 由 v1.4 以前升級時，記錄轉移了哪些設定。 */
    static void logMigration(Store store) {
        java.util.List<String> m = store.migratedLegacy();
        if (m.isEmpty()) return;
        AppLog.info("已將舊版設定（" + String.join("、", m) + "）轉移到頻道「" + store.primary().getName()
                + "」；原檔備份在 " + store.dir().resolve("backup-before-1.5"));
    }

    /** 取得單一執行個體鎖；已有其他執行個體時回傳 false。 */
    static synchronized boolean acquireLock(Store store) throws IOException {
        return acquireLock(store.dir());
    }

    static synchronized boolean acquireLock(java.nio.file.Path dir) throws IOException {
        if (instanceLock != null) return true;
        java.nio.file.Files.createDirectories(dir);
        lockChannel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        instanceLock = lockChannel.tryLock();
        if (instanceLock == null) {
            lockChannel.close();
            lockChannel = null;
            return false;
        }
        return true;
    }

    static synchronized void releaseLock() {
        try {
            if (instanceLock != null) instanceLock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
        } finally {
            instanceLock = null;
            lockChannel = null;
        }
    }
}
