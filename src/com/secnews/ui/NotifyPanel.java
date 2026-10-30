package com.secnews.ui;

import com.secnews.Channel;
import com.secnews.MonitorService;

import javax.swing.*;
import java.awt.*;

/** 推播設定（v1.8）：Google Chat、LINE、Gmail，可同時啟用多個。 */
final class NotifyPanel extends JPanel {
    NotifyPanel(Channel ch, MonitorService monitor, Runnable onSaved) {
        super(new BorderLayout());
        JTabbedPane t = new JTabbedPane();
        ChatPanel chat = new ChatPanel(ch, monitor);
        chat.onSaved = onSaved;
        t.addTab("Google Chat", chat);
        t.addTab("LINE", new LinePanel(ch, monitor, onSaved));
        t.addTab("Gmail", new EmailPanel(ch, monitor, onSaved));
        add(t, BorderLayout.CENTER);
    }
}
