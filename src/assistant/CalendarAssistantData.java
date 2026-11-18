package assistant;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract.Instances;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class CalendarAssistantData {
    private static final int MAX_EVENTS = 200;
    private static final long MINUTE = 60_000L;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm XXX", Locale.ROOT);

    static final class Snapshot {
        final String preview;
        final String summary;
        final LocalDate date;
        final ZoneId zone;
        final int count;

        Snapshot(String preview, String summary, LocalDate date, ZoneId zone, int count) {
            this.preview = preview;
            this.summary = summary;
            this.date = date;
            this.zone = zone;
            this.count = count;
        }

        boolean isToday() {
            ZoneId current = ZoneId.systemDefault();
            return zone.equals(current) && date.equals(LocalDate.now(current));
        }

        /** JVM 测试可控版本：对照显式 now / zone 判断样本是否“当日有效”。 */
        boolean isToday(ZoneId currentZone, LocalDate currentDate) {
            return zone.equals(currentZone) && date.equals(currentDate);
        }
    }

    private static final class Span {
        final long start;
        final long end;

        Span(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    static Snapshot load(Context context) throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        return query(context, LocalDate.now(zone), zone, System.currentTimeMillis());
    }

    /**
     * 可控日期样本入口：以显式 date / zone / now 计算（供宿主与 JVM 测试共用）。
     * Android 全天日程以 UTC 日期标签存储，而非本地时刻，故同时取 UTC 边界。
     */
    static Snapshot query(Context context, LocalDate date, ZoneId zone, long now) throws IOException {
        List<Event> events = readEvents(context, date, zone);
        return build(date, zone, now, events);
    }

    /** 从 CalendarContract.Instances 读取当日候选（query 边界 + 每行分类）。 */
    private static List<Event> readEvents(Context context, LocalDate date, ZoneId zone) throws IOException {
        List<Event> events = new ArrayList<>();
        long start = millis(date, zone);
        long end = millis(date.plusDays(1), zone);
        long utcStart = millis(date, ZoneOffset.UTC);
        long utcEnd = millis(date.plusDays(1), ZoneOffset.UTC);
        Uri.Builder uri = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, Math.min(start, utcStart));
        ContentUris.appendId(uri, Math.max(end, utcEnd));
        try (Cursor cursor = context.getContentResolver().query(uri.build(),
                new String[]{Instances.BEGIN, Instances.END, Instances.ALL_DAY},
                null, null, Instances.BEGIN + " ASC, " + Instances.END + " ASC")) {
            if (cursor == null) throw new IOException("Calendar provider unavailable");
            while (cursor.moveToNext()) {
                events.add(new Event(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2) != 0));
            }
        }
        return events;
    }

    /**
     * 纯计算：事件行 → Snapshot（JVM 可直接测试，不依赖 Android）。
     *
     * @param now  “当前时刻”样本（用于空档计算起点）
     */
    static Snapshot build(LocalDate date, ZoneId zone, long now, List<Event> events) {
        long start = millis(date, zone);
        long end = millis(date.plusDays(1), zone);
        long utcStart = millis(date, ZoneOffset.UTC);
        long utcEnd = millis(date.plusDays(1), ZoneOffset.UTC);
        StringBuilder preview = new StringBuilder("日期：").append(date)
                .append("\n时区：").append(zone.getId()).append('\n');
        int count = 0;
        int allDayCount = 0;
        boolean truncated = false;
        List<Span> timed = new ArrayList<>();
        for (Event event : events) {
            long rawStart = event.start;
            long rawEnd = event.end;
            boolean allDay = event.allDay;
            long dayStart = allDay ? utcStart : start;
            long dayEnd = allDay ? utcEnd : end;
            if (rawEnd < rawStart || rawStart >= dayEnd || rawEnd < dayStart
                    || (rawEnd == dayStart && rawEnd > rawStart)) continue;
            if (count == MAX_EVENTS) {
                truncated = true;
                break;
            }
            preview.append(++count).append(". ");
            if (allDay) {
                allDayCount++;
                preview.append("全天\n");
            } else {
                long a = Math.max(start, rawStart);
                long b = Math.min(end, rawEnd);
                timed.add(new Span(a, b));
                preview.append(stamp(a, zone)).append(" — ").append(stamp(b, zone));
                if (a != rawStart || b != rawEnd) preview.append("（跨天，仅列今日部分）");
                preview.append('\n');
            }
        }
        if (truncated) preview.append("仅列出前 ").append(MAX_EVENTS).append(" 条。\n");
        if (count == 0) preview.append("无日程。\n");
        return new Snapshot(preview.toString(), summarize(timed, allDayCount, truncated,
                date, zone, now), date, zone, count);
    }

    /** 一行日历数据的原始三元组（begin/end/allDay）。 */
    static final class Event {
        final long start;
        final long end;
        final boolean allDay;

        Event(long start, long end, boolean allDay) {
            this.start = start;
            this.end = end;
            this.allDay = allDay;
        }
    }

    private static String summarize(List<Span> timed, int allDay, boolean truncated,
                                    LocalDate date, ZoneId zone, long now) {
        StringBuilder text = new StringBuilder("今日：").append(timed.size())
                .append(" 条定时日程、").append(allDay).append(" 条全天日程。");
        if (truncated) return text.append("\n日程过多，统计不完整，不提供空档建议。").toString();
        timed.sort(Comparator.comparingLong(span -> span.start));
        long occupied = 0;
        long lastEnd = Long.MIN_VALUE;
        int conflicts = 0;
        for (Span span : timed) {
            if (span.start == span.end) continue;
            if (span.start < lastEnd) conflicts++;
            occupied += Math.max(0, span.end - Math.max(lastEnd, span.start));
            lastEnd = Math.max(lastEnd, span.end);
        }
        text.append("\n定时占用（重叠合并）：").append(occupied / MINUTE).append(" 分钟。");
        if (conflicts > 0) text.append(" 有 ").append(conflicts).append(" 条定时日程与其他日程重叠。");

        long from = Math.max(now, date.atTime(9, 0).atZone(zone)
                .toInstant().toEpochMilli());
        long until = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli();
        long gapStart = from;
        long gapEnd = from;
        long next = from;
        for (Span span : timed) {
            if (span.start == span.end || span.end <= next) continue;
            long boundary = Math.min(span.start, until);
            if (boundary - next > gapEnd - gapStart) {
                gapStart = next;
                gapEnd = boundary;
            }
            next = Math.max(next, span.end);
            if (next >= until) break;
        }
        if (until - next > gapEnd - gapStart) {
            gapStart = next;
            gapEnd = until;
        }
        if (gapEnd - gapStart >= 30 * MINUTE) {
            text.append("\n若需要调整，可先核对 ").append(stamp(gapStart, zone)).append(" — ")
                    .append(stamp(gapEnd, zone)).append(" 这一空档（仅按定时日程估算，未扣除全天事项）。");
        } else {
            text.append("\n在今日剩余的 09:00–18:00 内未找到至少 30 分钟的空档。");
        }
        return text.toString();
    }

    private static long millis(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static String stamp(long millis, ZoneId zone) {
        return STAMP.format(Instant.ofEpochMilli(millis).atZone(zone));
    }
}
