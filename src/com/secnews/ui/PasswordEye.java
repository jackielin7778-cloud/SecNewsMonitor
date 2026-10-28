package com.secnews.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * 密碼欄旁的「眼睛」按鈕：按下顯示明碼，再按一次隱藏。
 * 圖示用程式畫出（不依賴字型或圖檔），macOS 與 Windows 顯示一致。
 */
final class PasswordEye {
    private PasswordEye() {}

    /** 把密碼欄包成「欄位 + 眼睛按鈕」的面板。 */
    static JPanel wrap(JPasswordField field) {
        char defaultEcho = field.getEchoChar() != 0 ? field.getEchoChar() : '•';
        JToggleButton eye = new JToggleButton(new EyeIcon(false));
        eye.setSelectedIcon(new EyeIcon(true));
        eye.setToolTipText("顯示密碼");
        eye.setFocusable(false);                 // 不搶走 Tab 鍵順序與輸入焦點
        eye.setBorderPainted(false);
        eye.setContentAreaFilled(false);
        eye.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        eye.setMargin(new Insets(0, 2, 0, 2));
        eye.getAccessibleContext().setAccessibleName("顯示或隱藏密碼");
        eye.addActionListener(e -> {
            boolean show = eye.isSelected();
            field.setEchoChar(show ? (char) 0 : defaultEcho);
            eye.setToolTipText(show ? "隱藏密碼" : "顯示密碼");
            field.requestFocusInWindow();
        });

        JPanel p = new JPanel(new BorderLayout(2, 0));
        p.setOpaque(false);
        p.add(field, BorderLayout.CENTER);
        p.add(eye, BorderLayout.EAST);
        return p;
    }

    /** 眼睛圖示；open=true 時為「張開的眼睛」（目前顯示明碼），false 時加一條斜線。 */
    private static final class EyeIcon implements Icon {
        private static final int W = 20, H = 16;
        private final boolean open;

        EyeIcon(boolean open) { this.open = open; }

        @Override public int getIconWidth() { return W; }
        @Override public int getIconHeight() { return H; }

        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(x, y);
            Color col = open ? new Color(0x1565C0) : new Color(0x616161);
            g.setColor(col);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Path2D eye = new Path2D.Float();
            eye.moveTo(1.5, H / 2.0);
            eye.quadTo(W / 2.0, -1.5, W - 1.5, H / 2.0);
            eye.quadTo(W / 2.0, H + 1.5, 1.5, H / 2.0);
            eye.closePath();
            g.draw(eye);
            g.fill(new Ellipse2D.Float(W / 2f - 3f, H / 2f - 3f, 6f, 6f));

            if (!open) {
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(3, H - 2, W - 3, 2);
            }
            g.dispose();
        }
    }
}
