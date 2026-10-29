package com.secnews.ui;

import com.secnews.AppLog;
import com.secnews.ScheduleConfig;
import com.secnews.SchedulerService;
import com.secnews.Channel;
import com.secnews.Store;

import javax.swing.*;
import java.awt.*;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** 監控排程設定：每隔 N 分鐘（可限制時段與星期）／每日／每週指定星期幾。 */
final class SchedulePanel extends JPanel {
    private final Channel store;
    private final SchedulerService scheduler;

    private final JCheckBox enabled = new JCheckBox("啟用自動排程");
    private final JRadioButton rInterval = new JRadioButton("每隔");
    private final JRadioButton rDaily = new JRadioButton("每日");
    private final JRadioButton rWeekly = new JRadioButton("每週");
    private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(60, ScheduleConfig.MIN_INTERVAL_MINUTES, 10080, 5));
    // 每隔 N 分鐘：執行時段與星期
    private final JCheckBox windowOn = new JCheckBox("只在");
    private final JSpinner windowStart = timeSpinner();
    private final JSpinner windowEnd = timeSpinner();
    private final Map<DayOfWeek, JCheckBox> intervalDayBoxes = new EnumMap<>(DayOfWeek.class);
    private final JLabel windowHint = new JLabel(" ");
    // 每日／每週
    private final JSpinner dailyTime = timeSpinner();
    private final JSpinner weeklyTime = timeSpinner();
    private final Map<DayOfWeek, JCheckBox> dayBoxes = new EnumMap<>(DayOfWeek.class);
    // 其他
    private final JCheckBox baseline = new JCheckBox("每個網站第一次抓取時只建立基準、不推播（避免一次推送大量舊文章）");
    private final JSpinner maxAge = new JSpinner(new SpinnerNumberModel(30, 0, 3650, 1));
    private final JLabel nextLabel = new JLabel(" ");
    private final JLabel dirtyLabel = new JLabel(" ");
    private boolean loading;

    SchedulePanel(Channel store, SchedulerService scheduler) {
        super(new BorderLayout());
        this.store = store;
        this.scheduler = scheduler;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        ButtonGroup g = new ButtonGroup();
        g.add(rInterval); g.add(rDaily); g.add(rWeekly);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        body.add(row(enabled));
        body.add(Box.createVerticalStrut(6));

        // ---- 每隔 N 分鐘 ----
        body.add(row(rInterval, minutes, new JLabel("分鐘查詢一次（最少 " + ScheduleConfig.MIN_INTERVAL_MINUTES + " 分鐘）")));
        body.add(row(indent(), windowOn, windowStart, new JLabel("～"), windowEnd,
                new JLabel("之間執行（結束早於開始＝跨夜，例如 22:00～06:00）")));
        JPanel idays = row(indent(), new JLabel("執行日："));
        for (DayOfWeek d : DayOfWeek.values()) {
            JCheckBox cb = new JCheckBox(ScheduleConfig.zhDay(d));
            intervalDayBoxes.put(d, cb);
            idays.add(cb);
        }
        idays.add(quickBtn("平日", EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), intervalDayBoxes, rInterval));
        idays.add(quickBtn("每天", EnumSet.allOf(DayOfWeek.class), intervalDayBoxes, rInterval));
        body.add(idays);
        windowHint.setForeground(new Color(0x616161));
        body.add(row(indent(), windowHint));
        body.add(Box.createVerticalStrut(6));

        // ---- 每日 ----
        body.add(row(rDaily, new JLabel("時間"), dailyTime));

        // ---- 每週 ----
        JPanel weekRow = row(rWeekly);
        for (DayOfWeek d : DayOfWeek.values()) {
            JCheckBox cb = new JCheckBox(ScheduleConfig.zhDay(d));
            dayBoxes.put(d, cb);
            weekRow.add(cb);
        }
        weekRow.add(new JLabel("  時間"));
        weekRow.add(weeklyTime);
        body.add(weekRow);
        JPanel quick = row(indent(), new JLabel("快速選擇："));
        quick.add(quickBtn("平日", EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), dayBoxes, rWeekly));
        quick.add(quickBtn("週末", EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), dayBoxes, rWeekly));
        quick.add(quickBtn("每天", EnumSet.allOf(DayOfWeek.class), dayBoxes, rWeekly));
        body.add(quick);

        body.add(Box.createVerticalStrut(10));
        body.add(row(baseline));
        ((JSpinner.DefaultEditor) maxAge.getEditor()).getTextField().setColumns(4);
        if (store.isProduct()) {
            // 商品頻道：沒有發布日期；基準的意思不同
            baseline.setText("每個商品第一次搜尋時只記錄現有商品、不通知新商品（低於設定價格仍會通知）");
        } else {
            body.add(row(new JLabel("只推播發布日期在"), maxAge,
                    new JLabel("天內的文章（0 = 不限；RSS 沒有提供日期的文章照常比對）")));
        }

        add(UiUtil.titled("排程方式", body), BorderLayout.NORTH);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton save = new JButton("儲存排程");
        JButton runNow = new JButton("立即執行一次");
        JButton reset = new JButton("清除已讀紀錄");
        save.addActionListener(e -> save());
        runNow.addActionListener(e -> runNow());
        reset.addActionListener(e -> clearSeen());
        bottom.add(save);
        bottom.add(runNow);
        bottom.add(reset);
        bottom.add(Box.createHorizontalStrut(12));
        bottom.add(nextLabel);
        bottom.add(Box.createHorizontalStrut(12));
        dirtyLabel.setForeground(new Color(0xC62828));
        dirtyLabel.setFont(dirtyLabel.getFont().deriveFont(Font.BOLD));
        bottom.add(dirtyLabel);
        add(bottom, BorderLayout.SOUTH);

        // 任何變更 → 更新啟用狀態、提示文字與「尚未儲存」標示
        Runnable changed = () -> { updateEnabled(); updateDirty(); };
        for (AbstractButton b : new AbstractButton[]{enabled, rInterval, rDaily, rWeekly, windowOn, baseline}) {
            b.addActionListener(e -> changed.run());
        }
        for (JCheckBox cb : intervalDayBoxes.values()) cb.addActionListener(e -> changed.run());
        for (JCheckBox cb : dayBoxes.values()) cb.addActionListener(e -> changed.run());
        for (JSpinner s : new JSpinner[]{minutes, windowStart, windowEnd, dailyTime, weeklyTime, maxAge}) {
            s.addChangeListener(e -> changed.run());
        }

        load();
        refreshNext();
    }

    /** 更新「下次執行」顯示（排程器通知時由主視窗呼叫）。 */
    void refreshNext() { showNext(scheduler.nextRunAt(store)); }

    /**
     * 切換頻道或關閉前呼叫：有未儲存的變更時詢問是否儲存。
     * 回傳 false 表示使用者取消（不要切換）。
     */
    boolean confirmLeave() {
        if (!isDirty()) return true;
        int r = JOptionPane.showConfirmDialog(this,
                "頻道「" + store.getName() + "」的排程設定已變更但尚未儲存。\n要先儲存嗎？",
                "尚未儲存", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return false;
        return r == JOptionPane.NO_OPTION || save();
    }

    private static JComponent indent() { return (JComponent) Box.createHorizontalStrut(22); }

    private static JSpinner timeSpinner() {
        JSpinner s = new JSpinner(new SpinnerDateModel());
        s.setEditor(new JSpinner.DateEditor(s, "HH:mm"));
        return s;
    }

    private static JPanel row(JComponent... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setAlignmentX(LEFT_ALIGNMENT);
        for (JComponent c : cs) p.add(c);
        return p;
    }

    private JButton quickBtn(String label, EnumSet<DayOfWeek> set, Map<DayOfWeek, JCheckBox> boxes, JRadioButton mode) {
        JButton b = new JButton(label);
        b.addActionListener(e -> {
            mode.setSelected(true);
            boxes.forEach((d, cb) -> cb.setSelected(set.contains(d)));
            updateEnabled();
            updateDirty();
        });
        return b;
    }

    private void load() {
        loading = true;
        ScheduleConfig c = store.getSchedule();
        enabled.setSelected(c.enabled);
        rInterval.setSelected(c.mode == ScheduleConfig.Mode.INTERVAL);
        rDaily.setSelected(c.mode == ScheduleConfig.Mode.DAILY);
        rWeekly.setSelected(c.mode == ScheduleConfig.Mode.WEEKLY);
        minutes.setValue(Math.max(ScheduleConfig.MIN_INTERVAL_MINUTES, c.intervalMinutes));
        windowOn.setSelected(c.windowEnabled);
        setTime(windowStart, c.windowStart);
        setTime(windowEnd, c.windowEnd);
        intervalDayBoxes.forEach((d, cb) -> cb.setSelected(c.intervalDays.contains(d)));
        setTime(dailyTime, c.time);
        setTime(weeklyTime, c.time);
        dayBoxes.forEach((d, cb) -> cb.setSelected(c.days.contains(d)));
        baseline.setSelected(c.baselineFirstRun);
        maxAge.setValue(c.maxAgeDays);
        loading = false;
        updateEnabled();
        updateDirty();
    }

    /** 依畫面目前的內容組出設定（尚未儲存）。 */
    private ScheduleConfig fromUi() {
        ScheduleConfig c = new ScheduleConfig();
        c.enabled = enabled.isSelected();
        c.mode = rDaily.isSelected() ? ScheduleConfig.Mode.DAILY
                : rWeekly.isSelected() ? ScheduleConfig.Mode.WEEKLY : ScheduleConfig.Mode.INTERVAL;
        c.intervalMinutes = (Integer) minutes.getValue();
        c.windowEnabled = windowOn.isSelected();
        c.windowStart = getTime(windowStart);
        c.windowEnd = getTime(windowEnd);
        c.intervalDays = EnumSet.noneOf(DayOfWeek.class);
        intervalDayBoxes.forEach((d, cb) -> { if (cb.isSelected()) c.intervalDays.add(d); });
        // 每日與每週共用同一個時間欄位：以目前選取的模式為準，未選取的模式保留原設定
        ScheduleConfig saved = store.getSchedule();
        c.time = c.mode == ScheduleConfig.Mode.WEEKLY ? getTime(weeklyTime)
                : c.mode == ScheduleConfig.Mode.DAILY ? getTime(dailyTime) : saved.time;
        c.days = EnumSet.noneOf(DayOfWeek.class);
        dayBoxes.forEach((d, cb) -> { if (cb.isSelected()) c.days.add(d); });
        c.baselineFirstRun = baseline.isSelected();
        c.maxAgeDays = (Integer) maxAge.getValue();
        return c;
    }

    private boolean isDirty() {
        return !fromUi().sameAs(store.getSchedule());
    }

    private void updateDirty() {
        if (loading) return;
        dirtyLabel.setText(isDirty() ? "⚠ 設定已變更，尚未儲存" : " ");
        ScheduleConfig c = fromUi();
        if (c.mode == ScheduleConfig.Mode.INTERVAL && c.enabled) {
            String w = c.describe();
            windowHint.setText("目前設定：" + w + (c.intervalDays.isEmpty() ? "（請至少勾選一天）" : ""));
        } else {
            windowHint.setText(" ");
        }
    }

    /** 儲存成功回傳 true。 */
    private boolean save() {
        ScheduleConfig c = fromUi();
        if (c.enabled && c.mode == ScheduleConfig.Mode.WEEKLY && c.days.isEmpty()) {
            JOptionPane.showMessageDialog(this, "每週模式請至少勾選一天", "設定不完整", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (c.enabled && c.mode == ScheduleConfig.Mode.INTERVAL && c.intervalDays.isEmpty()) {
            JOptionPane.showMessageDialog(this, "「每隔」模式的執行日請至少勾選一天", "設定不完整", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            store.setSchedule(c);
            AppLog.info("[" + store.getName() + "] 排程已儲存：" + c.describe() + "；首次只建立基準：" + (c.baselineFirstRun ? "是" : "否")
                    + "；只推播 " + (c.maxAgeDays == 0 ? "不限天數" : c.maxAgeDays + " 天內") + "的文章");
            scheduler.reschedule(store);
            updateDirty();
            JOptionPane.showMessageDialog(this, "已儲存：" + c.describe());
            return true;
        } catch (Exception e) {
            UiUtil.error(this, "儲存排程失敗", e);
            return false;
        }
    }

    private void runNow() {
        if (isDirty()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "排程設定已變更但尚未儲存。\n「首次只建立基準」與「只推播 N 天內」會影響這次執行的結果。\n\n要先儲存再執行嗎？",
                    "尚未儲存", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
            if (r == JOptionPane.YES_OPTION && !save()) return;
        }
        scheduler.runNow(store);
        JOptionPane.showMessageDialog(this, "已開始執行頻道「" + store.getName() + "」，結果請看「執行紀錄」");
    }

    private void clearSeen() {
        if (JOptionPane.showConfirmDialog(this,
                "清除頻道「" + store.getName() + "」的已讀紀錄後，下次執行會把此頻道所有" + (store.isProduct() ? "商品" : "網站") + "視為第一次抓取。確定清除？", "清除已讀紀錄",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            store.clearSeen();
            AppLog.info("[" + store.getName() + "] 已清除已讀紀錄");
        } catch (Exception e) {
            UiUtil.error(this, "清除失敗", e);
        }
    }

    private void updateEnabled() {
        boolean on = enabled.isSelected();
        boolean iv = on && rInterval.isSelected();
        rInterval.setEnabled(on); rDaily.setEnabled(on); rWeekly.setEnabled(on);
        minutes.setEnabled(iv);
        windowOn.setEnabled(iv);
        windowStart.setEnabled(iv && windowOn.isSelected());
        windowEnd.setEnabled(iv && windowOn.isSelected());
        intervalDayBoxes.values().forEach(cb -> cb.setEnabled(iv));
        dailyTime.setEnabled(on && rDaily.isSelected());
        weeklyTime.setEnabled(on && rWeekly.isSelected());
        dayBoxes.values().forEach(cb -> cb.setEnabled(on && rWeekly.isSelected()));
    }

    private void showNext(ZonedDateTime t) {
        nextLabel.setText(t == null ? "下次執行：—" :
                "下次執行：" + t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    @SuppressWarnings("deprecation")
    private static void setTime(JSpinner s, LocalTime t) {
        java.util.Date d = new java.util.Date();
        d.setHours(t.getHour());
        d.setMinutes(t.getMinute());
        d.setSeconds(0);
        s.setValue(d);
    }

    @SuppressWarnings("deprecation")
    private static LocalTime getTime(JSpinner s) {
        java.util.Date d = (java.util.Date) s.getValue();
        return LocalTime.of(d.getHours(), d.getMinutes());
    }
}
