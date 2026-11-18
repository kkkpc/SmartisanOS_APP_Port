package android.content;
public class Context {
    // 仅满足 CalendarAssistantData 的编译依赖；运行时从不实例化
    public Object getSystemService(String name) { throw new RuntimeException("stub"); }
}
