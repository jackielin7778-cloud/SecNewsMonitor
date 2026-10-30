package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.Channel;
import com.secnews.MonitorService;
import com.secnews.ProductWatch;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** 商品監控頻道的「商品清單」：要在 PChome 24h 搜尋的商品與通知條件。 */
final class ProductsPanel extends JPanel {
    private final Channel store;
    private final MonitorService monitor;
    private final List<ProductWatch> items = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    ProductsPanel(Channel store, MonitorService monitor) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.monitor = monitor;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        JPanel notify = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        notify.add(new JLabel("通知條件："));
        notify.add(notifyBox("新商品", "new", "搜尋結果出現之前沒看過的商品"));
        notify.add(notifyBox("低於設定價格", "below", "商品價格 ≤ 該項目的價格上限（每件商品只通知一次，價格回升後再降到上限以下會再通知）"));
        notify.add(notifyBox("降價", "drop", "比上次看到的價格便宜"));
        notify.add(notifyBox("補貨（momo、露天）", "restock", "上次缺貨、這次有貨（PChome 搜尋結果沒有庫存資訊）"));
        JPanel sitesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sitesRow.add(new JLabel("搜尋網站："));
        for (String site : new String[]{"pchome", "momo", "ruten"}) sitesRow.add(siteBox(site));
        sitesRow.add(new JLabel("（每個商品會在勾選的網站各搜尋一次，可跨網站比價）"));
        JPanel rows = new JPanel(new GridLayout(0, 1));
        rows.add(sitesRow);
        rows.add(notify);
        top.add(rows, BorderLayout.NORTH);
        JLabel hint = new JLabel("<html><div style='width:760px'><small>資料來源為各網站本身使用的搜尋資料（非官方 API，網站改版時可能失效；每次抓取前會檢查 robots.txt）。"
                + "PChome 搜尋結果沒有庫存資訊，補貨通知只適用 momo、露天。"
                + "第一次搜尋只記錄現有商品、不通知新商品，但會通知已低於設定價格的商品。</small></div></html>");
        hint.setForeground(new Color(0x616161));
        top.add(hint, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        UiUtil.plainText(table);
        table.setRowHeight(table.getRowHeight() + 6);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] w = {45, 130, 190, 120, 150, 150};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) edit(); }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.add(button("新增", this::add));
        btns.add(button("修改", this::edit));
        btns.add(button("刪除", this::delete));
        btns.add(Box.createHorizontalStrut(16));
        btns.add(button("測試搜尋", this::test));
        add(btns, BorderLayout.SOUTH);

        try {
            items.addAll(store.loadProducts());
        } catch (Exception e) {
            UiUtil.error(this, "讀取商品清單失敗", e);
        }
    }

    private JCheckBox notifyBox(String label, String kind, String tip) {
        JCheckBox cb = new JCheckBox(label, store.isNotify(kind));
        cb.setToolTipText(tip);
        cb.addActionListener(e -> {
            try {
                store.setNotify(kind, cb.isSelected());
                AppLog.info("[" + store.getName() + "] 通知條件「" + label + "」" + (cb.isSelected() ? "已啟用" : "已停用"));
            } catch (Exception ex) {
                UiUtil.error(this, "儲存失敗", ex);
            }
        });
        return cb;
    }

    private JCheckBox siteBox(String site) {
        String label = switch (site) { case "momo" -> "momo"; case "ruten" -> "露天"; default -> "PChome 24h"; };
        JCheckBox cb = new JCheckBox(label, store.getProductSites().contains(site));
        cb.addActionListener(e -> {
            List<String> l = new ArrayList<>(store.getProductSites());
            if (cb.isSelected()) { if (!l.contains(site)) l.add(site); } else l.remove(site);
            if (l.isEmpty()) {
                JOptionPane.showMessageDialog(this, "至少要勾選一個網站");
                cb.setSelected(true);
                return;
            }
            try {
                store.setProductSites(l);
                AppLog.info("[" + store.getName() + "] 搜尋網站：" + String.join("、", l));
            } catch (Exception ex) {
                UiUtil.error(this, "儲存失敗", ex);
            }
        });
        return cb;
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private void persist() {
        try {
            store.saveProducts(items);
        } catch (Exception e) {
            UiUtil.error(this, "儲存商品清單失敗", e);
        }
        model.fireTableDataChanged();
    }

    private ProductWatch selected() {
        int r = table.getSelectedRow();
        if (r < 0) { JOptionPane.showMessageDialog(this, "請先選擇一個商品"); return null; }
        return items.get(r);
    }

    private void add() {
        ProductWatch w = showEditor(new ProductWatch("", "", "", 0, true), null, "新增監控商品");
        if (w == null) return;
        items.add(w);
        persist();
        AppLog.info("[" + store.getName() + "] 新增監控商品：" + w.name);
    }

    private void edit() {
        ProductWatch w = selected();
        if (w == null) return;
        ProductWatch e = showEditor(w.copy(), w, "修改監控商品");
        if (e == null) return;
        int i = items.indexOf(w);
        items.set(i, e);
        persist();
        AppLog.info("[" + store.getName() + "] 修改監控商品：" + e.name);
    }

    private void delete() {
        ProductWatch w = selected();
        if (w == null) return;
        if (JOptionPane.showConfirmDialog(this, "確定刪除「" + w.name + "」？", "刪除",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        items.remove(w);
        persist();
        AppLog.info("[" + store.getName() + "] 刪除監控商品：" + w.name);
    }

    private void test() {
        ProductWatch w = selected();
        if (w == null) return;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception { return monitor.testProduct(store, w); }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    List<String> l = get();
                    long ok = l.stream().filter(s -> s.startsWith("✓")).count();
                    StringBuilder sb = new StringBuilder("搜尋到 " + l.size() + " 件，符合條件 " + ok + " 件（✓ 符合、✗ 排除並註明原因），前 30 件：\n\n");
                    for (int i = 0; i < Math.min(30, l.size()); i++) sb.append(l.get(i)).append('\n');
                    JTextArea area = new JTextArea(sb.toString(), 18, 70);
                    area.setEditable(false);
                    JOptionPane.showMessageDialog(ProductsPanel.this, new JScrollPane(area), "測試搜尋：" + w.name,
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    UiUtil.error(ProductsPanel.this, "搜尋失敗：" + w.name, e.getCause() != null ? e.getCause() : e);
                }
            }
        }.execute();
    }

    private ProductWatch showEditor(ProductWatch w, ProductWatch original, String title) {
        JTextField name = new JTextField(w.name, 28);
        JTextField query = new JTextField(w.query, 28);
        JTextField must = new JTextField(w.mustContain, 28);
        JTextField excl = new JTextField(w.exclude, 28);
        JSpinner min = new JSpinner(new SpinnerNumberModel(w.minPrice, 0, 100_000_000, 100));
        JSpinner max = new JSpinner(new SpinnerNumberModel(w.maxPrice, 0, 100_000_000, 100));
        JCheckBox enabled = new JCheckBox("啟用", w.enabled);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        Object[][] rows = {{"名稱：", name}, {"搜尋關鍵字：", query}, {"名稱需包含：", must},
                {"排除字：", excl}, {"最低價格（NT$）：", min}, {"價格上限（NT$）：", max}, {"", enabled}};
        for (int i = 0; i < rows.length; i++) {
            c.gridx = 0; c.gridy = i; c.fill = GridBagConstraints.NONE;
            p.add(new JLabel((String) rows[i][0]), c);
            c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            p.add((Component) rows[i][1], c);
        }
        c.gridx = 1; c.gridy = rows.length;
        p.add(new JLabel("<html><small>名稱：自己看得懂的名稱（可留空，預設＝搜尋關鍵字）。<br>"
                + "搜尋關鍵字：送到 PChome 搜尋，例如 iPhone 17 Pro 256GB。<br>"
                + "名稱需包含：商品名稱必須包含這些字（空白分隔，全部都要有）；可留空。<br>"
                + "排除字：商品名稱出現任一個就排除（空白分隔），例如：保護貼 保護殼 殼 膜 支架。<br>"
                + "最低價格：低於此價格的商品不列入，可排除便宜的配件；0 = 不設定。<br>"
                + "價格上限：價格 ≤ 此值時通知「低於設定價格」；0 = 不設定。</small></html>"), c);
        while (true) {
            if (JOptionPane.showConfirmDialog(this, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) return null;
            String q = query.getText().trim();
            String n = name.getText().trim().isEmpty() ? q : name.getText().trim();
            int mn = (Integer) min.getValue(), mx = (Integer) max.getValue();
            String err = q.isEmpty() ? "搜尋關鍵字不可空白"
                    : mn > 0 && mx > 0 && mn > mx ? "最低價格不可高於價格上限"
                    : items.stream().anyMatch(x -> x != original && x.name.equalsIgnoreCase(n)) ? "名稱已存在" : null;
            if (err == null) return new ProductWatch(n, q, must.getText(), mx, enabled.isSelected(), excl.getText(), mn);
            JOptionPane.showMessageDialog(this, err, "輸入錯誤", JOptionPane.WARNING_MESSAGE);
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"啟用", "名稱", "搜尋關鍵字", "名稱需包含", "排除字", "價格範圍（NT$）"};
        public int getRowCount() { return items.size(); }
        public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        public Object getValueAt(int r, int c) {
            ProductWatch w = items.get(r);
            return switch (c) {
                case 0 -> w.enabled; case 1 -> w.name; case 2 -> w.query; case 3 -> w.mustContain; case 4 -> w.exclude;
                default -> w.minPrice == 0 && w.maxPrice == 0 ? "—"
                        : w.maxPrice == 0 ? "≥ " + String.format("%,d", w.minPrice)
                        : w.minPrice == 0 ? "≤ " + String.format("%,d", w.maxPrice)
                        : String.format("%,d～%,d", w.minPrice, w.maxPrice);
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c != 0) return;
            items.get(r).enabled = (Boolean) v;
            persist();
        }
    }
}
