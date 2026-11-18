# 锤子日历二次开发（Smartisan Calendar Dev）

对 `Calendar_8.1.2.apk`（锤子日历，Smartisan OS 移植版）做二次开发的工作区。

## 快速开始

```bash
make env          # 检查工具链版本
make decode       # apktool 反编译 -> apktool/calendar
make build        # 编译 src/ 的 Java -> smali 合入 apktool 工程
make sign         # 回编译 + zipalign + v2/v3 签名 -> dist/Calendar_debug_signed.apk
make install      # 安装到已连接设备
make pull-log     # 拉取设备上的调试日志
```

完整重建（改完代码后）：

```bash
make decode && python3 patches/apply_patches.py && make build && make sign
```

## 目录结构

| 路径 | 说明 |
|------|------|
| `src/dbg/` | **新增 Java 源码**（编译成 smali 合入 APK） |
| `patches/apply_patches.py` | **smali 补丁脚本**（幂等，可重复执行） |
| `scripts/build.sh` | Java → dex → smali 合入管道 |
| `Tool/` | 固定版本工具链（apktool / jadx / r8 / smali / signer / android.jar） |
| `apktool/calendar/` | 反编译产物（gitignored，可重建） |
| `decompiled/calendar/` | jadx 的 Java 源码（gitignored，仅供阅读） |
| `dist/` | 构建产物（gitignored） |

## 工具链（全部锁定在仓库内，不污染全局）

| 工具 | 版本 | 位置 |
|------|------|------|
| Apktool | 3.0.1 | `Tool/apktool.jar` |
| jadx | 1.5.6 | 全局 brew（仅用于阅读，不影响构建） |
| R8/D8 | 8.3.37 | `Tool/lib/r8.jar` |
| smali/baksmali | 2.5.2 | `Tool/lib/` |
| uber-apk-signer | 1.3.0 | `Tool/uber-apk-signer.jar` |
| android.jar | API 35 | `Tool/lib/android.jar` |
| JDK | OpenJDK 27 | `/opt/homebrew/opt/openjdk` |

工具链全部随仓库提交，离线可完整重建。

环境变量：`source .envrc` 可把本项目的 Java 放进 PATH。

## 补丁机制

所有对存量 smali 的修改都通过 `patches/apply_patches.py` 完成，**不在 decode 产物上手工改**：

- 幂等：重复执行安全，已应用的补丁会跳过；
- 失败即停：锚点找不到会报错退出，不会静默产生半成品；
- 可复现：`make decode` 后重新应用即可回到相同状态。

当前补丁：

| ID | 内容 |
|----|------|
| `bug1` | 短路「离开 ≥ 1 小时跳回今天」（`AllInOneActivity.onResume` 判定块 `if-ltz` → `goto`） |
| `stubs` | 9 个日志插桩点（见下） |

## 日志系统

### `dbg.Logger`

- 双写：**logcat**（tag `CalDbg`）+ **文件** `<app filesDir>/cal_dbg.log`
- 文件轮转：单文件上限 512KB，超限改名 `.1`
- 内存环形缓冲：最近 512 条
- 零三方依赖，仅用 Android framework + JDK

### `dbg.Trace` 插桩点

| 位置 | 方法 | 记录内容 |
|------|------|----------|
| `CalendarApplication.onCreate` | `Logger.init` | 日志系统初始化 |
| `AllInOneActivity.onCreate` | `Trace.onCreate` | 入口 |
| `AllInOneActivity.onResume` | `Trace.onResume` | **当前 viewTime / viewType** |
| `AllInOneActivity.onPause` | `Trace.onPause` | 后台 |
| `AllInOneActivity.onUserLeaveHint` | `Trace.onUserLeaveHint` | **离开时刻 leaveAt** |
| `AllInOneActivity.onSnapLeave` | `Trace.onSnapLeave` | **now / leaveAt / elapsed / wouldJump** |
| `AllInOneActivity.onSaveInstanceState` | `Trace.onSaveInstanceState` | 保存的视图状态 |
| `AllInOneActivity.onNewIntent` | `Trace.onNewIntent` | 新 intent action |
| `AllInOneActivity.a(long,int,Bundle)` | `Trace.updateFragments` | **UI 渲染实际发生点** |

`onSnapLeave` 是排查「后台返回跳回当天」的核心观测点：

```
[snap] decision now=... leaveAt=... elapsed=3600001ms wouldJump=true => ORIGINAL_LOGIC_JUMPS_TO_TODAY
```

### 取日志

```bash
make pull-log           # 自动尝试 run-as / adb pull
# 或 root 设备直接：
adb pull /data/data/com.android.calendar/files/cal_dbg.log .
# 或实时看：
adb logcat -s CalDbg
```

## smali 插桩的寄存器约束（踩坑记录）

1. **invoke 参数寄存器只能是 v0..v15**（4 位寄存器号），`.locals` 虽可更大但传参不行；
2. **wide 寄存器必须成对**（偶数起、相邻），如 v0/v1 是 wide，v1 不可单独作 int 用；
3. `move-result-wide v0` 之后 **v1 已是 v0 的高位**，此时写 v1 会破坏 v0；
4. 方法描述符里 `V` 表示 void，`Z` 才是 boolean —— 写成 `JJJV` 会报 `no viable alternative`；
5. 插桩点优先**复用原代码在该位置已定义且随后会被覆盖**的寄存器，避免新增；
6. 复用前必须确认该寄存器在插桩点之后**不会再被读取**（如 `v0` 若是对象引用被覆盖会 NPE）。

## 待办

