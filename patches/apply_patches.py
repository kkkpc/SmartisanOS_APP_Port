#!/usr/bin/env python3
"""对 apktool 反编译产物应用 smali 补丁（幂等，可重复执行）。

用法: python3 patches/apply_patches.py

设计：apktool/ 目录是 decode 的确定性产物（已 gitignore），
本脚本在每次 decode 之后、encode 之前运行，把补丁重放上去。
每个补丁独立函数，先检查是否已应用（幂等），找不到锚点则报错退出。

补丁清单：
  bug1            : 短路"离开>=1小时跳回今天"（方案A，用户已确认）。
                    本轮 LLM 任务包【默认不应用】（任务书：af64b59 已混入、
                    须保留为显式可选补丁）—— 用 `--with-bug1` 显式开启。
  stubs           : AI 调试日志插桩（dbg.Logger / dbg.Trace）
                    - CalendarApplication.onCreate -> Logger.init(this)
                    - AllInOneActivity 生命周期：onCreate/onResume/onPause/
                      onUserLeaveHint/onSaveInstanceState/onNewIntent
                    - snap 判定块观测（记录 now / leaveAt / elapsed / wouldJump）
                    - UI 渲染入口观测（a(long,int,Bundle)）
  assistant_entry : LLM 助手入口（方案B 可见按钮）
                    标题栏右侧 TitleBar.addRightImageView(0x7f0702f1) + 点击
                    -> com.smartisan.calendar.AssistantEntry -> CalendarAssistant.show
                    插入点 l() 末尾（D 布局完成处），复用死值 v0/v1

寄存器安全策略（已逐方法核实原始 smali，2026-11-18）：
  smali 的 invoke（非 range）参数寄存器只能是 v0..v15（4 位寄存器号）；
  wide（J/D）参数必须提供两个相邻寄存器字（如 v0+v1）。
  所有插桩点复用原代码在该位置"已定义且随后会被覆盖"的寄存器，
  插入前已逐点确认该寄存器在插入点之后不会被原代码再次读取。
  详细说明见每个 patch 的注释。
"""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SMALI = ROOT / "apktool/calendar/smali"
AIO = SMALI / "com/smartisan/calendar/AllInOneActivity.smali"
APP = SMALI / "com/smartisan/calendar/CalendarApplication.smali"

DST = "Ldbg/Trace;"


def die(msg):
    print(f"[patches] FATAL: {msg}", file=sys.stderr)
    sys.exit(1)


def read(p):
    return p.read_text(encoding="utf-8")


def write(p, s):
    p.write_text(s, encoding="utf-8")
    print(f"[patches] wrote {p.relative_to(ROOT)}")


def ensure_unique(src, needle, label):
    if src.count(needle) != 1:
        die(f"{label}: anchor count={src.count(needle)} (want 1): {needle[:70]!r}")


def insert_after(src, method_sig, anchor, lines, label, already_token):
    """在 method_sig 方法体内 anchor 之后插入 lines。幂等由 already_token 判断。"""
    if already_token in src:
        print(f"[patches] {label}: already applied")
        return src
    mi = src.find(method_sig)
    if mi < 0:
        die(f"{label}: method not found: {method_sig}")
    ai = src.find(anchor, mi)
    if ai < 0 or ai > src.find(".end method", mi):
        die(f"{label}: anchor not found inside method")
    block = "".join("\n    " + l for l in lines)
    print(f"[patches] {label}: applied")
    return src[:ai + len(anchor)] + block + src[ai + len(anchor):]


def insert_before(src, method_sig, anchor, lines, label, already_token):
    """在 method_sig 方法体内第一次出现 anchor 之前插入 lines。"""
    if already_token in src:
        print(f"[patches] {label}: already applied")
        return src
    mi = src.find(method_sig)
    if mi < 0:
        die(f"{label}: method not found: {method_sig}")
    ai = src.find(anchor, mi)
    if ai < 0 or ai > src.find(".end method", mi):
        die(f"{label}: anchor not found inside method")
    block = "".join("\n    " + l for l in lines)
    print(f"[patches] {label}: applied")
    # 末尾补换行，避免与 anchor 首行粘连成 "...Vinvoke-super"
    return src[:ai] + block + "\n" + src[ai:]


def bump_locals(src, method_sig, old_n, new_n, label):
    """把指定方法的 .locals 从 old_n 改成 new_n（幂等）。"""
    old = f"{method_sig}\n    .locals {old_n}"
    new = f"{method_sig}\n    .locals {new_n}"
    if new in src:
        print(f"[patches] {label}: already applied")
        return src
    ensure_unique(src, old, label)
    print(f"[patches] {label}: applied")
    return src.replace(old, new)


