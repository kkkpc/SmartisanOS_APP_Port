package assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AssistantProfiles {
    // 官方模型 ID 已于 2026-11-18 核对（见 DEVELOPMENT.md「Provider 预设」）：
    //   DashScope: qwen-flash（兼容模式端点，官方注明旧域名仍可用）
    //   智谱:     glm-5.3-flash（glm-4-flash 文档页已下线）
    //   两者均为 OpenAI-compatible chat/completions。
    static final String[] PROVIDERS = {"通义千问 qwen-flash", "智谱 GLM-5.3 Flash", "自定义 OpenAI 兼容"};
    static final String[] ENDPOINTS = {
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions", ""};
    static final String[] MODELS = {"qwen-flash", "glm-5.3-flash", ""};
    static final int MAX_PROFILES = 12;
    private static final String ALIAS = "assistant.calendar.profiles.v1";
    private static final String BLOB = "encrypted_v1";
    private static final byte[] AAD = ALIAS.getBytes(StandardCharsets.UTF_8);

    static final class Profile {
        final String name;
        final int provider;
        final String endpoint;
        final String model;
        final String key;

        Profile(String name, int provider, String endpoint, String model, String key) {
            if (provider < 0 || provider >= PROVIDERS.length) throw new IllegalArgumentException();
            this.name = name.trim();
            this.provider = provider;
            this.endpoint = provider == 2 ? endpoint.trim() : ENDPOINTS[provider];
            this.model = model.trim();
            this.key = key.trim();
            // key 只允许 ASCII 可打印且无空白：防止把换行/控制符注入 HTTP 头
            if (this.name.isEmpty() || this.name.length() > 60 || this.model.isEmpty()
                    || this.model.length() > 120 || this.endpoint.length() > 1024
                    || this.key.length() > 1024 || !this.key.matches("[!-~]+")) {
                throw new IllegalArgumentException();
            }
            try {
                URI uri = URI.create(this.endpoint);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null
                        // 必须是路径形式（chat/completions 端点），且禁 // 与非 ASCII
                        || uri.getRawPath().isEmpty() || uri.getRawPath().contains("//")
                        || uri.getRawPath().matches("[^\\x00-\\x7F]+")
                        || !this.endpoint.matches("\\p{ASCII}+")
                        || uri.getPort() > 65535 || uri.getPort() == 0) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException malformed) {   // URI.create 对畸形输入抛 IAE
                throw new IllegalArgumentException();
            }
        }
    }

    final List<Profile> profiles = new ArrayList<>();
    int active;
    private final SharedPreferences prefs;

    AssistantProfiles(Context context) {
        prefs = context.getSharedPreferences("calendar_assistant", Context.MODE_PRIVATE);
    }

    Profile current() { return profiles.isEmpty() ? null : profiles.get(active); }

    void load() throws Exception {
        String saved = prefs.getString(BLOB, null);
        if (saved == null) return;
        String[] pieces = saved.split(":", -1);
        if (pieces.length != 2) throw new IllegalStateException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(false),
                new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
        cipher.updateAAD(AAD);
        byte[] plain = cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP));
        JSONObject data;
        try {
            data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
        JSONArray entries = data.getJSONArray("profiles");
        if (entries.length() > MAX_PROFILES) throw new IllegalStateException();
        List<Profile> decoded = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.getJSONObject(i);
            decoded.add(new Profile(item.getString("name"), item.getInt("provider"),
                    item.getString("endpoint"), item.getString("model"), item.getString("key")));
        }
        active = Math.max(0, Math.min(data.getInt("active"), decoded.size() - 1));
        profiles.clear();
        profiles.addAll(decoded);
    }

    void save() throws Exception {
        if (profiles.isEmpty()) {
            if (!prefs.edit().remove(BLOB).commit()) throw new IllegalStateException();
            return;
        }
        JSONArray entries = new JSONArray();
        for (Profile profile : profiles) {
            entries.put(new JSONObject().put("name", profile.name).put("provider", profile.provider)
                    .put("endpoint", profile.endpoint).put("model", profile.model).put("key", profile.key));
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret(true));
        cipher.updateAAD(AAD);
        byte[] plain = new JSONObject().put("active", active).put("profiles", entries)
                .toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try {
            encrypted = cipher.doFinal(plain);
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
        String combined = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!prefs.edit().putString(BLOB, combined).commit()) throw new IllegalStateException();
    }

    private static synchronized SecretKey secret(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key key = store.getKey(ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        if (!create) throw new IllegalStateException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
