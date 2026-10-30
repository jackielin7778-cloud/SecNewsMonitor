package com.secnews;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 待重送佇列（v1.8.0）：某個推播管道失敗時，把那則通知存起來，下次執行時只對失敗的管道（LINE 則只對失敗的收件人）重送。
 * 其他成功的管道不會重複收到。每個管道最多保留 MAX_PER_NOTIFIER 則，超過 MAX_AGE_DAYS 天的捨棄並記錄。
 * 檔案：channels/頻道ID/outbox.tsv（內容為通知文字，不含任何機密）。
 */
final class Outbox {
    static final int MAX_PER_NOTIFIER = 30;
    static final int MAX_AGE_DAYS = 3;

    static final String CHAT = "chat", LINE = "line", MAIL = "mail";

    static final class Entry {
        final String id, notifier;
        final long created;
        /** LINE：只重送給這些收件人；空 = 依目前設定。 */
        final List<String> to;
        final String subject, text;
        Entry(String id, String notifier, long created, List<String> to, String subject, String text) {
            this.id = id; this.notifier = notifier; this.created = created; this.to = to; this.subject = subject; this.text = text;
        }
        String toLine() {
            Base64.Encoder e = Base64.getEncoder();
            return id + "\t" + notifier + "\t" + created + "\t" + String.join(",", to) + "\t"
                    + e.encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "\t"
                    + e.encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }
        static Entry fromLine(String line) {
            String[] p = line.split("\t", -1);
            if (p.length < 6) return null;
            try {
                Base64.Decoder d = Base64.getDecoder();
                return new Entry(p[0], p[1], Long.parseLong(p[2]), Channel.splitList(p[3]),
                        new String(d.decode(p[4]), StandardCharsets.UTF_8), new String(d.decode(p[5]), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private Outbox() {}

    static Path file(Channel ch) { return ch.dir().resolve("outbox.tsv"); }

    static List<Entry> load(Channel ch) throws IOException {
        synchronized (ch) {
            List<Entry> out = new ArrayList<>();
            Path f = file(ch);
            if (!Files.exists(f)) return out;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                Entry e = Entry.fromLine(line);
                if (e != null) out.add(e);
            }
            return out;
        }
    }

    static void save(Channel ch, List<Entry> entries) throws IOException {
        synchronized (ch) {
            if (entries.isEmpty()) { Files.deleteIfExists(file(ch)); return; }
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) sb.append(e.toLine()).append('\n');
            Store.writeAtomic(file(ch), sb.toString());
        }
    }

    /** 加入一則；同一管道超過上限時捨棄最舊的，回傳被捨棄的數量。 */
    static int add(Channel ch, Entry e) throws IOException {
        synchronized (ch) {
            List<Entry> all = load(ch);
            all.add(e);
            int dropped = 0;
            while (all.stream().filter(x -> x.notifier.equals(e.notifier)).count() > MAX_PER_NOTIFIER) {
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).notifier.equals(e.notifier)) { all.remove(i); dropped++; break; }
                }
            }
            save(ch, all);
            return dropped;
        }
    }

    static String label(String notifier) {
        return switch (notifier) { case LINE -> "LINE"; case MAIL -> "Gmail"; default -> "Google Chat"; };
    }
}
