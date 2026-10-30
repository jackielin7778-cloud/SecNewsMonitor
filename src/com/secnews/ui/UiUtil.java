package com.secnews.ui;

import com.secnews.AppLog;

import javax.swing.*;
import java.awt.*;

final class UiUtil {
    private UiUtil() {}

    static void error(Component parent, String msg, Throwable t) {
        AppLog.error(msg, t);
        String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        JOptionPane.showMessageDialog(parent, msg + "\n" + reason, "錯誤", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * 讓表格／清單把內容一律當純文字顯示（v1.7.1）。Swing 遇到以 &lt;html&gt; 開頭的字串會當 HTML 解析，
     * 網站名稱等可能來自匯入檔案的內容，不應被解讀（例如 &lt;img src=...&gt; 會讓程式對外連線）。
     */
    static void plainText(JTable table) {
        javax.swing.table.DefaultTableCellRenderer r = new javax.swing.table.DefaultTableCellRenderer();
        r.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, r);
        table.setDefaultRenderer(String.class, r);
        table.putClientProperty("html.disable", Boolean.TRUE);
    }

    static void plainText(JList<?> list) {
        DefaultListCellRenderer r = new DefaultListCellRenderer();
        r.putClientProperty("html.disable", Boolean.TRUE);
        list.setCellRenderer(r);
    }

    static JPanel titled(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }
}
