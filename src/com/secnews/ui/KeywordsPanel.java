package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.Store;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 監控關鍵字設定。可一次輸入多個，用逗號分隔，例如：Java, Tomcat, OpenJDK */
final class KeywordsPanel extends JPanel {
    private final Store store;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JTextField input = new JTextField(28);

    KeywordsPanel(Store store) {
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
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton edit = new JButton("修改");
        JButton del = new JButton("刪除");
        edit.addActionListener(e -> edit());
        del.addActionListener(e -> delete());
        btns.add(edit);
        btns.add(del);
        btns.add(new JLabel("    比對規則：不分大小寫；英數關鍵字以完整單字比對（Java 不會誤中 JavaScript）"));
        add(btns, BorderLayout.SOUTH);

        try {
            store.loadKeywords().forEach(model::addElement);
        } catch (Exception e) {
            UiUtil.error(this, "讀取關鍵字失敗", e);
        }
    }

    private void add() {
        List<String> added = new ArrayList<>();
        for (String raw : input.getText().split("[,，、]")) {
            String k = raw.trim();
            if (!k.isEmpty() && !contains(k)) { model.addElement(k); added.add(k); }
        }
        input.setText("");
        if (!added.isEmpty()) { persist(); AppLog.info("新增關鍵字：" + String.join(", ", added)); }
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
        AppLog.info("修改關鍵字：" + old + " → " + nk.trim());
    }

    private void delete() {
        int[] idx = list.getSelectedIndices();
        if (idx.length == 0) { JOptionPane.showMessageDialog(this, "請先選擇要刪除的關鍵字"); return; }
        List<String> removed = new ArrayList<>(list.getSelectedValuesList());
        for (int i = idx.length - 1; i >= 0; i--) model.remove(idx[i]);
        persist();
        AppLog.info("刪除關鍵字：" + String.join(", ", removed));
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
