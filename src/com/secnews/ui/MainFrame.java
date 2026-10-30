package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.Channel;
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

/**
 * 主視窗。上方選擇頻道（例如「資安新聞」「AI 新聞」），
 * 下方分頁：網站列表／監控關鍵字／排程／Google Chat 都是該頻道自己的設定；執行紀錄為全部頻道共用。
 */
public final class MainFrame extends JFrame {
    private final Store store;
    private final MonitorService monitor;
    private final SchedulerService scheduler;
    private TrayIcon trayIcon;

    private final JTabbedPane tabs = new JTabbedPane();
    private final DefaultComboBoxModel<Channel> channelModel = new DefaultComboBoxModel<>();
    private final JComboBox<Channel> channelBox = new JComboBox<>(channelModel);
    private final JCheckBox channelEnabled = new JCheckBox("啟用此頻道");
    private final JLabel channelInfo = new JLabel(" ");
    private Channel current;
    private SchedulePanel schedulePanel;
    private boolean switching;
    private int channelTabs;      // 目前頻道佔用的分頁數（新聞 4 個、商品 3 個）

    public MainFrame(Store store, MonitorService monitor, SchedulerService scheduler) {
        super("資安新聞監控 SecNewsMonitor " + com.secnews.AppInfo.versionLabel());
        this.store = store;
        this.scheduler = scheduler;
        Image icon = makeIcon(64);
        setIconImage(icon);

        this.monitor = monitor;
        // LogPanel 先建立，才能收到其他面板初始化時的紀錄
        LogPanel logPanel = new LogPanel(store, scheduler);
        for (Channel c : store.channels()) channelModel.addElement(c);
        current = store.primary();
        channelBox.setSelectedItem(current);
        buildChannelTabs();
        tabs.addTab("執行紀錄（全部頻道）", logPanel);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildChannelBar(), BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        updateChannelInfo();

        scheduler.setOnNextRunChanged(() -> SwingUtilities.invokeLater(() -> {
            if (schedulePanel != null) schedulePanel.refreshNext();
            updateChannelInfo();
        }));

        setJMenuBar(buildMenu());
        setSize(980, 660);
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

    // ------------------------------------------------------------------ 頻道

    private JPanel buildChannelBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xBDBDBD)));
        JLabel l = new JLabel("頻道：");
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        bar.add(l);
        channelBox.setPrototypeDisplayValue(null);
        channelBox.setPreferredSize(new Dimension(200, channelBox.getPreferredSize().height));
        channelBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                putClientProperty("html.disable", Boolean.TRUE);
                if (value instanceof Channel c) setText((c.isProduct() ? "［商品］" : "") + c.getName() + (c.isEnabled() ? "" : "（停用）"));
                return this;
            }
        });
        channelBox.addActionListener(e -> onChannelSelected());
        bar.add(channelBox);
        JButton add = new JButton("新增頻道");
        JButton rename = new JButton("重新命名");
        JButton del = new JButton("刪除頻道");
        add.addActionListener(e -> addChannel());
        rename.addActionListener(e -> renameChannel());
        del.addActionListener(e -> deleteChannel());
        bar.add(add);
        bar.add(rename);
        bar.add(del);
        bar.add(Box.createHorizontalStrut(8));
        channelEnabled.addActionListener(e -> toggleEnabled());
        bar.add(channelEnabled);
        bar.add(Box.createHorizontalStrut(8));
        channelInfo.setForeground(new Color(0x424242));
        bar.add(channelInfo);
        return bar;
    }

    /** 重建前 4 個分頁（屬於目前頻道的設定）。 */
    private void buildChannelTabs() {
        int sel = tabs.getSelectedIndex();
        boolean onLog = sel >= 0 && tabs.getComponentAt(sel) instanceof LogPanel;
        for (int i = 0; i < channelTabs && tabs.getTabCount() > 0 && !(tabs.getComponentAt(0) instanceof LogPanel); i++) {
            tabs.removeTabAt(0);
        }
        schedulePanel = new SchedulePanel(current, scheduler);
        int k = 0;
        if (current.isProduct()) {
            tabs.insertTab("商品清單", null, new ProductsPanel(current, monitor), null, k++);
        } else {
            tabs.insertTab("網站列表", null, new SitesPanel(current, monitor), null, k++);
            tabs.insertTab("監控關鍵字", null, new KeywordsPanel(current), null, k++);
        }
        tabs.insertTab("監控排程", null, schedulePanel, null, k++);
        tabs.insertTab("推播設定", null, new NotifyPanel(current, monitor, this::updateChannelInfo), null, k++);
        channelTabs = k;
        if (onLog) tabs.setSelectedIndex(k);
        else if (sel >= 0 && sel < k) tabs.setSelectedIndex(sel);
        else tabs.setSelectedIndex(0);
        channelEnabled.setSelected(current.isEnabled());
    }

    private void onChannelSelected() {
        if (switching) return;
        Channel c = (Channel) channelBox.getSelectedItem();
        if (c == null || c == current) return;
        if (schedulePanel != null && !schedulePanel.confirmLeave()) {
            switching = true;
            channelBox.setSelectedItem(current);
            switching = false;
            return;
        }
        current = c;
        buildChannelTabs();
        updateChannelInfo();
    }

    private void selectChannel(Channel c) {
        switching = true;
        channelBox.setSelectedItem(c);
        switching = false;
        current = c;
        buildChannelTabs();
        updateChannelInfo();
    }

    private void updateChannelInfo() {
        if (current == null) return;
        java.time.ZonedDateTime t = scheduler.nextRunAt(current);
        String hook = current.hasAnyNotifier() ? "" : "　⚠ 尚未設定推播（Google Chat／LINE／Gmail）";
        channelInfo.setText(!current.isEnabled() ? "此頻道已停用，不會自動執行" + hook
                : (t == null ? "下次執行：—" : "下次執行：" + t.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))) + hook);
        channelBox.repaint();
    }

    private void addChannel() {
        if (schedulePanel != null && !schedulePanel.confirmLeave()) return;
        Object[] types = {"新聞監控（網站＋關鍵字）", "商品監控（PChome／momo／露天）"};
        Object t = JOptionPane.showInputDialog(this, "頻道類型：", "新增頻道", JOptionPane.PLAIN_MESSAGE, null, types, types[0]);
        if (t == null) return;
        boolean product = t == types[1];
        String name = askName("新增頻道", product ? "頻道名稱（例如：商品比價）：" : "頻道名稱（例如：AI 新聞）：", "", null);
        if (name == null) return;
        try {
            Channel c = store.createChannel(name, product ? Channel.Type.PRODUCT : Channel.Type.NEWS);
            AppLog.info("新增頻道：" + c.getName());
            channelModel.addElement(c);
            selectChannel(c);
            scheduler.reschedule(c);
            tabs.setSelectedIndex(0);
            JOptionPane.showMessageDialog(this, product
                    ? "<html>已新增商品監控頻道「" + escape(c.getName()) + "」。接下來請設定：<br>"
                    + "1. 商品清單：勾選搜尋網站，新增要監控的商品（搜尋關鍵字、價格範圍、排除字），按「測試搜尋」確認<br>"
                    + "2. 監控排程（建議每 30～60 分鐘）<br>3. Google Chat 設定：貼上要推播的聊天室 Webhook</html>"
                    : "<html>已新增頻道「" + escape(c.getName()) + "」。接下來請設定：<br>"
                    + "1. 網站列表：新增網站，或按「匯入…」載入網站清單檔（例如 docs/ai-sites.tsv）<br>"
                    + "2. 監控關鍵字<br>3. 監控排程<br>4. Google Chat 設定：貼上這個頻道要推播的聊天室 Webhook</html>");
        } catch (Exception e) {
            UiUtil.error(this, "新增頻道失敗", e);
        }
    }

    private void renameChannel() {
        String old = current.getName();
        String name = askName("重新命名頻道", "新名稱：", old, current);
        if (name == null || name.equals(old)) return;
        try {
            current.setName(name);
            AppLog.info("頻道重新命名：" + old + " → " + name);
            buildChannelTabs();      // 面板標題含頻道名稱
            channelBox.repaint();
        } catch (Exception e) {
            UiUtil.error(this, "重新命名失敗", e);
        }
    }

    private void deleteChannel() {
        if (store.channels().size() <= 1) {
            JOptionPane.showMessageDialog(this, "至少要保留一個頻道");
            return;
        }
        String name = current.getName();
        String typed = JOptionPane.showInputDialog(this,
                "刪除頻道「" + name + "」會一併刪除它的網站列表、關鍵字、排程、Webhook 與已讀紀錄，無法復原。\n"
                        + "（建議先在「網站列表」按「匯出…」備份）\n\n請輸入頻道名稱確認：",
                "刪除頻道", JOptionPane.WARNING_MESSAGE);
        if (typed == null) return;
        if (!typed.trim().equals(name)) {
            JOptionPane.showMessageDialog(this, "名稱不符，未刪除");
            return;
        }
        try {
            Channel del = current;
            store.deleteChannel(del);
            AppLog.info("刪除頻道：" + name);
            switching = true;
            channelModel.removeElement(del);
            switching = false;
            selectChannel(store.primary());
            scheduler.reschedule();
        } catch (Exception e) {
            UiUtil.error(this, "刪除頻道失敗", e);
        }
    }

    private void toggleEnabled() {
        try {
            current.setEnabled(channelEnabled.isSelected());
            AppLog.info("[" + current.getName() + "] 頻道" + (current.isEnabled() ? "已啟用" : "已停用（不會自動執行）"));
            scheduler.reschedule(current);
            updateChannelInfo();
        } catch (Exception e) {
            UiUtil.error(this, "儲存失敗", e);
        }
    }

    private String askName(String title, String prompt, String initial, Channel except) {
        String v = initial;
        while (true) {
            v = (String) JOptionPane.showInputDialog(this, prompt, title, JOptionPane.PLAIN_MESSAGE, null, null, v);
            if (v == null) return null;
            v = v.trim();
            String err = v.isEmpty() ? "名稱不可空白"
                    : v.length() > 40 ? "名稱最多 40 個字"
                    : store.channelNameExists(v, except) ? "頻道名稱已存在" : null;
            if (err == null) return v;
            JOptionPane.showMessageDialog(this, err, "輸入錯誤", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ 選單

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
                + "頻道數：" + store.channels().size() + "<br>"
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
        if (schedulePanel != null && !schedulePanel.confirmLeave()) return;
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
            MenuItem run = new MenuItem("Run all now / 立即執行全部頻道");
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
