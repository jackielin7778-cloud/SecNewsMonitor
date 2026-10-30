package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.PasswordRules;
import com.secnews.Store;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/** 登入畫面。第一次啟動時改為「建立帳號」畫面。 */
public final class LoginDialog extends JDialog {
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60_000;

    private final Store store;
    private final boolean setupMode;
    private final JTextField userField = new JTextField(18);
    private final JPasswordField passField = new JPasswordField(18);
    private final JPasswordField confirmField = new JPasswordField(18);
    private final JLabel msg = new JLabel(" ");
    private final JButton okBtn;
    private boolean success;
    private int fails;
    private long lockedUntil;

    /** 重設帳號模式：建立新帳號成功時才清除舊帳號（v1.7.1）。 */
    private final boolean resetMode;

    public LoginDialog(Store store) { this(store, false); }

    public LoginDialog(Store store, boolean resetMode) {
        super((Frame) null, "資安新聞監控 " + com.secnews.AppInfo.versionLabel() + " - "
                + (store.hasAccount() && !resetMode ? "登入" : resetMode ? "重設帳號" : "建立帳號"), true);
        this.store = store;
        this.resetMode = resetMode && store.hasAccount();
        this.setupMode = !store.hasAccount() || this.resetMode;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.anchor = GridBagConstraints.WEST;

        JLabel title = new JLabel(resetMode ? "重設帳號：請建立新的管理員帳號（按取消則保留原帳號）"
                : setupMode ? "第一次使用，請建立管理員帳號" : "請輸入帳號密碼");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 2f));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        form.add(title, c);
        c.gridwidth = 1;

        JPanel passRow = PasswordEye.wrap(passField);
        // 帳號欄右側留出與眼睛按鈕同寬的空白，讓三個欄位右緣對齊
        JPanel userRow = new JPanel(new BorderLayout(2, 0));
        userRow.setOpaque(false);
        userRow.add(userField, BorderLayout.CENTER);
        userRow.add(Box.createHorizontalStrut(passRow.getComponent(1).getPreferredSize().width), BorderLayout.EAST);

        addRow(form, c, 1, "帳號：", userRow);
        addRow(form, c, 2, "密碼：", passRow);
        if (setupMode) addRow(form, c, 3, "確認密碼：", PasswordEye.wrap(confirmField));
        else userField.setText(store.accountUser());

        msg.setForeground(new Color(0xC62828));
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2;
        form.add(msg, c);

        okBtn = new JButton(setupMode ? "建立並登入" : "登入");
        JButton cancel = new JButton("離開");
        okBtn.addActionListener(e -> submit());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(okBtn);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.EAST);
        if (!setupMode) {
            JButton forgot = new JButton("忘記密碼？重設帳號");
            forgot.setBorderPainted(false);
            forgot.setContentAreaFilled(false);
            forgot.setForeground(new Color(0x1565C0));
            forgot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            forgot.addActionListener(e -> resetAccount());
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
            left.add(forgot);
            south.add(left, BorderLayout.WEST);
        }

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(okBtn);
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> (setupMode || userField.getText().isEmpty() ? userField : passField).requestFocusInWindow());
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        p.add(field, c);
    }

    private void submit() {
        if (System.currentTimeMillis() < lockedUntil) {
            msg.setText("錯誤次數過多，請 " + ((lockedUntil - System.currentTimeMillis()) / 1000 + 1) + " 秒後再試");
            return;
        }
        String user = userField.getText().trim();
        char[] pw = passField.getPassword();
        if (user.isEmpty() || pw.length == 0) { msg.setText("帳號與密碼不可空白"); return; }

        if (setupMode) {
            char[] confirm = confirmField.getPassword();
            String err = PasswordRules.check(pw, confirm);
            Arrays.fill(confirm, '\0');
            if (err != null) { msg.setText(err); Arrays.fill(pw, '\0'); return; }
        }

        okBtn.setEnabled(false);
        msg.setText("驗證中…");
        // PBKDF2 需要一點時間，放到背景執行緒以免畫面卡住
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() throws Exception {
                try {
                    if (resetMode) {
                        store.resetAndCreateAccount(user, pw);
                        AppLog.info("帳號已重設（忘記密碼），Google Chat Webhook 已清除，需重新設定");
                        return true;
                    }
                    if (setupMode) { store.createAccount(user, pw); return true; }
                    return store.login(user, pw);
                } finally {
                    Arrays.fill(pw, '\0');
                }
            }
            @Override protected void done() {
                okBtn.setEnabled(true);
                try {
                    if (get()) {
                        success = true;
                        AppLog.info("使用者 " + user + (setupMode ? " 建立帳號並登入" : " 登入成功"));
                        dispose();
                    } else {
                        fails++;
                        passField.setText("");
                        if (fails >= MAX_FAILS) {
                            lockedUntil = System.currentTimeMillis() + LOCK_MS;
                            fails = 0;
                            msg.setText("錯誤次數過多，鎖定 60 秒");
                            AppLog.info("登入失敗次數過多，暫時鎖定");
                        } else {
                            msg.setText("帳號或密碼錯誤（剩餘 " + (MAX_FAILS - fails) + " 次）");
                        }
                    }
                } catch (Exception ex) {
                    msg.setText("發生錯誤：" + ex.getMessage());
                }
            }
        }.execute();
    }

    private void resetAccount() {
        String msg = "<html><b>密碼無法找回，只能重設帳號。</b><br><br>"
                + "會刪除：帳號密碼、已儲存的 Google Chat Webhook（需重新貼上）<br>"
                + "會保留：網站列表、關鍵字、排程、已讀紀錄、執行紀錄<br><br>"
                + "接著會出現「建立帳號」畫面；建立新帳號後才會清除，按取消則保持原狀。<br><br>"
                + "請輸入 <b>RESET</b> 確認：</html>";
        String in = JOptionPane.showInputDialog(this, msg, "重設帳號", JOptionPane.WARNING_MESSAGE);
        if (in == null) return;
        if (!"RESET".equals(in.trim())) {
            JOptionPane.showMessageDialog(this, "輸入不符，未重設。", "重設帳號", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        resetRequested = true;          // v1.7.1：此時還不清除，建立新帳號成功時才清除
        dispose();
    }

    private boolean resetRequested;

    /** 使用者是否按了「重設帳號」（呼叫端應重新顯示建立帳號畫面）。 */
    public boolean isResetRequested() { return resetRequested; }

    /** 顯示對話框並回傳是否登入成功。 */
    public boolean showDialog() {
        setVisible(true);
        return success;
    }
}
