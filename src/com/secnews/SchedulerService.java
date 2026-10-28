package com.secnews;

import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 低資源排程器：只有一條低優先權的背景執行緒（daemon）。
 * 每 30 秒醒來比對一次「牆上時鐘」是否已到下次執行時間——
 * 這樣電腦睡眠（Sleep）醒來後也能正確補跑，每次檢查只花幾微秒。
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
    private volatile ZonedDateTime nextRunAt;
    private volatile Consumer<ZonedDateTime> onNextRunChanged = t -> {};
    private boolean started;

    public SchedulerService(Store store, MonitorService monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    public void setOnNextRunChanged(Consumer<ZonedDateTime> c) { onNextRunChanged = c; }

    public ZonedDateTime nextRunAt() { return nextRunAt; }

    public synchronized void start() {
        if (started) return;
        started = true;
        reschedule();
        exec.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** 依目前設定重新計算下一次執行時間。設定變更後呼叫。 */
    public synchronized void reschedule() {
        ScheduleConfig cfg = store.getSchedule();
        nextRunAt = cfg.nextRun(ZonedDateTime.now());
        if (nextRunAt != null) {
            AppLog.info("下次執行時間：" + nextRunAt.toLocalDateTime().withNano(0).toString().replace('T', ' ')
                    + "（" + cfg.describe() + "）");
        } else {
            AppLog.info("排程未啟用或設定不完整，目前不會自動執行");
        }
        onNextRunChanged.accept(nextRunAt);
    }

    private void tick() {
        ZonedDateTime due = nextRunAt;
        if (due != null && !ZonedDateTime.now().isBefore(due)) execute("排程");
    }

    public void runNow() {
        exec.execute(() -> execute("手動"));
    }

    public boolean isRunning() { return running.get(); }

    private void execute(String trigger) {
        if (!running.compareAndSet(false, true)) {
            AppLog.info("上一次監控仍在執行，略過本次（" + trigger + "）");
            return;
        }
        try {
            monitor.runOnce(trigger);
        } catch (Throwable t) {
            AppLog.error("監控執行發生未預期錯誤", t);
        } finally {
            running.set(false);
            if ("排程".equals(trigger)) reschedule();
        }
    }

    public void shutdown() { exec.shutdownNow(); }
}
