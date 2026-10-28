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

    static JPanel titled(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }
}
