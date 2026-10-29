package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.FeedItem;
import com.secnews.MonitorService;
import com.secnews.Site;
import com.secnews.Channel;
import com.secnews.Store;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** 資安網站列表：查詢、新增、修改、刪除、測試。 */
final class SitesPanel extends JPanel {
    private final Channel store;
    private final MonitorService monitor;
    private final List<Site> sites = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final TableRowSorter<Model> sorter = new TableRowSorter<>(model);
    private final JTextField search = new JTextField(20);

    SitesPanel(Channel store, MonitorService monitor) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.monitor = monitor;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.add(new JLabel("查詢："));
        top.add(search);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        add(top, BorderLayout.NORTH);

        table.setRowSorter(sorter);
        table.setRowHeight(table.getRowHeight() + 6);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(45);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(190);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(360);        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) edit();
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.add(button("新增", this::add));
        btns.add(button("修改", this::edit));
        btns.add(button("刪除", this::delete));
        btns.add(Box.createHorizontalStrut(16));
        btns.add(button("測試抓取", this::test));
        btns.add(Box.createHorizontalStrut(16));
        btns.add(button("匯入…", this::importFile));
        btns.add(button("匯出…", this::exportFile));
        add(btns, BorderLayout.SOUTH);

        reload();
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private void reload() {
        try {
            sites.clear();
            sites.addAll(store.loadSites());
            model.fireTableDataChanged();
        } catch (Exception e) {
            UiUtil.error(this, "讀取網站列表失敗", e);
        }
    }

    private void persist() {
        try {
            store.saveSites(sites);
        } catch (Exception e) {
            UiUtil.error(this, "儲存網站列表失敗", e);
        }
    }

    private void filter() {
        String q = search.getText().trim();
        sorter.setRowFilter(q.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q), 2, 4));
    }

    private Site selected() {
        int v = table.getSelectedRow();
        if (v < 0) {
            JOptionPane.showMessageDialog(this, "請先選擇一個網站");
            return null;
        }
        return sites.get(table.convertRowIndexToModel(v));
    }

    private void add() {
        Site s = showEditor(new Site("", "https://", Site.Type.RSS, true), "新增網站");
        if (s == null) return;
        sites.add(s);
        persist();
        model.fireTableDataChanged();
        AppLog.info("[" + store.getName() + "] 新增網站：" + s.name);
    }

    private void edit() {
        Site s = selected();
        if (s == null) return;
        Site edited = showEditor(s.copy(), "修改網站");
        if (edited == null) return;
        s.name = edited.name; s.url = edited.url; s.type = edited.type; s.enabled = edited.enabled;
        s.fullText = edited.fullText;
        persist();
        model.fireTableDataChanged();
        AppLog.info("[" + store.getName() + "] 修改網站：" + s.name);
    }

    private void delete() {
        Site s = selected();
        if (s == null) return;
        if (JOptionPane.showConfirmDialog(this, "確定刪除「" + s.name + "」？", "刪除網站",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        sites.remove(s);
        persist();
        model.fireTableDataChanged();
        AppLog.info("[" + store.getName() + "] 刪除網站：" + s.name);
    }

    private void test() {
        Site s = selected();
        if (s == null) return;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<List<FeedItem>, Void>() {
            @Override protected List<FeedItem> doInBackground() throws Exception { return monitor.testSite(s); }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    List<FeedItem> items = get();
                    StringBuilder sb = new StringBuilder("抓到 " + items.size() + " 則，前 5 則：\n\n");
                    for (int i = 0; i < Math.min(5, items.size()); i++) sb.append("• ").append(items.get(i).title).append('\n');
                    JOptionPane.showMessageDialog(SitesPanel.this, sb.toString(), "測試：" + s.name, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    UiUtil.error(SitesPanel.this, "抓取失敗：" + s.name, c);
                }
            }
        }.execute();
    }

    /** 從 TSV 檔匯入網站（例如 docs/ai-sites.tsv，或其他頻道匯出的檔案）；名稱或網址重複的略過。 */
    private void importFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("匯入網站列表（.tsv）");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<String> lines = java.nio.file.Files.readAllLines(fc.getSelectedFile().toPath(), java.nio.charset.StandardCharsets.UTF_8);
            List<String> added = new ArrayList<>();
            int skipped = 0, bad = 0;
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) continue;
                Site n = Site.fromLine(line.startsWith("\uFEFF") ? line.substring(1) : line);
                if (n == null) { bad++; continue; }
                boolean dup = sites.stream().anyMatch(x -> x.name.equals(n.name) || x.url.equalsIgnoreCase(n.url));
                if (dup) { skipped++; continue; }
                sites.add(n);
                added.add(n.name);
            }
            if (!added.isEmpty()) {
                persist();
                model.fireTableDataChanged();
                AppLog.info("[" + store.getName() + "] 匯入網站 " + added.size() + " 個：" + String.join(", ", added));
            }
            JOptionPane.showMessageDialog(this, "新增 " + added.size() + " 個網站"
                    + (skipped > 0 ? "，略過重複 " + skipped + " 個" : "")
                    + (bad > 0 ? "，格式錯誤 " + bad + " 行" : ""));
        } catch (Exception e) {
            UiUtil.error(this, "匯入失敗", e);
        }
    }

    private void exportFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("匯出網站列表");
        fc.setSelectedFile(new java.io.File(store.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + "-sites.tsv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        StringBuilder sb = new StringBuilder("# name\turl\ttype(RSS|HTML)\tenabled\tfullText\n");
        for (Site s : sites) sb.append(s.toLine()).append('\n');
        try {
            java.nio.file.Files.writeString(fc.getSelectedFile().toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "已匯出 " + sites.size() + " 個網站");
        } catch (Exception e) {
            UiUtil.error(this, "匯出失敗", e);
        }
    }

    private Site showEditor(Site s, String title) {
        JTextField name = new JTextField(s.name, 30);
        JTextField url = new JTextField(s.url, 30);
        JComboBox<Site.Type> type = new JComboBox<>(Site.Type.values());
        type.setSelectedItem(s.type);
        JCheckBox enabled = new JCheckBox("啟用監控", s.enabled);
        JCheckBox fullText = new JCheckBox("全文比對（標題沒命中時，抓文章內文再比對）", s.fullText);

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        Object[][] rows = {{"名稱：", name}, {"網址：", url}, {"類型：", type}, {"", enabled}, {"", fullText}};
        for (int i = 0; i < rows.length; i++) {
            c.gridx = 0; c.gridy = i; c.fill = GridBagConstraints.NONE;
            p.add(new JLabel((String) rows[i][0]), c);
            c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            p.add((Component) rows[i][1], c);
        }
        c.gridx = 1; c.gridy = rows.length;
        JLabel hint = new JLabel("<html><small>RSS：填 RSS/Atom 網址（建議）。<br>HTML：沒有 RSS 時填新聞列表頁，"
                + "程式會擷取頁面上的文章連結。<br>全文比對：每次執行每個網站最多抓 30 篇新文章內文，每篇間隔 1 秒。"
                + "<br>請遵守網站的使用條款與 robots.txt。</small></html>");
        p.add(hint, c);

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            String n = name.getText().trim(), u = url.getText().trim();
            String err = null;
            if (n.isEmpty()) err = "名稱不可空白";
            else if (sites.stream().anyMatch(x -> x != s && x.name.equals(n) && !x.name.equals(s.name))) err = "名稱已存在";
            else {
                try {
                    URI uri = URI.create(u);
                    if (uri.getHost() == null || !(u.startsWith("http://") || u.startsWith("https://"))) err = "網址需以 http:// 或 https:// 開頭";
                } catch (Exception ex) { err = "網址格式不正確"; }
            }
            if (err == null) return new Site(n, u, (Site.Type) type.getSelectedItem(), enabled.isSelected(), fullText.isSelected());
            JOptionPane.showMessageDialog(this, err, "輸入錯誤", JOptionPane.WARNING_MESSAGE);
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"啟用", "全文比對", "名稱", "類型", "網址"};
        public int getRowCount() { return sites.size(); }
        public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c <= 1 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c <= 1; }
        public Object getValueAt(int r, int c) {
            Site s = sites.get(r);
            return switch (c) {
                case 0 -> s.enabled; case 1 -> s.fullText; case 2 -> s.name; case 3 -> s.type.name(); default -> s.url;
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            Site s = sites.get(r);
            if (c == 0) s.enabled = (Boolean) v;
            else if (c == 1) s.fullText = (Boolean) v;
            else return;
            persist();
            fireTableCellUpdated(r, c);
            AppLog.info("[" + store.getName() + "] " + s.name + "：" + (c == 0 ? "監控" : "全文比對") + (Boolean.TRUE.equals(v) ? "已啟用" : "已停用"));
        }
    }
}
