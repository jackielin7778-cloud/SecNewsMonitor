package com.secnews;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 低資源排程器：只有一條低優先權的背景執行緒（daemon）。
 * 每 30 秒醒來比對一次「牆上時鐘」是否已到各頻道的下次執行時間——
 * 這樣電腦睡眠（Sleep）醒來後也能正確補跑，每次檢查只花幾微秒。
 *
 * 多個頻道各自排程，但同一時間只執行一個頻道（依序執行），避免同時大量連線。
 * 某頻道到期時若另一個頻道正在執行，會在它結束後的下一次檢查（30 秒內）接著執行。
 */
public final class SchedulerService {
    private static final long TICK_SECONDS = 30;

    private final Store store;
    private final MonitorService monitor;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "secnews-scheduler");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, ZonedDateTime> nextRun = new ConcurrentHashMap<>();
    private volatile Runnable onNextRunChanged = () -> {};
    private boolean started;

    public SchedulerService(Store store, MonitorService monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    /** 任何頻道的下次執行時間改變時呼叫（UI 用）。 */
    public void setOnNextRunChanged(Runnable r) { onNextRunChanged = r == null ? () -> {} : r; }

    /** 指定頻道的下次執行時間；未排程回傳 null。 */
    public ZonedDateTime nextRunAt(Channel c) { return c == null ? null : nextRun.get(c.id()); }

    /** 所有頻道中最早的下次執行時間。 */
    public ZonedDateTime nextRunAt() {
        ZonedDateTime min = null;
        for (ZonedDateTime t : nextRun.values()) if (min == null || t.isBefore(min)) min = t;
        return min;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        reschedule();
        exec.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** 依目前設定重新計算所有頻道的下一次執行時間。設定變更、頻道新增／刪除後呼叫。 */
    public synchronized void reschedule() {
        nextRun.keySet().retainAll(store.channels().stream().map(Channel::id).toList());
        for (Channel c : store.channels()) schedule(c, true);
        onNextRunChanged.run();
    }

    /** 只重新計算一個頻道。 */
    public synchronized void reschedule(Channel c) {
        schedule(c, true);
        onNextRunChanged.run();
    }

    private void schedule(Channel c, boolean log) {
        String tag = "[" + c.getName() + "] ";
        ScheduleConfig cfg = c.getSchedule();
        ZonedDateTime t = c.isEnabled() ? cfg.nextRun(ZonedDateTime.now()) : null;
        if (t != null) nextRun.put(c.id(), t);
        else nextRun.remove(c.id());
        if (!log) return;
        if (!c.isEnabled()) AppLog.info(tag + "頻道已停用，不會自動執行");
        else if (t != null) AppLog.info(tag + "下次執行時間：" + t.toLocalDateTime().withNano(0).toString().replace('T', ' ')
                + "（" + cfg.describe() + "）");
        else AppLog.info(tag + "排程未啟用或設定不完整，目前不會自動執行");
    }

    /** v1.7.1：整個 tick 包在 try/catch 裡；例外若傳出去，排程器會靜默停止之後所有排程。 */
    private void tick() {
        try {
            ZonedDateTime now = ZonedDateTime.now();
            for (Channel c : store.channels()) {
                ZonedDateTime due = nextRun.get(c.id());
                if (due != null && !now.isBefore(due)) execute(c, "排程");
                if (Thread.currentThread().isInterrupted()) return;
            }
        } catch (Throwable t) {
            AppLog.error("排程檢查發生未預期錯誤（排程會繼續）", t);
        }
    }

    /** 立即執行指定頻道一次（排入背景執行緒，若其他頻道正在執行，會等它結束）。 */
    public void runNow(Channel c) {
        exec.execute(() -> execute(c, "手動"));
    }

    /** 立即依序執行所有啟用中的頻道。 */
    public void runNow() {
        exec.execute(() -> {
            for (Channel c : store.channels()) if (c.isEnabled()) execute(c, "手動");
        });
    }

    public boolean isRunning() { return running.get(); }

    private void execute(Channel c, String trigger) {
        if (!store.channels().contains(c)) return;          // 已被刪除
        running.set(true);
        try {
            monitor.runChannel(c, trigger);
        } catch (Throwable t) {
            AppLog.error("[" + c.getName() + "] 監控執行發生未預期錯誤", t);
        } finally {
            running.set(false);
            if ("排程".equals(trigger)) {
                synchronized (this) { schedule(c, true); }
                onNextRunChanged.run();
            }
        }
    }

    public void shutdown() { exec.shutdownNow(); }
}
