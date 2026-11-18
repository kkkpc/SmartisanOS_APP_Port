package assistant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;

/** 可控日期/时区下 Snapshot 时效守卫测试。 */
public class SnapshotGuardTest {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Class<?> data = Class.forName("assistant.CalendarAssistantData");
        Class<?> eventClass = Class.forName("assistant.CalendarAssistantData$Event");
        java.lang.reflect.Constructor<?> ctor = eventClass.getDeclaredConstructor(long.class, long.class, boolean.class);
        ctor.setAccessible(true);
        java.lang.reflect.Method build = data.getDeclaredMethod("build",
                LocalDate.class, ZoneId.class, long.class, java.util.List.class);
        build.setAccessible(true);
        Class<?> snapClass = Class.forName("assistant.CalendarAssistantData$Snapshot");
        java.lang.reflect.Method guard = snapClass.getDeclaredMethod("isToday", ZoneId.class, LocalDate.class);
        guard.setAccessible(true);

        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        LocalDate date = LocalDate.of(2026, 11, 18);
        long at = LocalDateTime.of(2026, 11, 18, 10, 0).atZone(shanghai).toInstant().toEpochMilli();
        Object snap = build.invoke(null, date, shanghai, at, new ArrayList<>());

        check("same day+zone valid", (Boolean) guard.invoke(snap, shanghai, date));
        check("different date invalid", !(Boolean) guard.invoke(snap, shanghai, date.plusDays(1)));
        check("different zone invalid", !(Boolean) guard.invoke(snap, ZoneId.of("UTC"), date));

        // FakeProvider 冒烟：正常与失败两态
        Class<?> fakeClass = Class.forName("assistant.AssistantTransport$FakeProvider");
        java.lang.reflect.Constructor<?> fakeCtor = fakeClass.getDeclaredConstructor(boolean.class);
        fakeCtor.setAccessible(true);
        Object okFake = fakeCtor.newInstance(false);
        Object badFake = fakeCtor.newInstance(true);
        java.lang.reflect.Method complete = fakeClass.getDeclaredMethod("complete", String.class, String.class);
        java.lang.reflect.Method ping = fakeClass.getDeclaredMethod("ping");
        complete.setAccessible(true); ping.setAccessible(true);

        String reply = (String) complete.invoke(okFake, "sys", "用户\n内容");
        check("fake reply mentions 本地模拟", reply.contains("[本地模拟]"));
        check("fake ping ok returns null", ping.invoke(okFake) == null);
        boolean threw = false;
        try { complete.invoke(badFake, "sys", "x"); }
        catch (java.lang.reflect.InvocationTargetException expected) { threw = true; }
        check("failing fake throws", threw);
        check("failing fake ping category=fake", "fake".equals(ping.invoke(badFake)));

        // OpenAiCompat 构造与 HttpStatusException 存在性（不联网）
        Class<?> profileClass = Class.forName("assistant.AssistantProfiles$Profile");
        Class<?> httpEx = Class.forName("assistant.AssistantTransport$HttpStatusException");
        check("HttpStatusException is IOException", java.io.IOException.class.isAssignableFrom(httpEx));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    static void check(String label, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }
}