# ---------------------------------------------------------------- bug1
def patch_bug1():
    """Bug1: 后台>=1小时返回时 UI 跳回当天、丢失用户定位日期。

    根因: AllInOneActivity.onResume 中
        if (R != 0 && now - R >= 3600000) { t = true; controller.a(now); }
    离开时长超阈值即把控制器时间拽回"今天"，随后 t 触发 fragments 重渲染。

    修复(方案A): 条件跳转 if-ltz v1, :cond_3 改为无条件 goto :cond_3，
    判定块永久短路，永不自动跳回当天。onSaveInstanceState 的
    key_restore_time 兜底不受影响。

    任务书要求：LLM 测试包默认不应用（保留为显式可选补丁）。
    """
    src = read(AIO)
    OLD = "if-ltz v1, :cond_3"
    NEW = "goto :cond_3"
    if NEW in src and OLD not in src:
        print("[patches] bug1: already applied")
        return
    ensure_unique(src, OLD, "bug1")
    ctx = src[src.index("0x36ee80"):][:400]
    if OLD not in ctx:
        die("bug1 anchor not in expected 0x36ee80 block")
    write(AIO, src.replace(OLD, NEW))
    print("[patches] bug1: applied (snap-to-today short-circuited)")


# ---------------------------------------------------------------- stubs
def patch_stubs():
    src = read(AIO)
    app = read(APP)

    # ---- 1) CalendarApplication.onCreate: Logger.init(this) ----
    # .locals 1（v0 后续用于 Handler）。插入点在 invoke-super 之后、
    # new-instance v0 之前：直接用 p0 参数，不占 v0。零寄存器开销。
    app = insert_after(
        app,
        ".method public onCreate()V",
        "invoke-super {p0}, Landroid/app/Application;->onCreate()V",
        ["invoke-static {p0}, Ldbg/Logger;->init(Landroid/content/Context;)V"],
        "stub:AppInit",
        already_token="Ldbg/Logger;->init(Landroid/content/Context;)V",
    )
    write(APP, app)

    # ---- 2) AllInOneActivity.onCreate ----
    # .locals 11。插入点在 invoke-super 之后，v0 随后立刻被
    # getWindow() 的 move-result-object 覆盖，只用 p0，零开销。
    src = insert_after(
        src,
        ".method protected onCreate(Landroid/os/Bundle;)V",
        "invoke-super {p0, p1}, Lcom/smartisan/calendar/BaseActivity;->onCreate(Landroid/os/Bundle;)V",
        [f"invoke-static {{p0}}, {DST}->onCreate(Landroid/content/Context;)V"],
        "stub:AIO.onCreate",
        already_token=f"{DST}->onCreate(",
    )

    # ---- 3) AllInOneActivity.onResume：记录 viewTime / viewType ----
    # 插入点：e->b(0, this) 调用之后（原始代码第 45 行处，此时
    # v0/v1/v2 均为死值：v0 在下一行被 new-instance v0 覆盖）。
    # 寄存器：v14=this，v0+v1 装 viewTime(wide 成对)，v2 装 viewType(int)。
    #   viewTime 来自 this.j.c() 的 move-result-wide v0（自动占 v0/v1）。
    #   viewType 来自 this.j.f() 的 move-result v2。
    #   v10 只作 e 引用的临时寄存器（随后不再被读）。
    # 修复：原版 invoke 只给了 J 一个寄存器字（v0），verify 必炸；
    #       现在按 {v14, v0, v1, v2} 传 Context,J(两个字),I。
    src = insert_after(
        src,
        ".method protected onResume()V",
        "invoke-virtual {v0, v15, v14}, Lcom/smartisan/calendar/e;->b(ILcom/smartisan/calendar/e$a;)V",
        [
            "iget-object v10, v14, Lcom/smartisan/calendar/AllInOneActivity;->j:Lcom/smartisan/calendar/e;",
            "invoke-virtual {v10}, Lcom/smartisan/calendar/e;->c()J",
            "move-result-wide v0",
            "iget-object v10, v14, Lcom/smartisan/calendar/AllInOneActivity;->j:Lcom/smartisan/calendar/e;",
            "invoke-virtual {v10}, Lcom/smartisan/calendar/e;->f()I",
            "move-result v2",
            f"invoke-static {{v14, v0, v1, v2}}, {DST}->onResume(Landroid/content/Context;JI)V",
        ],
        "stub:AIO.onResume",
        already_token=f"{DST}->onResume(",
    )

    # ---- 4) AllInOneActivity.onPause ----
    src = insert_after(
        src,
        ".method protected onPause()V",
        "invoke-super {p0}, Lcom/smartisan/calendar/BaseActivity;->onPause()V",
        [f"invoke-static {{p0}}, {DST}->onPause(Landroid/content/Context;)V"],
        "stub:AIO.onPause",
        already_token=f"{DST}->onPause(",
    )

    # ---- 5) AllInOneActivity.onUserLeaveHint：记录离开时刻 ----
    # .locals 2。插入点在 sput-wide v0 之后（v0+v1=leaveAt wide 对仍然
    # 有效）、invoke-super 之前。invoke 传 {p0, v0, v1} 三个字对应
    # (Context;J)。v1 此后不再被读。
    src = insert_after(
        src,
        ".method protected onUserLeaveHint()V",
        "sput-wide v0, Lcom/smartisan/calendar/AllInOneActivity;->R:J",
        [
            f"invoke-static {{p0, v0, v1}}, {DST}->onUserLeaveHint(Landroid/content/Context;J)V",
        ],
        "stub:AIO.onUserLeaveHint",
        already_token=f"{DST}->onUserLeaveHint(",
    )

    # ---- 6) AllInOneActivity.onSaveInstanceState：记录保存的视图状态 ----
    # .locals 3（v0,v1,v2）。插入点在方法末尾 return-void 之前。
    # v0+v1 装 viewTime(wide 成对)，v2 装 viewType(int)。
    # 修复：原版 J 只传了 v0 单字；现在按 {p0, v0, v1, v2} 传。
    # （不能用 v1 单独装 int——它是 v0 wide 对的高位。）
    src = insert_before(
        src,
        ".method public onSaveInstanceState(Landroid/os/Bundle;)V",
        "    return-void",
        [
            "iget-object v0, p0, Lcom/smartisan/calendar/AllInOneActivity;->j:Lcom/smartisan/calendar/e;",
            "invoke-virtual {v0}, Lcom/smartisan/calendar/e;->c()J",
            "move-result-wide v0",
            "iget v2, p0, Lcom/smartisan/calendar/AllInOneActivity;->r:I",
            f"invoke-static {{p0, v0, v1, v2}}, {DST}->onSaveInstanceState(Landroid/content/Context;JI)V",
        ],
        "stub:AIO.onSaveInstanceState",
        already_token=f"{DST}->onSaveInstanceState(",
    )

    # ---- 7) AllInOneActivity.onNewIntent：记录新 intent ----
    # .locals 11。插入点在 aa.a(intent) 之后（原代码在 getAction 之后
    # 立刻调用 aa.a，v0=action 此刻有效，v2/v3 为死值）。
    # 修复（原版两处 bug）：
    #   a) 插在 getAction 的 move-result-object 之后又紧跟
    #      move-result-object v0 —— 重复 move-result，verify 必炸。
    #      现在锚定 aa.a(intent) 行（唯一），action 已在 v0。
    #   b) J 参数只给了 v2 一个字；现在 const-wide/16 v2 会占 v2+v3
    #      成对，invoke 传 {p0, v0, v2, v3} 四个字。
    src = insert_after(
        src,
        ".method protected onNewIntent(Landroid/content/Intent;)V",
        "invoke-static {p1}, Lcom/smartisan/calendar/aa;->a(Landroid/content/Intent;)V",
        [
            "const-wide/16 v2, 0x0",
            f"invoke-static {{p0, v0, v2, v3}}, {DST}->onNewIntent(Landroid/content/Context;Ljava/lang/String;J)V",
        ],
        "stub:AIO.onNewIntent",
        already_token=f"{DST}->onNewIntent(",
    )

    # ---- 8) snap 判定块观测：记录 now / leaveAt / elapsed / wouldJump ----
    # 插入点在 `sget-wide v3, ...->R:J` 之后（此时 v1+v2=now(由
    # move-result-wide v1 就绪)，v3+v4=leaveAt(由 sget-wide v3 就绪)。
    # invoke 传四个字 {v1,v2,v3,v4} 对应 (JJ)。
    # 此插入必须在 bug1 之前执行（bug1 把 if-ltz 改成 goto，但
    # sget-wide 行不变，锚点稳定）。
    # 此插入必须在 bug1 之前执行（bug1 把 if-ltz 改成 goto，但
    # sget-wide 行不变，锚点稳定）。
    src = insert_after(
        src,
        ".method protected onResume()V",
        "sget-wide v3, Lcom/smartisan/calendar/AllInOneActivity;->R:J",
        [
            f"invoke-static {{v1, v2, v3, v4}}, {DST}->onSnapLeave(JJ)V",
        ],
        "stub:SnapObs",
        already_token=f"{DST}->onSnapLeave(",
    )

    # ---- 9) UI 渲染入口观测：a(long, int, Bundle) ----
    # 该方法 .locals 16。插入点：move-object/from16 v0, p4 之后。
    # v9=this，v7+v8=viewTime(wide 成对)，v10=viewType(int)，
    # v12 装 snapFlag 后在下方被 `const/4 v12, 0x6` 覆盖 —— 安全。
    # 修复：J 参数原版只传了 v7 单字；现在传 v7+v8 成对。
    src = insert_after(
        src,
        ".method private a(JILandroid/os/Bundle;)V",
        "move-object/from16 v0, p4",
        [
            "iget-boolean v12, v9, Lcom/smartisan/calendar/AllInOneActivity;->t:Z",
            f"invoke-static {{v9, v7, v8, v10, v12}}, {DST}->updateFragments(Landroid/content/Context;JIZ)V",
        ],
        "stub:UpdateFragments",
        already_token=f"{DST}->updateFragments(",
    )

    write(AIO, src)


