package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.SchedulerService;
import com.secnews.Store;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.time.LocalDate;

/**
 * 執行紀錄。畫面只保留最近 1000 行，避免長時間執行吃記憶體；
 * 完整紀錄寫在 logs/monitor-YYYY-MM-DD.log（一天一檔），可設定保留天數自動刪除。
 */
final class LogPanel extends JPanel {
    private static final int MAX_LINES = 1000;
    private final JTextArea area = new JTextArea();
    private final Store store;
    private final JSpinner retention;

    LogPanel(Store store, SchedulerService scheduler) {
        super(new BorderLayout(8, 8));
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        add(new JScrollPane(area), BorderLayout.CENTER);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton run = new JButton("立即執行全部頻道");
        JButton clear = new JButton("清除畫面");
        JButton open = new JButton("開啟紀錄檔資料夾");
        run.addActionListener(e -> scheduler.runNow());
        clear.addActionListener(e -> area.setText(""));
        open.addActionListener(e -> {
            try { Desktop.getDesktop().open(store.logsDir().toFile()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "紀錄檔資料夾：\n" + store.logsDir()); }
        });
        btns.add(run);
        btns.add(clear);
        btns.add(open);

        JPanel keep = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        retention = new JSpinner(new SpinnerNumberModel(store.getLogRetentionDays(), 0, Store.MAX_LOG_RETENTION_DAYS, 1));
        ((JSpinner.DefaultEditor) retention.getEditor()).getTextField().setColumns(4);
        JButton save = new JButton("儲存");
        JButton cleanNow = new JButton("立即清理");
        save.addActionListener(e -> saveRetention());
        cleanNow.addActionListener(e -> cleanNow());
        keep.add(new JLabel("紀錄檔一天一個，保留最近"));
        keep.add(retention);
        keep.add(new JLabel("天（0 = 永久保留），超過的自動刪除"));
        keep.add(save);
        keep.add(cleanNow);

        JLabel path = new JLabel("  紀錄檔位置：" + store.logsDir() + "（monitor-YYYY-MM-DD.log）");
        path.setFont(path.getFont().deriveFont(path.getFont().getSize() - 1f));

        for (JComponent c : new JComponent[]{btns, keep, path}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            south.add(c);
            south.add(Box.createVerticalStrut(4));
        }
        add(south, BorderLayout.SOUTH);

        AppLog.addListener(line -> SwingUtilities.invokeLater(() -> append(line)));
    }

    private void saveRetention() {
        int days = (Integer) retention.getValue();
        try {
            store.setLogRetentionDays(days);
            AppLog.info("紀錄檔保留天數已設定為：" + (days == 0 ? "永久保留" : days + " 天"));
            int n = AppLog.cleanup(LocalDate.now());
            if (n > 0) AppLog.info("已刪除 " + n + " 個超過保留天數的紀錄檔");
            JOptionPane.showMessageDialog(this, "已儲存" + (n > 0 ? "，並刪除 " + n + " 個舊紀錄檔" : ""));
        } catch (Exception e) {
            UiUtil.error(this, "儲存失敗", e);
        }
    }

    private void cleanNow() {
        int days = store.getLogRetentionDays();
        if (days == 0) {
            JOptionPane.showMessageDialog(this, "目前設定為永久保留，不會刪除任何紀錄檔。");
            return;
        }
        if ((Integer) retention.getValue() != days) {
            JOptionPane.showMessageDialog(this, "畫面上的天數尚未儲存，請先按「儲存」。");
            return;
        }
        int n = AppLog.cleanup(LocalDate.now());
        AppLog.info("手動清理紀錄檔：刪除 " + n + " 個（保留最近 " + days + " 天）");
        JOptionPane.showMessageDialog(this, "已刪除 " + n + " 個超過 " + days + " 天的紀錄檔");
    }

    private void append(String line) {
        area.append(line + "\n");
        int extra = area.getLineCount() - MAX_LINES;
        if (extra > 0) {
            try { area.replaceRange("", 0, area.getLineEndOffset(extra - 1)); } catch (BadLocationException ignored) {}
        }
        area.setCaretPosition(area.getDocument().getLength());
    }
}
