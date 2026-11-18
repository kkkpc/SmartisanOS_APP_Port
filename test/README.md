# JVM 测试（可控日期样本）

用最小 Android stub（`test/lib/`）在纯 JVM 上测 assistant 包的可测逻辑。
不测：Android 框架交互（对话框、Keystore、网络栈）——这些列为真机待验项。

```bash
cd <repo root>
JDK=/opt/homebrew/opt/openjdk/bin

# 1. 编译 stub
$JDK/javac --release 8 -nowarn -d test/lib-classes $(find test/lib -name "*.java")

# 2. 编译被测源 + 测试
$JDK/javac --release 8 -nowarn -cp Tool/lib/android.jar -d test/build/classes \
    $(find src/assistant -name "*.java") \
    test/java/assistant/CalendarAssistantDataTest.java \
    test/java/assistant/SnapshotGuardTest.java \
    test/java/assistant/AssistantProfilesValidationTest.java

# 3. 运行（3 个套件，全 PASS 为准）
$JDK/java -cp test/lib-classes:test/build/classes assistant.CalendarAssistantDataTest
$JDK/java -cp test/lib-classes:test/build/classes assistant.SnapshotGuardTest
$JDK/java -cp test/lib-classes:test/build/classes assistant.AssistantProfilesValidationTest
```

套件说明：

| 套件 | 覆盖 |
|------|------|
| CalendarAssistantDataTest | 空日历、普通/跨天/全天(UTC)/重叠/零长度/乱序、空档建议 |
| SnapshotGuardTest | isToday 守卫（日期/时区漂移）、FakeProvider 两态、异常类型 |
| AssistantProfilesValidationTest | URL 规则（https、无凭据、无 //、ASCII）、key 规则（可打印无空白）、预设端点/模型 ID |

`CalendarAssistantData.build()` 是纯计算（date/zone/now/events 全显式传参），
真机读取与这里的计算共用同一实现。