# ---------------------------------------------------------------- assistant entry
def patch_assistant_entry():
    """LLM 助手入口（方案B 可见按钮）：主界面标题栏右侧添加图标，
    点击打开 assistant.CalendarAssistant。

    宿主 UI 为 Smartisan TitleBar（非 Android OptionsMenu）。
    入口插入点选在 l() 方法末尾（D TitleBar 布局完成处）：
      .method private l()V   .locals 3
      ... D.setCenterView(H) 之后、return-void 之前
    插入序列（p0 是 this，v0/v1 此处均为死值——setCenterView 的实参
    已消费完，后面只剩 return）：
      v0 = D（主标题栏）
      v1 = 0x7f0702f1（standard_icon_filter_selector，宿主自带资源）
      v0 = D.addRightImageView(v1)          -> 右侧新增可见图标
      v1 = new AssistantEntry(this)
      v0.setOnClickListener(v1)             -> 点击唤出助手
    AssistantEntry 由 build.sh 从 src/com/smartisan/calendar/AssistantEntry.java
    编译合入（javac -> d8 -> baksmali），onClick ->
    assistant.CalendarAssistant.show(activity)。
    可见按钮 = 用户可直接发现入口（此前长按方案在真机上不可发现，
    已弃用）；无新增资源 id、无新增布局，图标为宿主自带 selector。
    寄存器：v0/v1 复用插入点死值 + p0，全部 <= v15、无 wide。
    幂等 token: Lcom/smartisan/calendar/AssistantEntry;（AIO 内实际出现的串）
    """
    src = read(AIO)
    TOKEN = "Lcom/smartisan/calendar/AssistantEntry;"
    if TOKEN in src:
        print("[patches] assistant_entry: already applied")
        return

    METHOD = ".method private l()V"
    anchor = (
        "    iget-object v0, p0, Lcom/smartisan/calendar/AllInOneActivity;->D:Lsmartisanos/widget/TitleBar;\n"
        "\n"
        "    iget-object v1, p0, Lcom/smartisan/calendar/AllInOneActivity;->H:Lsmartisanos/widget/SmartisanSpinnerView;\n"
        "\n"
        "    invoke-virtual {v0, v1}, Lsmartisanos/widget/TitleBar;->setCenterView(Landroid/view/View;)V\n"
        "\n"
        "    return-void"
    )
    ensure_unique(src, anchor, "assistant_entry")
    lines = [
        "iget-object v0, p0, Lcom/smartisan/calendar/AllInOneActivity;->D:Lsmartisanos/widget/TitleBar;",
        "const v1, 0x7f0702f1",
        "invoke-virtual {v0, v1}, Lsmartisanos/widget/TitleBar;->addRightImageView(I)Landroid/widget/ImageView;",
        "move-result-object v0",
        "new-instance v1, Lcom/smartisan/calendar/AssistantEntry;",
        "invoke-direct {v1, p0}, Lcom/smartisan/calendar/AssistantEntry;-><init>(Landroid/app/Activity;)V",
        "invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setOnClickListener(Landroid/view/View$OnClickListener;)V",
    ]
    mi = src.find(METHOD)
    if mi < 0:
        die("assistant_entry: method l()V not found")
    ai = src.find(anchor, mi)
    if ai < 0 or ai > src.find(".end method", mi):
        die("assistant_entry: anchor not found inside l()V")
    # 在 return-void 之前插入（锚点末尾是 return-void）
    cut = ai + len(anchor) - len("    return-void")
    block = "".join("\n    " + l for l in lines) + "\n"
    src = src[:cut] + block + src[cut:]
    print("[patches] assistant_entry: applied (title-bar long-press wired)")
    write(AIO, src)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--with-bug1",
        action="store_true",
        help="额外应用 bug1（跳回今天短路）。任务书：LLM 测试包默认不应用。",
    )
    args = parser.parse_args()

    if not AIO.exists():
        die(f"missing {AIO}; run `make decode` first")

    # 顺序有讲究：SnapObs 依赖原始 sget-wide v3 行；bug1 改写判定块但
    # 不动 sget-wide 行；assistant_entry 独立于前两者。
    patch_stubs()
    if args.with_bug1:
        patch_bug1()
    patch_assistant_entry()
    print("[patches] all patches applied")


if __name__ == "__main__":
    main()
