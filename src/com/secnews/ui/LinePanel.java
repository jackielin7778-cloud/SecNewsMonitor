package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.Channel;
import com.secnews.LineClient;
import com.secnews.MonitorService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** LINE 推播設定（v1.8，LINE 官方帳號 Messaging API）。Token 加密儲存。 */
final class LinePanel extends JPanel {
    private final Channel ch;
    private final MonitorService monitor;
    private final Runnable onSaved;
    private final JCheckBox enabled = new JCheckBox("啟用 LINE 推播");
    private final JRadioButton push = new JRadioButton("推給指定的人（填使用者 ID）", true);
    private final JRadioButton broadcast = new JRadioButton("推給官方帳號的所有好友");
    private final JPasswordField token = new JPasswordField(44);
    private final JTextArea ids = new JTextArea(4, 44);
    private final JCheckBox show = new JCheckBox("顯示 Token");

    LinePanel(Channel ch, MonitorService monitor, Runnable onSaved) {
        super(new BorderLayout(8, 8));
        this.ch = ch;
        this.monitor = monitor;
        this.onSaved = onSaved;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        ButtonGroup g = new ButtonGroup();
        g.add(push); g.add(broadcast);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        int y = 0;
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; form.add(enabled, c);
        c.gridwidth = 1;
        y = row(form, c, y, "Channel access token：", token);
        c.gridx = 1; c.gridy = y++; form.add(show, c);
        y = row(form, c, y, "推播對象：", push);
        y = row(form, c, y, "", broadcast);
        ids.setLineWrap(true);
        y = row(form, c, y, "使用者 ID（一行一個）：", new JScrollPane(ids));

        JLabel help = new JLabel("<html><b>設定步驟（LINE Notify 已於 2025/3/31 停止服務，改用 LINE 官方帳號）：</b><br>"
                + "1. 建立 LINE 官方帳號，並在官方帳號的設定中啟用 Messaging API<br>"
                + "2. 到 LINE Developers（developers.line.biz）開啟該官方帳號的 channel →「Messaging API」頁籤 → 發行 Channel access token（long-lived）<br>"
                + "3. 「Basic settings」頁籤最下方的「Your user ID」就是你自己的使用者 ID（U 開頭）<br>"
                + "4. 收件人需先把官方帳號加為好友才收得到<br>"
                + "※ 免費方案（輕用量）每月 200 則：每次推播，每位收件人（或每位好友）算 1 則，超過後當月無法傳送。<br>"
                + "※ 群組 ID 需透過 Webhook 伺服器取得，本程式不提供。選單名稱以 LINE 實際畫面為準。<br>"
                + "※ Token 等同密碼，本程式會加密儲存。</html>");
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.insets = new Insets(14, 4, 4, 4);
        form.add(help, c);
        add(UiUtil.titled("頻道「" + ch.getName() + "」的 LINE 推播", form), BorderLayout.NORTH);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton save = new JButton("儲存");
        JButton test = new JButton("傳送測試訊息");
        save.addActionListener(e -> save());
        test.addActionListener(e -> test());
        btns.add(save); btns.add(test);
        add(btns, BorderLayout.SOUTH);

        token.setEchoChar('•');
        show.addActionListener(e -> token.setEchoChar(show.isSelected() ? (char) 0 : '•'));
        push.addActionListener(e -> ids.setEnabled(true));
        broadcast.addActionListener(e -> ids.setEnabled(false));
        load();
    }

    private static int row(JPanel p, GridBagConstraints c, int y, String label, JComponent f) {
        c.gridx = 0; c.gridy = y; c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(f, c);
        c.fill = GridBagConstraints.NONE;
        return y + 1;
    }

    private void load() {
        enabled.setSelected(ch.isLineEnabled());
        boolean b = ch.getLineMode() == Channel.LineMode.BROADCAST;
        broadcast.setSelected(b); push.setSelected(!b);
        ids.setEnabled(!b);
        ids.setText(String.join("\n", ch.getLineTo()));
        try { token.setText(ch.getLineToken()); } catch (Exception e) { AppLog.error("讀取 LINE 設定失敗", e); }
    }

    private List<String> currentIds() {
        List<String> out = new ArrayList<>();
        for (String s : ids.getText().split("[,;\\s]+")) {
            if (s.isBlank()) continue;
            LineClient.validateId(s.trim());
            if (!out.contains(s.trim())) out.add(s.trim());
        }
        return out;
    }

    private Channel.LineMode mode() { return broadcast.isSelected() ? Channel.LineMode.BROADCAST : Channel.LineMode.PUSH; }

    private void save() {
        try {
            String t = new String(token.getPassword()).trim();
            List<String> to = currentIds();
            if (enabled.isSelected()) {
                LineClient.validateToken(t);
                if (mode() == Channel.LineMode.PUSH && to.isEmpty()) throw new IllegalArgumentException("請填寫至少一個使用者 ID，或改選「推給官方帳號的所有好友」");
            } else if (!t.isEmpty()) {
                LineClient.validateToken(t);
            }
            ch.setLine(enabled.isSelected(), mode(), to, t);
            AppLog.info("[" + ch.getName() + "] LINE 設定已儲存（" + (enabled.isSelected() ? "啟用" : "停用") + "，"
                    + (mode() == Channel.LineMode.BROADCAST ? "所有好友" : to.size() + " 位收件人") + "）");
            if (onSaved != null) onSaved.run();
            JOptionPane.showMessageDialog(this, "已儲存（Token 已加密）");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "輸入錯誤", JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            UiUtil.error(this, "儲存失敗", e);
        }
    }

    private void test() {
        final String t;
        final List<String> to;
        try {
            t = new String(token.getPassword()).trim();
            LineClient.validateToken(t);
            to = currentIds();
            if (mode() == Channel.LineMode.PUSH && to.isEmpty()) throw new IllegalArgumentException("請填寫至少一個使用者 ID");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "輸入錯誤", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (mode() == Channel.LineMode.BROADCAST && JOptionPane.showConfirmDialog(this,
                "測試訊息會送給官方帳號的所有好友（每位好友算 1 則），確定傳送？", "傳送測試訊息",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        final Channel.LineMode m = mode();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { monitor.sendTestLine(t, m, to, ch.getName()); return null; }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    get();
                    AppLog.info("[" + ch.getName() + "] LINE 測試訊息傳送成功");
                    JOptionPane.showMessageDialog(LinePanel.this, "測試訊息已送出，請到 LINE 確認");
                } catch (Exception e) {
                    UiUtil.error(LinePanel.this, "LINE 測試傳送失敗", e.getCause() != null ? e.getCause() : e);
                }
            }
        }.execute();
    }
}
