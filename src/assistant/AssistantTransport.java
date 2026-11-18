package assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Provider 层：可替换的对话接口。
 *
 * - Provider          : 接口，complete(messages) / ping()（连接测试）
 * - OpenAiCompat      : OpenAI 兼容 HTTPS 实现（DashScope 兼容模式 / 智谱 / 自定义）
 * - FakeProvider      : 无真 key 时的本地假实现，仅用于界面与流程验证
 *
 * 安全约束：
 * - 只接受 https；不跟随重定向（避免凭据被转发）
 * - 连接 10s / 读取 20s 超时；响应体上限 128KB
 * - 日志只走 AssistantLog（阶段 + 错误类别），无 key、无正文
 */
final class AssistantTransport {

    private AssistantTransport() {
    }

    /** 可替换的 Provider 接口。 */
    interface Provider {
        /** 发送一轮对话，返回模型文本（已做长度与 key 泄漏防护）。 */
        String complete(String systemPrompt, String userText) throws Exception;

        /** 连接测试：只发送固定的无隐私文本。返回 null 表示成功，否则为错误类别。 */
        String ping();
    }

    static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status) { this.status = status; }
    }

    /** 固定的连接测试文本（无任何隐私）。 */
    static final String PING_TEXT = "连接测试：请回复“收到”。";

    private static final int MAX_RESPONSE = 128 * 1024;

    // ------------------------------------------------------------------
    // OpenAI 兼容实现
    // ------------------------------------------------------------------

    static final class OpenAiCompat implements Provider {
        private final AssistantProfiles.Profile profile;

        OpenAiCompat(AssistantProfiles.Profile profile) { this.profile = profile; }

        @Override
        public String complete(String systemPrompt, String userText) throws Exception {
            return post(buildBody(profile.model, systemPrompt, userText, 512));
        }

        @Override
        public String ping() {
            try {
                String reply = post(buildBody(profile.model, "只输出“ok”两个字。", PING_TEXT, 16));
                return (reply != null && !reply.trim().isEmpty()) ? null : "empty";
            } catch (SocketTimeoutException e) {
                AssistantLog.failure("ping", e);
                return "timeout";
            } catch (HttpStatusException e) {
                AssistantLog.stage("ping", "http");
                return "http";
            } catch (IOException e) {
                AssistantLog.failure("ping", e);
                return "net";
            } catch (Exception e) {
                AssistantLog.failure("ping", e);
                return e.getClass().getSimpleName();
            }
        }

        private String post(byte[] body) throws Exception {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(profile.endpoint).openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setUseCaches(false);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(20_000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + profile.key);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = conn.getOutputStream()) { stream.write(body); }
                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) throw new HttpStatusException(status);
                try (InputStream stream = conn.getInputStream()) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = stream.read(chunk)) != -1) {
                        if (buffer.size() + read > MAX_RESPONSE) throw new IOException();
                        buffer.write(chunk, 0, read);
                    }
                    JSONObject root = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                    Object content = root.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").get("content");
                    if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
                        throw new IOException();
                    }
                    String redacted = ((String) content).replace(profile.key, "[已隐藏]").trim();
                    return redacted.length() > 8000 ? redacted.substring(0, 8000) + "…" : redacted;
                }
            } finally {
                conn.disconnect();
            }
        }

        private static byte[] buildBody(String model, String systemPrompt, String userText, int maxTokens)
                throws org.json.JSONException {
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(new JSONObject().put("role", "user").put("content", userText));
            return new JSONObject().put("model", model).put("messages", messages)
                    .put("max_tokens", maxTokens).put("stream", false)
                    .toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // 假 Provider（无真 key 时的流程验证）
    // ------------------------------------------------------------------

    /** 永远不联网。返回固定话术，冒充云端回复用于界面验证。 */
    static final class FakeProvider implements Provider {
        private final boolean fail;

        FakeProvider(boolean fail) { this.fail = fail; }

        @Override
        public String complete(String systemPrompt, String userText) {
            if (fail) throw new RuntimeException("fake provider failure");
            return "[本地模拟] 已收到 " + countLines(userText) + " 行匿名时间段。"
                    + "这是一条用于验证界面的假回复，不代表任何服务商的输出。";
        }

        @Override
        public String ping() { return fail ? "fake" : null; }

        private static int countLines(String text) {
            int n = 0;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
            return Math.max(1, n);
        }
    }

    // ------------------------------------------------------------------
    // 供 CalendarAssistant 调用的入口
    // ------------------------------------------------------------------

    static final String INSTRUCTIONS = "你是日程整理助手。只根据提供的匿名时间段，"
            + "用简体中文总结当天安排、指出重叠并建议可调整的时间。"
            + "勿推测事项内容或声称已经修改日程。";

    static Provider openAi(AssistantProfiles.Profile profile) { return new OpenAiCompat(profile); }

    static String suggest(Provider provider, CalendarAssistantData.Snapshot snapshot) throws Exception {
        return provider.complete(INSTRUCTIONS, snapshot.preview);
    }
}
