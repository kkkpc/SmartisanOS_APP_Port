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

- [ ] T0.3 OPPO 真机安装与冒烟验证
- [ ] Bug1 真机回归：切到「第二天」→ 后台 → 回前台，位置应保持
- [ ] T3 LLM Provider（in-app 菜单入口 + App 内配置界面）
