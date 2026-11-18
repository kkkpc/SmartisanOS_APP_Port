package assistant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM 可控样本测试（不依赖 Android，直接测 build() 纯计算）。
 * 覆盖：空日历、跨天、全天（UTC 标签）、重叠、空档、截断、午夜边界。
 * 用反射访问包私有类/方法。
 */
public class CalendarAssistantDataTest {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Class<?> data = Class.forName("assistant.CalendarAssistantData");
        Class<?> eventClass = Class.forName("assistant.CalendarAssistantData$Event");
        java.lang.reflect.Constructor<?> ctor = eventClass.getDeclaredConstructor(long.class, long.class, boolean.class);
        ctor.setAccessible(true);
        java.lang.reflect.Method build = data.getDeclaredMethod("build",
                LocalDate.class, ZoneId.class, long.class, List.class);
        build.setAccessible(true);
        Class<?> snapClass = Class.forName("assistant.CalendarAssistantData$Snapshot");
        java.lang.reflect.Field previewF = snapClass.getDeclaredField("preview");
        java.lang.reflect.Field summaryF = snapClass.getDeclaredField("summary");
        java.lang.reflect.Field countF = snapClass.getDeclaredField("count");
        java.lang.reflect.Field dateF = snapClass.getDeclaredField("date");
        java.lang.reflect.Field zoneF = snapClass.getDeclaredField("zone");
        previewF.setAccessible(true); summaryF.setAccessible(true);
        countF.setAccessible(true); dateF.setAccessible(true); zoneF.setAccessible(true);

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate date = LocalDate.of(2026, 11, 18);
        long midnight = LocalDateTime.of(2026, 11, 18, 0, 0).atZone(zone).toInstant().toEpochMilli();
        long at = LocalDateTime.of(2026, 11, 18, 10, 0).atZone(zone).toInstant().toEpochMilli();

        // 1) 空日历
        Object snap = build.invoke(null, date, zone, at, new ArrayList<>());
        check("empty preview mentions 无日程", String.valueOf(previewF.get(snap)).contains("无日程"));
        check("empty summary counts 0", String.valueOf(summaryF.get(snap)).contains("0 条定时日程、0 条全天日程"));
        check("empty count == 0", countF.get(snap).equals(0));
        // 空日历 09:00-18:00 全空 -> 建议 09:00 起空档
        // 空日历且 now=10:00：空档从 now(10:00) 起算到 18:00
        check("empty gap suggestion", String.valueOf(summaryF.get(snap)).contains("10:00") && String.valueOf(summaryF.get(snap)).contains("18:00"));

        // 2) 普通当日日程 10:00-11:00
        List<Object> events = new ArrayList<>();
        events.add(ctor.newInstance(at, at + 60 * 60_000L, false));
        snap = build.invoke(null, date, zone, at, events);
        check("one timed preview", String.valueOf(previewF.get(snap)).contains("11-18 10:00"));
        check("one occupied 60 min", String.valueOf(summaryF.get(snap)).contains("60 分钟"));

        // 3) 跨天：昨日 22:00 -> 今日 02:00（今日部分 00:00-02:00）
        long yesterday22 = LocalDateTime.of(2026, 11, 17, 22, 0).atZone(zone).toInstant().toEpochMilli();
        events.clear();
        events.add(ctor.newInstance(yesterday22, midnight + 2 * 3600_000L, false));
        snap = build.invoke(null, date, zone, at, events);
        check("cross-day shows 今日部分", String.valueOf(previewF.get(snap)).contains("跨天，仅列今日部分"));
        check("cross-day clip to 00:00", String.valueOf(previewF.get(snap)).contains("11-18 00:00"));

        // 4) 全天（UTC 标签）：同日期 00:00Z
        events.clear();
        long utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        events.add(ctor.newInstance(utcMidnight, utcMidnight + 3600_000L, true));
        snap = build.invoke(null, date, zone, at, events);
        check("all-day counted", String.valueOf(summaryF.get(snap)).contains("1 条全天日程"));
        check("all-day preview label", String.valueOf(previewF.get(snap)).contains("全天"));

        // 5) 重叠：10:00-11:00 与 10:30-11:30
        events.clear();
        events.add(ctor.newInstance(at, at + 60 * 60_000L, false));
        events.add(ctor.newInstance(at + 30 * 60_000L, at + 90 * 60_000L, false));
        snap = build.invoke(null, date, zone, at, events);
        check("overlap merged 90 min", String.valueOf(summaryF.get(snap)).contains("90 分钟"));
        check("overlap conflict counted", String.valueOf(summaryF.get(snap)).contains("1 条定时日程与其他日程重叠"));

        // 6) 零长度事件：仍计入条数（列表显示），但不占用时长、不产生重叠
        events.clear();
        events.add(ctor.newInstance(at, at, false));
        snap = build.invoke(null, date, zone, at, events);
        check("zero-length listed but zero occupied", String.valueOf(summaryF.get(snap)).contains("1 条定时日程"));

        // 7) 倒序输入也会排序后统计（10:00 与 14:00 输入顺序颠倒）
        long twoPM = LocalDateTime.of(2026, 11, 18, 14, 0).atZone(zone).toInstant().toEpochMilli();
        events.clear();
        events.add(ctor.newInstance(twoPM, twoPM + 3600_000L, false));
        events.add(ctor.newInstance(at, at + 3600_000L, false));
        snap = build.invoke(null, date, zone, at, events);
        check("unsorted input still works", String.valueOf(summaryF.get(snap)).contains("120 分钟"));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    static void check(String label, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }
}
