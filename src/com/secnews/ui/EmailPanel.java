package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.Channel;
import com.secnews.GmailClient;
import com.secnews.MonitorService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Gmail 寄信設定（v1.8）。應用程式密碼加密儲存。 */
final class EmailPanel extends JPanel {
    private final Channel ch;
    private final MonitorService monitor;
    private final Runnable onSaved;
    private final JCheckBox enabled = new JCheckBox("啟用 Gmail 通知");
    private final JTextField from = new JTextField(30);
    private final JPasswordField password = new JPasswordField(30);
    private final JTextArea to = new JTextArea(4, 44);
    private final JCheckBox show = new JCheckBox("顯示密碼");

    EmailPanel(Channel ch, MonitorService monitor, Runnable onSaved) {
        super(new BorderLayout(8, 8));
        this.ch = ch;
        this.monitor = monitor;
        this.onSaved = onSaved;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        int y = 0;
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; form.add(enabled, c);
        c.gridwidth = 1;
        y = row(form, c, y, "寄件 Gmail 帳號：", from);
        y = row(form, c, y, "應用程式密碼：", password);
        c.gridx = 1; c.gridy = y++; form.add(show, c);
        to.setLineWrap(true);
        y = row(form, c, y, "收件人（一行一個）：", new JScrollPane(to));

        JLabel help = new JLabel("<html><b>如何取得「應用程式密碼」：</b><br>"
                + "1. 登入 Google 帳戶 →「安全性」→ 開啟「兩步驟驗證」<br>"
                + "2. 在 Google 帳戶搜尋「應用程式密碼」→ 輸入名稱（例如 SecNewsMonitor）→ 建立<br>"
                + "3. 複製畫面上 16 個字母的密碼貼到上方（中間的空白可一起貼上）<br>"
                + "※ 不是 Google 帳戶的登入密碼。Google Workspace 帳號需管理員允許才能建立應用程式密碼。<br>"
                + "※ 透過 smtp.gmail.com（連接埠 465，全程加密）寄出；公司防火牆若封鎖此連接埠會寄不出去。<br>"
                + "※ 應用程式密碼本程式會加密儲存。選單名稱以 Google 實際畫面為準。</html>");
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.insets = new Insets(14, 4, 4, 4);
        form.add(help, c);
        add(UiUtil.titled("頻道「" + ch.getName() + "」的 Gmail 通知", form), BorderLayout.NORTH);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton save = new JButton("儲存");
        JButton test = new JButton("寄送測試信");
        save.addActionListener(e -> save());
        test.addActionListener(e -> test());
        btns.add(save); btns.add(test);
        add(btns, BorderLayout.SOUTH);

        password.setEchoChar('•');
        show.addActionListener(e -> password.setEchoChar(show.isSelected() ? (char) 0 : '•'));
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
        enabled.setSelected(ch.isEmailEnabled());
        from.setText(ch.getEmailFrom());
        to.setText(String.join("\n", ch.getEmailTo()));
        try { password.setText(ch.getEmailPassword()); } catch (Exception e) { AppLog.error("讀取 Gmail 設定失敗", e); }
    }

    private List<String> recipients() {
        List<String> out = new ArrayList<>();
        for (String s : to.getText().split("[,;\\s]+")) {
            if (s.isBlank()) continue;
            GmailClient.validateAddress(s.trim());
            if (!out.contains(s.trim())) out.add(s.trim());
        }
        return out;
    }

    private void save() {
        try {
            String f = from.getText().trim();
            String p = new String(password.getPassword());
            List<String> r = recipients();
            if (enabled.isSelected() || !f.isEmpty()) GmailClient.validateAddress(f);
            if (enabled.isSelected() || !p.isBlank()) p = GmailClient.normalizeAppPassword(p);
            if (enabled.isSelected() && r.isEmpty()) throw new IllegalArgumentException("請填寫至少一位收件人");
            ch.setEmail(enabled.isSelected(), f, r, p);
            AppLog.info("[" + ch.getName() + "] Gmail 設定已儲存（" + (enabled.isSelected() ? "啟用" : "停用") + "，" + r.size() + " 位收件人）");
            if (onSaved != null) onSaved.run();
            JOptionPane.showMessageDialog(this, "已儲存（應用程式密碼已加密）");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "輸入錯誤", JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            UiUtil.error(this, "儲存失敗", e);
        }
    }

    private void test() {
        final String f, p;
        final List<String> r;
        try {
            f = from.getText().trim();
            GmailClient.validateAddress(f);
            p = GmailClient.normalizeAppPassword(new String(password.getPassword()));
            r = recipients();
            if (r.isEmpty()) throw new IllegalArgumentException("請填寫至少一位收件人");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "輸入錯誤", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { monitor.sendTestEmail(f, p, r, ch.getName()); return null; }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    get();
                    AppLog.info("[" + ch.getName() + "] Gmail 測試信寄送成功");
                    JOptionPane.showMessageDialog(EmailPanel.this, "測試信已寄出，請到收件匣確認（也請檢查垃圾郵件）");
                } catch (Exception e) {
                    UiUtil.error(EmailPanel.this, "Gmail 測試寄送失敗", e.getCause() != null ? e.getCause() : e);
                }
            }
        }.execute();
    }
}
