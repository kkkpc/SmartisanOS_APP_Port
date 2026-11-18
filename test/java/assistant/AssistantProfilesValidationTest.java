package assistant;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Provider 层配置校验测试：URL 规则、key 规则、预设端点。 */
public class AssistantProfilesValidationTest {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Class<?> profileClass = Class.forName("assistant.AssistantProfiles$Profile");
        Constructor<?> ctor = profileClass.getDeclaredConstructor(
                String.class, int.class, String.class, String.class, String.class);
        ctor.setAccessible(true);

        // 合法：自定义 https 端点
        ok(ctor, "name", 2, "https://example.com/v1/chat/completions", "m1", "sk-abc123");
        // 合法：预设 0/1
        ok(ctor, "q", 0, "", "qwen-flash", "sk-xyz");
        ok(ctor, "g", 1, "", "glm-5.3-flash", "abc");

        // 非法：http 明文
        bad(ctor, "n", 2, "http://example.com/v1/chat/completions", "m", "k");
        // 非法：host 带凭据
        bad(ctor, "n", 2, "https://user:pass@example.com/v1/chat/completions", "m", "k");
        // 非法：key 带换行（HTTP 头注入）
        bad(ctor, "n", 2, "https://example.com/v1/chat/completions", "m", "abc\ndef");
        // 非法：key 带空格
        bad(ctor, "n", 2, "https://example.com/v1/chat/completions", "m", "a b");
        // 非法：空模型
        bad(ctor, "n", 2, "https://example.com/v1/chat/completions", "", "k");
        // 非法：路径 // 
        bad(ctor, "n", 2, "https://example.com//v1/chat/completions", "m", "k");

        // 预设端点核对（2026-11-18 官方文档）
        Class<?> profiles = Class.forName("assistant.AssistantProfiles");
        java.lang.reflect.Field endpointsF = profiles.getDeclaredField("ENDPOINTS");
        endpointsF.setAccessible(true);
        String[] endpoints = (String[]) endpointsF.get(null);
        check("dashscope endpoint", endpoints[0].equals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
        check("bigmodel endpoint", endpoints[1].equals("https://open.bigmodel.cn/api/paas/v4/chat/completions"));
        java.lang.reflect.Field modelsF = profiles.getDeclaredField("MODELS");
        modelsF.setAccessible(true);
        String[] models = (String[]) modelsF.get(null);
        check("qwen model id", models[0].equals("qwen-flash"));
        check("glm model id", models[1].equals("glm-5.3-flash"));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    static void ok(Constructor<?> c, Object... a) throws Exception {
        c.newInstance((String) a[0], (int) a[1], (String) a[2], (String) a[3], (String) a[4]);
        System.out.println("PASS valid profile accepted");
    }

    static void bad(Constructor<?> c, Object... a) throws Exception {
        try {
            c.newInstance((String) a[0], (int) a[1], (String) a[2], (String) a[3], (String) a[4]);
            System.out.println("FAIL invalid profile accepted: " + a[2]);
            failures++;
        } catch (java.lang.reflect.InvocationTargetException expected) {
            System.out.println("PASS invalid profile rejected");
        }
    }

    static void check(String label, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }
}
