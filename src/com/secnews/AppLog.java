package com.secnews;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 執行紀錄：一天一個檔案 logs/monitor-YYYY-MM-DD.log，並通知 UI。
 * 每天第一筆紀錄寫入時（以及程式啟動、變更設定時）自動刪除超過保留天數的舊檔。
 */
public final class AppLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FILE_NAME = Pattern.compile("monitor-(\\d{4}-\\d{2}-\\d{2})\\.log");

    private static Path dir;
    private static IntSupplier retentionDays = () -> Store.DEFAULT_LOG_RETENTION_DAYS;
    private static LocalDate currentDate;
    private static final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private AppLog() {}

    /** @param logsDir 紀錄檔資料夾；@param retention 保留天數（每次清理時讀取最新設定） */
    public static synchronized void init(Path logsDir, IntSupplier retention) {
        dir = logsDir;
        retentionDays = retention;
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        currentDate = null;   // 下一筆紀錄會觸發清理
    }

    public static void addListener(Consumer<String> l) { listeners.add(l); }

    public static Path fileFor(LocalDate d) {
        return dir.resolve("monitor-" + d + ".log");
    }

    public static void info(String msg) {
        LocalDateTime now = LocalDateTime.now();
        String line = now.format(TS) + "  " + msg;
        boolean newDay;
        synchronized (AppLog.class) {
            newDay = dir != null && !now.toLocalDate().equals(currentDate);
            if (newDay) currentDate = now.toLocalDate();
            write(now.toLocalDate(), line);
        }
        for (Consumer<String> l : listeners) l.accept(line);
        if (newDay) {
            int n = cleanup(now.toLocalDate());
            if (n > 0) info("已自動刪除 " + n + " 個超過 " + retentionDays.getAsInt() + " 天的紀錄檔");
        }
    }

    private static void write(LocalDate d, String line) {
        if (dir == null) return;
        try {
            Files.writeString(fileFor(d), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    /**
     * 刪除日期早於「today 往前 (保留天數 - 1) 天」的紀錄檔。
     * 例如保留 30 天、今天 10/27：保留 9/28～10/27，刪除 9/27 以前的檔案。
     * 只刪除檔名符合 monitor-YYYY-MM-DD.log 的檔案，不會動到其他檔案。
     * @return 刪除的檔案數
     */
    public static synchronized int cleanup(LocalDate today) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        int days = retentionDays.getAsInt();
        if (days <= 0) return 0;                 // 0 表示永久保留
        LocalDate oldestKept = today.minusDays(days - 1L);
        int deleted = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "monitor-*.log")) {
            for (Path p : ds) {
                Matcher m = FILE_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                LocalDate d;
                try { d = LocalDate.parse(m.group(1)); } catch (Exception e) { continue; }
                if (d.isBefore(oldestKept) && Files.deleteIfExists(p)) deleted++;
            }
        } catch (IOException ignored) {}
        return deleted;
    }

    public static void error(String msg, Throwable t) {
        String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        info("[錯誤] " + msg + "：" + reason);
    }
}
