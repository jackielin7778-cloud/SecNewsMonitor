package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.PasswordRules;
import com.secnews.MonitorService;
import com.secnews.SchedulerService;
import com.secnews.Store;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** 主視窗：網站列表／監控關鍵字／排程設定／Google Chat／執行紀錄。 */
public final class MainFrame extends JFrame {
    private final Store store;
    private final SchedulerService scheduler;
    private TrayIcon trayIcon;

    public MainFrame(Store store, MonitorService monitor, SchedulerService scheduler) {
        super("資安新聞監控 SecNewsMonitor " + com.secnews.AppInfo.versionLabel());
        this.store = store;
        this.scheduler = scheduler;
        Image icon = makeIcon(64);
        setIconImage(icon);

        // LogPanel 先建立，才能收到其他面板初始化時的紀錄
        LogPanel logPanel = new LogPanel(store, scheduler);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("資安網站列表", new SitesPanel(store, monitor));
        tabs.addTab("監控關鍵字", new KeywordsPanel(store));
        tabs.addTab("監控排程", new SchedulePanel(store, scheduler));
        tabs.addTab("Google Chat 設定", new ChatPanel(store, monitor));
        tabs.addTab("執行紀錄", logPanel);
        getContentPane().add(tabs);

        setJMenuBar(buildMenu());
        setSize(900, 620);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(null);

        boolean tray = installTray(icon);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (tray) {
                    setVisible(false);
                    trayIcon.displayMessage("資安新聞監控", "程式仍在背景執行排程", TrayIcon.MessageType.INFO);
                } else {
                    confirmExit();
                }
            }
        });
    }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu acc = new JMenu("帳號");
        JMenuItem change = new JMenuItem("變更密碼…");
        change.addActionListener(e -> changePassword());
        JMenuItem exit = new JMenuItem("結束程式");
        exit.addActionListener(e -> confirmExit());
        acc.add(change);
        acc.addSeparator();
        acc.add(exit);
        bar.add(acc);
        JMenu help = new JMenu("說明");
        JMenuItem about = new JMenuItem("關於 SecNewsMonitor…");
        about.addActionListener(e -> showAbout());
        help.add(about);
        bar.add(help);
        return bar;
    }

    private void showAbout() {
        String msg = "<html><b>資安新聞監控 SecNewsMonitor</b><br><br>"
                + "版本：" + com.secnews.AppInfo.versionLabel() + "<br>"
                + "Java：" + System.getProperty("java.version") + "（" + System.getProperty("java.vendor") + "）<br>"
                + "作業系統：" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "<br>"
                + "資料夾：" + store.dir() + "</html>";
        JOptionPane.showMessageDialog(this, msg, "關於", JOptionPane.INFORMATION_MESSAGE,
                new ImageIcon(makeIcon(48)));
    }

    private void changePassword() {
        JPasswordField oldPw = new JPasswordField(18), p1 = new JPasswordField(18), p2 = new JPasswordField(18);
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("目前密碼：")); p.add(PasswordEye.wrap(oldPw));
        p.add(new JLabel("新密碼：")); p.add(PasswordEye.wrap(p1));
        p.add(new JLabel("確認新密碼：")); p.add(PasswordEye.wrap(p2));
        if (JOptionPane.showConfirmDialog(this, p, "變更密碼", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        char[] o = oldPw.getPassword(), n1 = p1.getPassword(), n2 = p2.getPassword();
        try {
            if (!store.login(store.accountUser(), o)) {
                JOptionPane.showMessageDialog(this, "目前密碼錯誤", "錯誤", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String err = PasswordRules.check(n1, n2);
            if (err != null) { JOptionPane.showMessageDialog(this, err, "錯誤", JOptionPane.WARNING_MESSAGE); return; }
            store.changePassword(n1);
            AppLog.info("密碼已變更");
            JOptionPane.showMessageDialog(this, "密碼已變更");
        } catch (Exception e) {
            UiUtil.error(this, "變更密碼失敗", e);
        } finally {
            Arrays.fill(o, '\0'); Arrays.fill(n1, '\0'); Arrays.fill(n2, '\0');
        }
    }

    private void confirmExit() {
        if (JOptionPane.showConfirmDialog(this, "結束後將停止自動監控，確定結束？", "結束程式",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            AppLog.info("程式結束");
            scheduler.shutdown();
            if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
            dispose();
            System.exit(0);
        }
    }

    /** Windows 為系統匣（System Tray），macOS 為選單列（Menu Bar）圖示。 */
    private boolean installTray(Image icon) {
        if (!SystemTray.isSupported()) return false;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open / 開啟");
            MenuItem run = new MenuItem("Run now / 立即執行");
            MenuItem exit = new MenuItem("Exit / 結束");
            open.addActionListener(e -> showWindow());
            run.addActionListener(e -> scheduler.runNow());
            exit.addActionListener(e -> SwingUtilities.invokeLater(() -> { showWindow(); confirmExit(); }));
            menu.add(open); menu.add(run); menu.addSeparator(); menu.add(exit);
            Dimension size = SystemTray.getSystemTray().getTrayIconSize();
            trayIcon = new TrayIcon(icon.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH),
                    "資安新聞監控 " + com.secnews.AppInfo.versionLabel(), menu);
            trayIcon.addActionListener(e -> showWindow());
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (Exception e) {
            AppLog.error("無法建立系統匣圖示，改為關閉視窗即結束", e);
            return false;
        }
    }

    private void showWindow() {
        SwingUtilities.invokeLater(() -> {
            setVisible(true);
            setExtendedState(getExtendedState() & ~Frame.ICONIFIED);
            toFront();
        });
    }

    /** 以程式畫出圖示（盾牌），不需額外圖檔。 */
    static Image makeIcon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Polygon shield = new Polygon(
                new int[]{s / 2, s - s / 8, s - s / 8, s / 2, s / 8, s / 8},
                new int[]{s / 16, s / 5, s / 2, s - s / 16, s / 2, s / 5}, 6);
        g.setColor(new Color(0x1565C0));
        g.fillPolygon(shield);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(s / 10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(new int[]{s * 3 / 10, s * 9 / 20, s * 7 / 10}, new int[]{s / 2, s * 13 / 20, s * 7 / 20}, 3);
        g.dispose();
        return img;
    }
}
