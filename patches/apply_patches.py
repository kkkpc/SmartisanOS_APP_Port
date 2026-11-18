#!/usr/bin/env python3
"""对 apktool 反编译产物应用 smali 补丁（幂等，可重复执行）。

用法: python3 patches/apply_patches.py

设计：apktool/ 目录是 decode 的确定性产物（已 gitignore），
本脚本在每次 decode 之后、encode 之前运行，把补丁重放上去。
每个补丁独立函数，先检查是否已应用（幂等），找不到锚点则报错退出。

补丁清单：
  bug1  : 短路"离开>=1小时跳回今天"（方案A，用户已确认）
  stubs : AI 调试日志插桩（dbg.Logger / dbg.Trace）
          - CalendarApplication.onCreate -> Logger.init(this)
          - AllInOneActivity 生命周期：onCreate/onResume/onPause/
            onUserLeaveHint/onSaveInstanceState/onNewIntent
          - snap 判定块观测（记录 now / leaveAt / elapsed / wouldJump）
          - UI 渲染入口观测（a(long,int,Bundle)）

寄存器安全策略（已逐方法核实原代码，2026-11-18）：
  smali 的 invoke 参数寄存器只能是 v0..v15（4 位寄存器号），
  因此所有插桩点必须复用原代码在该位置"已定义且将被覆盖"的寄存器，
  或在必要时扩展 .locals。wide 寄存器必须成对（偶数起）且相邻。
  详细说明见每个 patch 的注释。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SMALI = ROOT / "apktool/calendar/smali"
AIO = SMALI / "com/android/calendar/AllInOneActivity.smali"
APP = SMALI / "com/android/calendar/CalendarApplication.smali"

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
        [f"invoke-static {{p0}}, Ldbg/Logger;->init(Landroid/content/Context;)V"],
        "stub:AppInit",
        already_token="Ldbg/Logger;->init(Landroid/content/Context;)V",
    )
    write(APP, app)

    # ---- 2) AllInOneActivity.onCreate ----
    # .locals 11。插入点在 invoke-super 之后，此处 v0 尚未定义但紧随其后
    # 就会被 new-instance v0 覆盖 —— 但我们不借用 v0，只用 p0，零开销。
    src = insert_after(
        src,
        ".method protected onCreate(Landroid/os/Bundle;)V",
        "invoke-super {p0, p1}, Lcom/android/calendar/BaseActivity;->onCreate(Landroid/os/Bundle;)V",
        [f"invoke-static {{p0}}, {DST}->onCreate(Landroid/content/Context;)V"],
        "stub:AIO.onCreate",
        already_token=f"{DST}->onCreate(",
    )

    # ---- 3) AllInOneActivity.onResume：记录 viewTime / viewType ----
    # 插入点：e->b(0, this) 调用之后（此时 j 已初始化）。
    # 寄存器：v0/v1 装 viewTime(wide)，v2 装 viewType(int)。
    #   该位置原代码随后 new-instance v0 / toMillis 会覆盖 v0，
    #   v2 随后被 move-result-wide v2 覆盖 —— 均安全。
    src = insert_after(
        src,
        ".method protected onResume()V",
        "invoke-virtual {v0, v15, v14}, Lcom/android/calendar/e;->b(ILcom/android/calendar/e$a;)V",
        [
            "iget-object v10, v14, Lcom/android/calendar/AllInOneActivity;->j:Lcom/android/calendar/e;",
            "invoke-virtual {v10}, Lcom/android/calendar/e;->c()J",
            "move-result-wide v0",
            "iget-object v10, v14, Lcom/android/calendar/AllInOneActivity;->j:Lcom/android/calendar/e;",
            "invoke-virtual {v10}, Lcom/android/calendar/e;->f()I",
            "move-result v2",
            f"invoke-static {{v14, v0, v2}}, {DST}->onResume(Landroid/content/Context;JI)V",
        ],
        "stub:AIO.onResume",
        already_token=f"{DST}->onResume(",
    )

    # ---- 4) AllInOneActivity.onPause ----
    src = insert_after(
        src,
        ".method protected onPause()V",
        "invoke-super {p0}, Lcom/android/calendar/BaseActivity;->onPause()V",
        [f"invoke-static {{p0}}, {DST}->onPause(Landroid/content/Context;)V"],
        "stub:AIO.onPause",
        already_token=f"{DST}->onPause(",
    )

    # ---- 5) AllInOneActivity.onUserLeaveHint：记录离开时刻 ----
    # .locals 2（v0=Time 对象, v1=常量1）。插入点 new-instance v0 之前，
    # 此时 v0/v1 都未定义 —— 我们不借用寄存器，只用 p0 + v0（随后被覆盖）。
    # 用 v0 传 leaveAt：其值在下方 toMillis 后才有意义，因此插桩要放在
    # sput-wide R 之后更准确 —— 但那样 v0 已被覆盖。
    # 折中：放在方法末尾（invoke-super 之前），此时 v0 = leaveAt(wide) 仍然有效。
    src = insert_before(
        src,
        ".method protected onUserLeaveHint()V",
        "invoke-super {p0}, Lcom/android/calendar/BaseActivity;->onUserLeaveHint()V",
        [f"invoke-static {{p0, v0}}, {DST}->onUserLeaveHint(Landroid/content/Context;J)V"],
        "stub:AIO.onUserLeaveHint",
        already_token=f"{DST}->onUserLeaveHint(",
    )

    # ---- 6) AllInOneActivity.onSaveInstanceState：记录保存的视图状态 ----
    # .locals 3（v0,v1,v2）。插入点在方法末尾 return-void 之前。
    # v0 装 viewTime(wide, 用 v0/v1 对)，v2 装 viewType(int)。
    # 注意：不能用 v1 装 viewType —— v1 是 v0 wide 对的高位，会破坏 v0。
    src = insert_before(
        src,
        ".method public onSaveInstanceState(Landroid/os/Bundle;)V",
        "    return-void",
        [
            "iget-object v0, p0, Lcom/android/calendar/AllInOneActivity;->j:Lcom/android/calendar/e;",
            "invoke-virtual {v0}, Lcom/android/calendar/e;->c()J",
            "move-result-wide v0",
            "iget v2, p0, Lcom/android/calendar/AllInOneActivity;->r:I",
            f"invoke-static {{p0, v0, v2}}, {DST}->onSaveInstanceState(Landroid/content/Context;JI)V",
        ],
        "stub:AIO.onSaveInstanceState",
        already_token=f"{DST}->onSaveInstanceState(",
    )

    # ---- 7) AllInOneActivity.onNewIntent：记录新 intent ----
    # .locals 11。插入点在 getAction 之后：v0=action(String)。
    # 额外传 targetTime=0（该方法后续才解析时间）。
    src = insert_after(
        src,
        ".method protected onNewIntent(Landroid/content/Intent;)V",
        "invoke-virtual {p1}, Landroid/content/Intent;->getAction()Ljava/lang/String;",
        [
            "move-result-object v0",
            "const-wide/16 v2, 0x0",
            f"invoke-static {{p0, v0, v2}}, {DST}->onNewIntent(Landroid/content/Context;Ljava/lang/String;J)V",
        ],
        "stub:AIO.onNewIntent",
        already_token=f"{DST}->onNewIntent(",
    )

    # ---- 8) snap 判定块观测：记录 now / leaveAt / elapsed / wouldJump ----
    # 插入点在 `sget-wide v3, ...->R:J` 之后（此处 v1=now, v3=leaveAt 都已就绪，
    # 直接复用，零新增寄存器）。Java 侧计算 elapsed 与 wouldJump。
    # 注意：此插入必须在 bug1 之前执行（bug1 会把 if-ltz 改成 goto，
    #       但 sget-wide 行不变，锚点稳定）。
    src = insert_after(
        src,
        ".method protected onResume()V",
        "sget-wide v3, Lcom/android/calendar/AllInOneActivity;->R:J",
        [f"invoke-static {{v1, v3}}, {DST}->onSnapLeave(JJ)V"],
        "stub:SnapObs",
        already_token=f"{DST}->onSnapLeave(",
    )

    # ---- 9) UI 渲染入口观测：a(long, int, Bundle) ----
    # 该方法 .locals 16。插入点：move-object/from16 v0, p4 之后。
    # v9=this, v7=viewTime(wide), v10=viewType(int), v12 装 snapFlag。
    # v12 在下方被 `const/4 v12, 0x6` 覆盖 —— 安全。
    src = insert_after(
        src,
        ".method private a(JILandroid/os/Bundle;)V",
        "move-object/from16 v0, p4",
        [
            "iget-boolean v12, v9, Lcom/android/calendar/AllInOneActivity;->t:Z",
            f"invoke-static {{v9, v7, v10, v12}}, {DST}->updateFragments(Landroid/content/Context;JIZ)V",
        ],
        "stub:UpdateFragments",
        already_token=f"{DST}->updateFragments(",
    )

    write(AIO, src)


def main():
    if not AIO.exists():
        die(f"missing {AIO}; run `make decode` first")
    patch_bug1()
    patch_stubs()
    print("[patches] all patches applied")


if __name__ == "__main__":
    main()