- [ ] Bug1 真机回归：切到「第二天」→ 后台 → 回前台，位置应保持（需 `--with-bug1` 显式构建）

## 包名迁移（com.android.calendar → com.smartisan.calendar）

流水线顺序（**rename 先行、patch 后行**，2026-11-18 架构裁决）：

```bash
make llm-all    # decode → migrate → patch → build → sign
```

`scripts/migrate_package.py`（decode 后、patch 前运行）：

| 步 | 内容 |
|----|------|
| A | smali 类树移动 `com/android/calendar` → `com/smartisan/calendar`（1176 文件） |
| B | 全树 `Lcom/android/calendar/` → `Lcom/smartisan/calendar/`（69006 处，0 残留断言） |
| C | const-string 定点 20 处（authority/action/偏好文件名/channel，计数校验） |
| D | res：layout 类名 42 文件、searchable/general_preferences/backup_rules |
| E | Manifest：package 属性、组件相对名、authorities、custom permission |

**红线（KEEP 断言 22 处 + manifest 5 项）**：系统 CalendarProvider 的
`content://com.android.calendar/...` URI、纯 authority 字符串、queries 里的系统
provider 项一律保留——真机日历数据仍从系统 provider 读取。

## LLM 助手（T3 + 收官，2026-11-18 真机验证通过）

入口：**标题栏右侧可见按钮**（方案B：`TitleBar.addRightImageView(0x7f0702f1)`
+ `AssistantEntry` 点击），点击唤出「日程助手」对话框。
（旧长按方案因真机不可发现已弃用，`AssistantBridge.java` 已删。）

```bash
make llm-all        # 干净 decode → 包名迁移 → 补丁(默认不含 bug1) → build → sign
# 产物: dist/Calendar_llm_test.apk（独立命名，不覆盖基线）
```

真机验证（moto S30, Android 11）：覆盖安装配置保留（Keystore 数据随包走）、
可见按钮出现、点击弹助手、sensenova provider 连通、月视图回归、0 崩溃。

### Provider 预设（2026-11-18 核对官方文档）

| 服务商 | 端点 | 模型 ID |
|--------|------|---------|
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-flash` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `glm-5.3-flash` |
| 自定义 | 用户输入完整 HTTPS chat/completions URL | 用户输入 |

- 百炼模型列表页现行 flash 为 qwen3.8-flash，但 `qwen-flash`（2025-07-28 版）文档页仍在、
  仍是有效模型 ID；兼容模式旧域名 `dashscope.aliyuncs.com` 官方注明仍可用。
- 智谱 `glm-4-flash` 文档页已 404，现行 Flash 为 `glm-5.3-flash`。
- 自定义端点强制 https、禁 userInfo/query/fragment、路径含 `/`、ASCII、无 `//`；
  API key 限 `[!-~]`（可打印 ASCII 无空白），防 HTTP 头注入。

### 安全要点

- key 经 Android Keystore AES-256-GCM 加密存 SharedPreferences；界面遮蔽；不写日志。
- 日志只记阶段与错误类别（`AssistantLog`，tag `CalAssistant`），无 key/日程/请求响应正文/endpoint。
- 连接测试只发送固定无隐私文本（`PING_TEXT`）；不跟随重定向；连接 10s / 读取 20s 超时；
  响应体上限 128KB；回复中 key 出现即替换 `[已隐藏]`。
- 发送前二次确认（预览对话框「同意并发送」）；发送使用预览时确认的 Provider 实例，
  之后配置变化不影响本轮；界面销毁（isFinishing/isDestroyed）后忽略过期回调。
- 仅读取：`CalendarContract.Instances` query（无 insert/update/delete）；摘要与建议只作文本展示。

### 代码结构

| 文件 | 职责 |
|------|------|
| `src/com/smartisan/calendar/AssistantEntry.java` | 可见按钮点击监听（OnClickListener → CalendarAssistant.show），宿主唯一接触点 |
| `src/assistant/CalendarAssistant.java` | 对话框 UI + 流程编排（本机汇总 / 预览确认 / 云端建议） |
| `src/assistant/CalendarAssistantData.java` | 今日数据（Instances 查询 + 纯计算 build，可 JVM 测试） |
| `src/assistant/AssistantProfiles.java` | 多配置存储（Keystore 加密）+ 校验 |
| `src/assistant/AssistantTransport.java` | Provider 接口 / OpenAI 兼容实现 / FakeProvider |
| `src/assistant/AssistantLog.java` | 阶段日志（只记阶段与错误类别） |

### smali 补丁（patches/apply_patches.py）

| ID | 内容 | 状态 |
|----|------|------|
| `stubs` | 9 个日志插桩点（含修复 wide 双寄存器字、onNewIntent 重复 move-result） | 默认应用 |
| `assistant_entry` | l() 末尾 7 行：iget D → addRightImageView(0x7f0702f1) → new AssistantEntry → setOnClickListener | 默认应用 |
| `bug1` | 跳回今天短路 | **默认不应用**，`--with-bug1` 显式开启 |

补丁可重复应用（幂等验证通过：干净 decode → 应用 → 再应用 → smali 零 diff）。

### JVM 测试（可控日期样本）

```bash
cd test && ../Tool/../scripts/… # 见 test/README.md
```

覆盖：空日历、普通日程、跨天裁剪、全天 UTC 标签、重叠合并与冲突计数、零长度事件、
乱序输入排序、isToday 时效守卫（日期/时区变化）、FakeProvider 正常与失败两态、
Provider URL/key 校验、预设端点与模型 ID 断言。全部 PASS。
