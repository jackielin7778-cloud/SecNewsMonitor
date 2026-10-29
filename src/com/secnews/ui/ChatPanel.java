package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.GoogleChatClient;
import com.secnews.MonitorService;
import com.secnews.Channel;
import com.secnews.Store;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Google Chat 串接設定。
 * 可直接貼上整段 Webhook URL，或分別填 Space ID、Key、Token。
 * 儲存時以登入密碼推導的金鑰做 AES-GCM 加密，不會以明文存檔。
 */
final class ChatPanel extends JPanel {
    private final Channel store;
    private final MonitorService monitor;

    private final JCheckBox enabled = new JCheckBox("啟用 Google Chat 推播");
    private final JRadioButton modeUrl = new JRadioButton("貼上完整 Webhook URL", true);
    private final JRadioButton modeParts = new JRadioButton("分欄位輸入");
    private final JPasswordField urlField = new JPasswordField(48);
    private final JTextField spaceField = new JTextField(24);
    private final JPasswordField keyField = new JPasswordField(36);
    private final JPasswordField tokenField = new JPasswordField(36);
    private final JCheckBox show = new JCheckBox("顯示內容");
    private final JSpinner maxPerRun = new JSpinner(new SpinnerNumberModel(Store.DEFAULT_MAX_PER_RUN, 1, 200, 5));

    ChatPanel(Channel store, MonitorService monitor) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.monitor = monitor;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        ButtonGroup g = new ButtonGroup();
        g.add(modeUrl); g.add(modeParts);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        int y = 0;
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; form.add(enabled, c);
        c.gridy = y++; form.add(modeUrl, c);
        c.gridwidth = 1;
        y = addRow(form, c, y, "Webhook URL：", urlField);
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; form.add(modeParts, c);
        c.gridwidth = 1;
        y = addRow(form, c, y, "Space ID：", spaceField);
        y = addRow(form, c, y, "Key：", keyField);
        y = addRow(form, c, y, "Token：", tokenField);
        c.gridx = 1; c.gridy = y++; form.add(show, c);
        JPanel maxRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        maxRow.add(maxPerRun);
        maxRow.add(new JLabel("則（超過的只記在「執行紀錄」，避免洗版）"));
        y = addRow(form, c, y, "單次最多推播：", maxRow);

        JLabel help = new JLabel("<html><b>如何取得 Webhook：</b><br>"
                + "1. 在 Google Chat 開啟要接收通知的聊天室（Space）<br>"
                + "2. 點聊天室名稱 → 「應用程式與整合」（Apps &amp; integrations）→ 「Webhook」→ 新增<br>"
                + "3. 複製產生的網址，格式為：<br>"
                + "&nbsp;&nbsp;&nbsp;https://chat.googleapis.com/v1/spaces/<i>SPACE_ID</i>/messages?key=<i>KEY</i>&amp;token=<i>TOKEN</i><br>"
                + "※ Token 等同密碼，請勿外流。本程式會加密儲存。<br>"
                + "※ Google Workspace 管理員需允許使用 Webhook；選單名稱以 Google 實際畫面為準。</html>");
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.insets = new Insets(14, 4, 4, 4);
        form.add(help, c);

        add(UiUtil.titled("頻道「" + store.getName() + "」的 Google Chat 傳入 Webhook（每個頻道可推到不同聊天室）", form), BorderLayout.NORTH);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton save = new JButton("儲存");
        JButton test = new JButton("傳送測試訊息");
        save.addActionListener(e -> save());
        test.addActionListener(e -> test());
        btns.add(save);
        btns.add(test);
        add(btns, BorderLayout.SOUTH);

        modeUrl.addActionListener(e -> updateMode());
        modeParts.addActionListener(e -> updateMode());
        show.addActionListener(e -> {
            char echo = show.isSelected() ? (char) 0 : '•';
            urlField.setEchoChar(echo); keyField.setEchoChar(echo); tokenField.setEchoChar(echo);
        });
        urlField.setEchoChar('•'); keyField.setEchoChar('•'); tokenField.setEchoChar('•');

        load();
    }

    private static int addRow(JPanel p, GridBagConstraints c, int y, String label, JComponent f) {
        c.gridx = 0; c.gridy = y; c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(f, c);
        c.fill = GridBagConstraints.NONE;
        return y + 1;
    }

    private void load() {
        enabled.setSelected(store.isChatEnabled());
        maxPerRun.setValue(store.getMaxPushPerRun());
        try {
            String url = store.getWebhookUrl();
            urlField.setText(url);
            fillParts(url);
        } catch (Exception e) {
            AppLog.error("讀取 Webhook 設定失敗", e);
        }
        updateMode();
    }

    private void fillParts(String url) {
        if (url == null || url.isBlank()) return;
        try {
            URI u = URI.create(url);
            String path = u.getPath();                          // /v1/spaces/XXX/messages
            String[] seg = path.split("/");
            for (int i = 0; i < seg.length - 1; i++) if ("spaces".equals(seg[i])) spaceField.setText(seg[i + 1]);
            if (u.getRawQuery() != null) {
                for (String kv : u.getRawQuery().split("&")) {
                    String[] p = kv.split("=", 2);
                    if (p.length < 2) continue;
                    String v = URLDecoder.decode(p[1], StandardCharsets.UTF_8);
                    if ("key".equals(p[0])) keyField.setText(v);
                    if ("token".equals(p[0])) tokenField.setText(v);
                }
            }
        } catch (Exception ignored) {}
    }

    private void updateMode() {
        boolean url = modeUrl.isSelected();
        urlField.setEnabled(url);
        spaceField.setEnabled(!url); keyField.setEnabled(!url); tokenField.setEnabled(!url);
    }

    private String currentUrl() {
        if (modeUrl.isSelected()) return new String(urlField.getPassword()).trim();
        String s = spaceField.getText().trim(), k = new String(keyField.getPassword()).trim(),
               t = new String(tokenField.getPassword()).trim();
        if (s.isEmpty() || k.isEmpty() || t.isEmpty()) throw new IllegalArgumentException("Space ID、Key、Token 都必須填寫");
        return GoogleChatClient.buildWebhookUrl(s, k, t);
    }

    private void save() {
        try {
            String url = currentUrl();
            if (!url.isEmpty()) GoogleChatClient.validate(url);
            store.setWebhookUrl(url);
            store.setChatEnabled(enabled.isSelected());
            store.setMaxPushPerRun((Integer) maxPerRun.getValue());
            urlField.setText(url);
            fillParts(url);
            AppLog.info("[" + store.getName() + "] Google Chat 設定已儲存（" + (enabled.isSelected() ? "啟用" : "停用")
                    + "，單次最多推播 " + maxPerRun.getValue() + " 則）");
            JOptionPane.showMessageDialog(this, "已儲存（Webhook 已加密）");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "輸入錯誤", JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            UiUtil.error(this, "儲存失敗", e);
        }
    }

    private void test() {
        final String url;
        try {
            url = currentUrl();
            GoogleChatClient.validate(url);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "輸入錯誤", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { monitor.sendTest(url, store.getName()); return null; }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    get();
                    AppLog.info("[" + store.getName() + "] Google Chat 測試訊息傳送成功");
                    JOptionPane.showMessageDialog(ChatPanel.this, "測試訊息已送出，請到 Google Chat 確認");
                } catch (Exception e) {
                    UiUtil.error(ChatPanel.this, "測試傳送失敗", e.getCause() != null ? e.getCause() : e);
                }
            }
        }.execute();
    }
}
