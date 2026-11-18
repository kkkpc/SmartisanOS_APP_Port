package assistant;

import android.util.Log;

/**
 * 助手阶段日志：只记录阶段与错误类别。
 * 明确不记录：API key、日程内容、请求/响应正文、endpoint、模型名。
 */
final class AssistantLog {
    private static final String TAG = "CalAssistant";

    private AssistantLog() {
    }

    static void stage(String stage) {
        Log.i(TAG, "stage=" + stage);
    }

    static void stage(String stage, String category) {
        // category 是错误类别枚举（如 net/http/parse/config），不是任何用户数据
        Log.i(TAG, "stage=" + stage + " err=" + category);
    }

    static void failure(String stage, Throwable t) {
        String type = t == null ? "unknown" : t.getClass().getSimpleName();
        Log.w(TAG, "stage=" + stage + " err=" + type);
    }
}
