package com.secnews;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * 排程設定：
 *   每隔 N 分鐘（可限制執行時段與星期，例如只在平日 08:00～18:00）
 *   每日指定時間
 *   每週指定星期幾與時間
 */
public final class ScheduleConfig {
    public enum Mode { INTERVAL, DAILY, WEEKLY }

    public static final int MIN_INTERVAL_MINUTES = 5;

    public boolean enabled = true;
    public Mode mode = Mode.INTERVAL;
    public int intervalMinutes = 60;
    public LocalTime time = LocalTime.of(9, 0);
    public Set<DayOfWeek> days = EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    // ---- 每隔 N 分鐘模式的執行時段（v1.3）----
    /** 是否只在 windowStart～windowEnd 之間執行；結束時間早於開始時間表示跨夜（例如 22:00～06:00）。 */
    public boolean windowEnabled = false;
    public LocalTime windowStart = LocalTime.of(8, 0);
    public LocalTime windowEnd = LocalTime.of(18, 0);
    /** 每隔 N 分鐘模式只在這些星期執行（跨夜時段以「開始那天」為準）。 */
    public Set<DayOfWeek> intervalDays = EnumSet.allOf(DayOfWeek.class);

    /** 每個網站第一次被抓取時，只記錄現有文章、不推播，避免一次洗版。 */
    public boolean baselineFirstRun = true;
    /** 只比對、推播發布日期在 N 天內的文章；0 = 不限。沒有日期的文章一律照常比對。 */
    public int maxAgeDays = 30;

    /** 計算 from 之後的下一次執行時間；未啟用或設定無效時回傳 null。 */
    public ZonedDateTime nextRun(ZonedDateTime from) {
        if (!enabled) return null;
        switch (mode) {
            case INTERVAL: {
                ZonedDateTime cand = from.plusMinutes(Math.max(MIN_INTERVAL_MINUTES, intervalMinutes));
                if (intervalDays.isEmpty()) return null;
                if (isAllowed(cand)) return cand;
                // 落在時段外：移到下一個允許時段的開始
                LocalTime start = windowEnabled ? windowStart : LocalTime.MIDNIGHT;
                for (int i = 0; i <= 8; i++) {
                    LocalDate d = cand.toLocalDate().plusDays(i);
                    if (!intervalDays.contains(d.getDayOfWeek())) continue;
                    ZonedDateTime t = d.atTime(start).atZone(from.getZone());
                    if (t.isAfter(cand)) return t;
                }
                return null;
            }
            case DAILY: {
                ZonedDateTime t = from.with(time).withSecond(0).withNano(0);
                return t.isAfter(from) ? t : t.plusDays(1);
            }
            case WEEKLY: {
                if (days.isEmpty()) return null;
                LocalDate d = from.toLocalDate();
                for (int i = 0; i <= 7; i++) {
                    LocalDate cand = d.plusDays(i);
                    if (!days.contains(cand.getDayOfWeek())) continue;
                    ZonedDateTime t = cand.atTime(time).atZone(from.getZone());
                    if (t.isAfter(from)) return t;
                }
                return null;
            }
            default:
                return null;
        }
    }

    /** 每隔 N 分鐘模式：t 是否落在允許的星期與時段內（含開始與結束時間）。 */
    public boolean isAllowed(ZonedDateTime t) {
        LocalTime tt = t.toLocalTime();
        DayOfWeek dow = t.getDayOfWeek();
        if (!windowEnabled || windowStart.equals(windowEnd)) return intervalDays.contains(dow);
        if (windowStart.isBefore(windowEnd)) {
            return intervalDays.contains(dow) && !tt.isBefore(windowStart) && !tt.isAfter(windowEnd);
        }
        // 跨夜：例如 22:00～06:00
        if (!tt.isBefore(windowStart)) return intervalDays.contains(dow);
        if (!tt.isAfter(windowEnd)) return intervalDays.contains(dow.minus(1));
        return false;
    }

