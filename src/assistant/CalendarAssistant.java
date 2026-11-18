package assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Standalone read-only assistant. The host declares INTERNET and READ_CALENDAR. */
public final class CalendarAssistant {
    private static final int READ_CALENDAR_REQUEST = 0x4341;
    private final Activity activity;
    private final AssistantProfiles store;
    private AlertDialog dialog;
    private TextView profileLabel;
    private TextView result;
    private Button local;
    private Button cloud;
    private Button manage;
    private boolean busy;
    private boolean storeReady;

    private CalendarAssistant(Activity activity) {
        this.activity = activity;
        this.store = new AssistantProfiles(activity);
    }

    public static void show(Activity activity) {
        if (activity == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> show(activity));
            return;
        }
        if (!activity.isFinishing() && !activity.isDestroyed()) new CalendarAssistant(activity).open();
    }

    private void open() {
        LinearLayout view = column();
        profileLabel = text(view, "正在读取加密配置…");
        manage = button(view, "管理服务配置", this::manage);
        local = button(view, "本机汇总与时间建议", () -> loadDay(false));
        cloud = button(view, "预览并选择是否发送", () -> loadDay(true));
        result = text(view, "仅查看，不修改日历。本机汇总无需联网；云端只发送经您确认的匿名时间段。");
        dialog = new AlertDialog.Builder(activity).setTitle("日程助手").setView(scroll(view))
                .setNegativeButton("关闭", null).create();
        secureShow(dialog);
        updateControls();
        new Thread(() -> {
            boolean loaded;
            try { store.load(); loaded = true; }
            catch (Exception failure) { loaded = false; }
            final boolean ok = loaded;
            post(() -> {
                storeReady = true;
                updateControls();
                if (!ok) profileLabel.setText("无法解密旧配置；保存新配置将替换旧配置。");
            });
        }, "calendar-assistant-store").start();
    }

    private void updateControls() {
        AssistantProfiles.Profile profile = storeReady ? store.current() : null;
        profileLabel.setText(!storeReady ? "正在读取加密配置…" : profile == null ? "未配置云服务"
                : "当前：" + profile.name + " · " + profile.model);
        local.setEnabled(!busy);
        manage.setEnabled(!busy && storeReady);
        cloud.setEnabled(!busy && profile != null);
    }

    private void loadDay(boolean remote) {
        if (busy) return;
        if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            result.setText("读取今日安排需要日历读取权限。授权后请再次点击；若曾永久拒绝，请在系统设置中授权。");
            activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, READ_CALENDAR_REQUEST);
            return;
        }
        AssistantProfiles.Profile profile = remote ? store.current() : null;
        if (remote && profile == null) return;
        busy = true;
        updateControls();
        result.setText("正在读取今日安排…");
        new Thread(() -> {
            try {
                CalendarAssistantData.Snapshot data = CalendarAssistantData.load(activity.getApplicationContext());
                post(() -> {
                    busy = false;
                    updateControls();
                    if (!data.isToday()) { result.setText("日期或时区已变化，请重新读取。"); return; }
                    result.setText(data.summary);
                    if (remote && data.count > 0) preview(profile, data);
                });
            } catch (Exception failure) {
                post(() -> finish("无法读取日历。请检查权限和系统日历服务。"));
            }
        }, "calendar-assistant-read").start();
    }

    private void preview(AssistantProfiles.Profile profile, CalendarAssistantData.Snapshot data) {
        LinearLayout content = column();
        text(content, "接收方：" + profile.endpoint + "\n模型：" + profile.model
                + "\n点击同意后，下面的日程数据将发送给该服务商。"
                + "\n附带固定的汇总与调整建议指令，不发送标题、地点、备注、参与者或日历名称。\n");
        text(content, data.preview);
        AlertDialog consent = new AlertDialog.Builder(activity).setTitle("确认本次发送")
                .setView(scroll(content)).setNegativeButton("不发送", null)
                .setPositiveButton("同意并发送", (d, w) -> send(profile, data)).create();
        secureShow(consent);
    }

    private void send(AssistantProfiles.Profile profile, CalendarAssistantData.Snapshot data) {
        if (!visible() || busy) return;
        if (!data.isToday()) { result.setText("日期或时区已变化，请重新读取并预览。"); return; }
        if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            result.setText("日历读取权限已撤回，未发送。");
            return;
        }
        busy = true;
        updateControls();
        result.setText(data.summary + "\n\n等待服务商回复…");
        AssistantLog.stage("send");
        // 发送使用预览时确认的 profile 实例，之后配置变化不影响本轮
        new Thread(() -> {
            String response;
            try {
                response = "云端建议（请核对，仅供参考）\n"
                        + AssistantTransport.suggest(AssistantTransport.openAi(profile), data);
                AssistantLog.stage("send", "ok");
            } catch (AssistantTransport.HttpStatusException failure) {
                AssistantLog.stage("send", "http");
                response = "服务商请求失败（HTTP " + failure.status + "）。请检查配置或稍后重试。";
            } catch (java.net.SocketTimeoutException failure) {
                AssistantLog.stage("send", "timeout");
                response = "服务商超时未回复。请稍后重试。";
            } catch (Exception failure) {
                AssistantLog.failure("send", failure);
                response = "无法获取云端建议。请检查网络和配置，或重新预览今日安排后重试。";
            }
            final String completed = data.summary + "\n\n" + response;
            post(() -> finish(completed));
        }, "calendar-assistant-request").start();
    }

    private void finish(String output) {
        busy = false;
        updateControls();
        result.setText(output);
    }

    private void manage() {
        List<String> labels = new ArrayList<>();
        for (AssistantProfiles.Profile profile : store.profiles) labels.add(profile.name + " · " + profile.model);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("切换服务配置")
                .setNegativeButton("关闭", null);
        if (labels.size() < AssistantProfiles.MAX_PROFILES) {
            builder.setPositiveButton("新建", (d, w) -> editProfile(-1));
        }
        if (labels.isEmpty()) builder.setMessage("请新建云服务配置；本机汇总无需配置。");
        else builder.setSingleChoiceItems(labels.toArray(new String[0]), store.active, (d, which) -> {
            if (save(new ArrayList<>(store.profiles), which)) d.dismiss();
        }).setNeutralButton("编辑当前", (d, w) -> editProfile(store.active));
        secureShow(builder.create());
    }

    private void editProfile(int index) {
        AssistantProfiles.Profile old = index < 0 ? null : store.profiles.get(index);
        LinearLayout view = column();
        text(view, "服务商");
        Spinner preset = new Spinner(activity);
        ArrayAdapter<String> choices = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, AssistantProfiles.PROVIDERS);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        preset.setAdapter(choices);
        if (old != null) preset.setSelection(old.provider);
        view.addView(preset);
        EditText name = field(view, "名称（可选）", 60, false);
        EditText endpoint = field(view, "自定义完整 HTTPS Chat Completions URL", 1024, false);
        EditText model = field(view, "模型（预设可留空）", 120, false);
        EditText key = field(view, old == null ? "API Key" : "留空保留原 API Key（仅同服务地址）", 1024, true);
        if (old != null) {
            name.setText(old.name);
            if (old.provider == 2) endpoint.setText(old.endpoint);
            if (!old.model.equals(AssistantProfiles.MODELS[old.provider])) model.setText(old.model);
        }
        text(view, "API Key 仅加密保存在本机；更换接收地址时必须重新输入。"
                + " 自定义 URL 示例：https://example.com/v1/chat/completions");
        TextView error = text(view, "");
        // 连接测试：独立于保存，只发送固定无隐私文本
        Button test = new Button(activity);
        test.setText("测试连接（发送固定的无隐私文本）");
        test.setOnClickListener(v -> {
            AssistantProfiles.Profile candidate;
            try {
                candidate = draft(preset, value(name), value(endpoint), value(model), value(key), old);
            } catch (IllegalArgumentException invalid) {
                error.setText("配置未填完整，无法测试。");
                return;
            }
            final AssistantProfiles.Profile target = candidate;
            error.setText("正在测试连接…");
            AssistantLog.stage("ping");
            new Thread(() -> {
                String category = new AssistantTransport.OpenAiCompat(target).ping();
                post(() -> error.setText(category == null
                        ? "连接成功，服务可用。"
                        : "连接失败（类别：" + category + "）。请检查地址、模型与 API Key。"));
            }, "calendar-assistant-ping").start();
        });
        view.addView(test, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(old == null ? "新建配置" : "编辑配置")
                .setView(scroll(view)).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (old != null) builder.setNeutralButton("删除", (d, w) -> {
            AlertDialog confirm = new AlertDialog.Builder(activity).setTitle("删除此配置？")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (x, y) -> {
                        List<AssistantProfiles.Profile> remaining = new ArrayList<>(store.profiles);
                        remaining.remove(index);
                        save(remaining, Math.max(0, Math.min(store.active, remaining.size() - 1)));
                    }).create();
            secureShow(confirm);
        });
        AlertDialog editor = builder.create();
        secureShow(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                AssistantProfiles.Profile replacement = draft(preset, value(name), value(endpoint),
                        value(model), value(key), old);
                List<AssistantProfiles.Profile> next = new ArrayList<>(store.profiles);
                int selected = index < 0 ? next.size() : index;
                if (index < 0) next.add(replacement); else next.set(index, replacement);
                if (save(next, selected)) editor.dismiss();
            } catch (IllegalArgumentException invalid) {
                error.setText("请检查 HTTPS 完整地址、模型与 API Key；切换服务时须重新输入 Key。");
            }
        });
    }

    /** 从编辑表单构造候选配置（留空 key 且同服务地址时沿用旧 key）。 */
    private static AssistantProfiles.Profile draft(Spinner preset, String label, String endpoint,
                                                   String model, String apiKey,
                                                   AssistantProfiles.Profile old) {
        int provider = preset.getSelectedItemPosition();
        String selectedEndpoint = provider == 2 ? endpoint : AssistantProfiles.ENDPOINTS[provider];
        String key = apiKey.trim();
        if (key.isEmpty() && old != null && old.provider == provider && old.endpoint.equals(selectedEndpoint)) {
            key = old.key;
        }
        return new AssistantProfiles.Profile(
                label.isEmpty() ? AssistantProfiles.PROVIDERS[provider] : label,
                provider, selectedEndpoint,
                model.isEmpty() ? AssistantProfiles.MODELS[provider] : model, key);
    }

    private boolean save(List<AssistantProfiles.Profile> proposed, int selected) {
        List<AssistantProfiles.Profile> old = new ArrayList<>(store.profiles);
        int previous = store.active;
        store.profiles.clear();
        store.profiles.addAll(proposed);
        store.active = selected;
        try {
            store.save();
            updateControls();
            return true;
        } catch (Exception failure) {
            store.profiles.clear();
            store.profiles.addAll(old);
            store.active = previous;
            Toast.makeText(activity, "无法保存加密配置。未使用明文存储。", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean visible() {
        return dialog != null && !activity.isFinishing() && !activity.isDestroyed() && dialog.isShowing();
    }

    private void post(Runnable action) {
        activity.runOnUiThread(() -> { if (visible()) action.run(); });
    }

    private void secureShow(AlertDialog window) {
        window.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.show();
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        return column;
    }

    private ScrollView scroll(LinearLayout view) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return scroll;
    }

    private TextView text(LinearLayout view, String message) {
        TextView text = new TextView(activity);
        text.setText(message);
        view.addView(text, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return text;
    }

    private Button button(LinearLayout view, String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        view.addView(button, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return button;
    }

    private EditText field(LinearLayout view, String hint, int max, boolean secret) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setSingleLine(true);
        field.setHint(hint);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        view.addView(field, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return field;
    }

    private static String value(EditText field) { return field.getText().toString().trim(); }
}
