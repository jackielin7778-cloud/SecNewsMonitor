package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.Channel;
import com.secnews.Store;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 監控關鍵字設定。可一次輸入多個，用逗號分隔，例如：Java, Tomcat, OpenJDK */
final class KeywordsPanel extends JPanel {
    private final Channel store;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JTextField input = new JTextField(28);

    KeywordsPanel(Channel store) {
        super(new BorderLayout(8, 8));
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.add(new JLabel("關鍵字："));
        top.add(input);
        JButton addBtn = new JButton("新增");
        addBtn.addActionListener(e -> add());
        input.addActionListener(e -> add());
        top.add(addBtn);
        top.add(new JLabel("（可用逗號一次輸入多個）"));
        add(top, BorderLayout.NORTH);

        list.setVisibleRowCount(14);
        UiUtil.plainText(list);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton edit = new JButton("修改");
        JButton del = new JButton("刪除");
        edit.addActionListener(e -> edit());
        del.addActionListener(e -> delete());
        JButton copyAll = new JButton("複製全部");
        JButton paste = new JButton("貼上匯入…");
        copyAll.addActionListener(e -> copyAll());
        paste.addActionListener(e -> pasteImport());
        btns.add(edit);
        btns.add(del);
        btns.add(Box.createHorizontalStrut(16));
        btns.add(copyAll);
        btns.add(paste);
        JPanel south = new JPanel(new BorderLayout());
        south.add(btns, BorderLayout.NORTH);
        JLabel rule = new JLabel("比對規則：不分大小寫；英數關鍵字以完整單字比對（Java 不會誤中 JavaScript）");
        rule.setForeground(new Color(0x616161));
        rule.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 0));
        south.add(rule, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        try {
            store.loadKeywords().forEach(model::addElement);
        } catch (Exception e) {
            UiUtil.error(this, "讀取關鍵字失敗", e);
        }
    }

    private void add() {
        List<String> added = new ArrayList<>();
        for (String k : splitKeywords(input.getText())) {
            if (!contains(k)) { model.addElement(k); added.add(k); }
        }
        input.setText("");
        if (!added.isEmpty()) { persist(); AppLog.info("[" + store.getName() + "] 新增關鍵字：" + String.join(", ", added)); }
    }

    private void edit() {
        int i = list.getSelectedIndex();
        if (i < 0) { JOptionPane.showMessageDialog(this, "請先選擇一個關鍵字"); return; }
        String old = model.get(i);
        String nk = (String) JOptionPane.showInputDialog(this, "修改關鍵字：", "修改", JOptionPane.PLAIN_MESSAGE, null, null, old);
        if (nk == null || nk.trim().isEmpty() || nk.trim().equals(old)) return;
        if (contains(nk.trim())) { JOptionPane.showMessageDialog(this, "關鍵字已存在"); return; }
        model.set(i, nk.trim());
        persist();
        AppLog.info("[" + store.getName() + "] 修改關鍵字：" + old + " → " + nk.trim());
    }

    private void delete() {
        int[] idx = list.getSelectedIndices();
        if (idx.length == 0) { JOptionPane.showMessageDialog(this, "請先選擇要刪除的關鍵字"); return; }
        List<String> removed = new ArrayList<>(list.getSelectedValuesList());
        for (int i = idx.length - 1; i >= 0; i--) model.remove(idx[i]);
        persist();
        AppLog.info("[" + store.getName() + "] 刪除關鍵字：" + String.join(", ", removed));
    }

    /** 一行一個，複製到剪貼簿（可貼到 Excel、記事本，或另一個頻道的「貼上匯入」）。 */
    private void copyAll() {
        if (model.isEmpty()) { JOptionPane.showMessageDialog(this, "目前沒有關鍵字"); return; }
        String text = String.join("\n", Collections.list(model.elements()));
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            JOptionPane.showMessageDialog(this, "已複製 " + model.size() + " 個關鍵字到剪貼簿（一行一個）");
        } catch (Exception e) {
            UiUtil.error(this, "無法存取剪貼簿", e);
        }
    }

    static List<String> splitKeywords(String text) {
        return com.secnews.KeywordMatcher.parseList(text);
    }

    /** 貼上多行清單一次匯入；已存在的（不分大小寫）自動略過。 */
    private void pasteImport() {
        String clip = "";
        try {
            Object o = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (o instanceof String str) clip = str;
        } catch (Exception ignored) {
            // 剪貼簿沒有文字：讓使用者自己貼上
        }
        JTextArea area = new JTextArea(clip, 14, 36);
        area.setLineWrap(false);
        JPanel p = new JPanel(new BorderLayout(4, 6));
        p.add(new JLabel("<html>貼上關鍵字清單（一行一個，或用逗號分隔）。<br>已存在的關鍵字會自動略過（不分大小寫）。</html>"),
                BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, p, "貼上匯入關鍵字", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        List<String> added = new ArrayList<>();
        int skipped = 0;
        for (String k : splitKeywords(area.getText())) {
            if (contains(k)) skipped++;
            else { model.addElement(k); added.add(k); }
        }
        if (!added.isEmpty()) {
            persist();
            AppLog.info("[" + store.getName() + "] 匯入關鍵字 " + added.size() + " 個：" + String.join(", ", added));
        }
        JOptionPane.showMessageDialog(this, "新增 " + added.size() + " 個關鍵字" + (skipped > 0 ? "，略過重複 " + skipped + " 個" : ""));
    }

    private boolean contains(String k) {
        for (int i = 0; i < model.size(); i++) if (model.get(i).equalsIgnoreCase(k)) return true;
        return false;
    }

    private void persist() {
        try {
            store.saveKeywords(Collections.list(model.elements()));
        } catch (Exception e) {
            UiUtil.error(this, "儲存關鍵字失敗", e);
        }
    }
}