    public void save(Properties p) {
        p.setProperty("schedule.enabled", String.valueOf(enabled));
        p.setProperty("schedule.mode", mode.name());
        p.setProperty("schedule.intervalMinutes", String.valueOf(intervalMinutes));
        p.setProperty("schedule.time", time.toString());
        p.setProperty("schedule.days", joinDays(days));
        p.setProperty("schedule.window.enabled", String.valueOf(windowEnabled));
        p.setProperty("schedule.window.start", windowStart.toString());
        p.setProperty("schedule.window.end", windowEnd.toString());
        p.setProperty("schedule.intervalDays", joinDays(intervalDays));
        p.setProperty("schedule.baselineFirstRun", String.valueOf(baselineFirstRun));
        p.setProperty("schedule.maxAgeDays", String.valueOf(Math.max(0, maxAgeDays)));
    }

    static ScheduleConfig load(Properties p) {
        ScheduleConfig c = new ScheduleConfig();
        c.enabled = Boolean.parseBoolean(p.getProperty("schedule.enabled", "true"));
        try { c.mode = Mode.valueOf(p.getProperty("schedule.mode", "INTERVAL")); } catch (Exception ignored) {}
        try { c.intervalMinutes = Integer.parseInt(p.getProperty("schedule.intervalMinutes", "60")); } catch (Exception ignored) {}
        try { c.time = LocalTime.parse(p.getProperty("schedule.time", "09:00")); } catch (Exception ignored) {}
        String ds = p.getProperty("schedule.days");
        if (ds != null) c.days = parseDays(ds);
        c.windowEnabled = Boolean.parseBoolean(p.getProperty("schedule.window.enabled", "false"));
        try { c.windowStart = LocalTime.parse(p.getProperty("schedule.window.start", "08:00")); } catch (Exception ignored) {}
        try { c.windowEnd = LocalTime.parse(p.getProperty("schedule.window.end", "18:00")); } catch (Exception ignored) {}
        String ids = p.getProperty("schedule.intervalDays");
        if (ids != null) c.intervalDays = parseDays(ids);
        c.baselineFirstRun = Boolean.parseBoolean(p.getProperty("schedule.baselineFirstRun", "true"));
        try { c.maxAgeDays = Math.max(0, Integer.parseInt(p.getProperty("schedule.maxAgeDays", "30"))); } catch (Exception ignored) {}
        return c;
    }

    /** 以存檔內容判斷兩份設定是否相同（用於「尚未儲存」提示）。 */
    public boolean sameAs(ScheduleConfig o) {
        Properties a = new Properties(), b = new Properties();
        save(a);
        o.save(b);
        return a.equals(b);
    }

    private static String joinDays(Set<DayOfWeek> ds) {
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (!ds.contains(d)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(d.name());
        }
        return sb.toString();
    }

    private static Set<DayOfWeek> parseDays(String s) {
        Set<DayOfWeek> r = EnumSet.noneOf(DayOfWeek.class);
        for (String x : s.split(",")) {
            try { if (!x.isBlank()) r.add(DayOfWeek.valueOf(x.trim())); } catch (Exception ignored) {}
        }
        return r;
    }

    public String describe() {
        if (!enabled) return "排程已停用";
        switch (mode) {
            case INTERVAL: {
                StringBuilder sb = new StringBuilder("每 " + Math.max(MIN_INTERVAL_MINUTES, intervalMinutes) + " 分鐘");
                if (windowEnabled && !windowStart.equals(windowEnd)) {
                    sb.append("，").append(windowStart).append("～").append(windowEnd);
                    if (windowEnd.isBefore(windowStart)) sb.append("（跨夜）");
                }
                if (intervalDays.size() < 7) sb.append("，").append(daysText(intervalDays));
                return sb.toString();
            }
            case DAILY: return "每日 " + time;
            case WEEKLY: return daysText(days) + " " + time;
            default: return "";
        }
    }

    static String daysText(Set<DayOfWeek> ds) {
        if (ds.size() == 7) return "每天";
        if (ds.equals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))) return "平日（週一～五）";
        if (ds.equals(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))) return "週末";
        StringBuilder sb = new StringBuilder("每週");
        for (DayOfWeek d : DayOfWeek.values()) if (ds.contains(d)) sb.append(zhDay(d));
        return sb.toString();
    }

    public static String zhDay(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "一"; case TUESDAY -> "二"; case WEDNESDAY -> "三";
            case THURSDAY -> "四"; case FRIDAY -> "五"; case SATURDAY -> "六"; case SUNDAY -> "日";
        };
    }
}
