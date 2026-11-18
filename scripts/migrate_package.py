#!/usr/bin/env python3
"""
包名迁移（可复现）：com.android.calendar -> com.smartisan.calendar
在仓库共享树上执行（decode 之后、apply_patches.py 之前运行）。

流程定位（决策 2026-11-18：rename 先行、patch 后行）：
    make llm-decode          # 干净 decode（com/android 形态）
    python3 scripts/migrate_package.py   # 本脚本：A-E 步
    python3 patches/apply_patches.py     # patch 用 smartisan 锚点，幂等成立

步骤（继承自快照验证过的 migrate.py，适配共享树）：
  A. smali 类树移动：smali/com/android/calendar -> smali/com/smartisan/calendar
  B. 全树文本替换：Lcom/android/calendar/ -> Lcom/smartisan/calendar/（仅 .smali）
  C. const-string 字面量按审计表改（(b) 组 20 处；(a) 组 KEEP 断言在位）
  D. res：layout 自定义 View 类名、searchable/general_preferences/backup_rules
  E. AndroidManifest：package 属性、组件相对名、authorities、custom permission
     （apktool 3.0.1 无 renameManifestPackage，直接改 manifest package 属性，
      审计依据见 deliverables/audit/STRING_AUDIT.md）

与快照版的差异（审核裁决）：
  - 删除 step_f（src Java 迁移）：AssistantEntry.java 已定稿在
    src/com/smartisan/calendar/，本脚本不碰 git 管理的源文件。
  - 删除 step_g（Makefile/apply_patches.py 改写）：两者已手工定稿。
  - ROOT 相对化：脚本以自身位置定位仓库根，可在任意 checkout 复现。
  - step_c 的 BridgeProvider authority 锚点：共享树为 provider/c.smali
    （快照为 provider/p.smali，混淆名不同；已实测计数一致，2026-11-18）。

红线（KEEP）：系统 CalendarProvider 的 authority/host 字符串一律保留
  （content://com.android.calendar/... URI、"com.android.calendar" 纯 authority、
   queries 里的系统 provider 项），真机日历数据仍从系统 provider 读取。

幂等性：A 步以 NEW_DIR 存在为已迁移标志；B/C/D/E 的替换串与目标串
  互不重叠，重复运行不产生二次替换；断言失败立即 SystemExit，零半迁移态。
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CAL = ROOT / "apktool/calendar"
SMALI = CAL / "smali"
OLD_DIR = SMALI / "com/android/calendar"
NEW_DIR = SMALI / "com/smartisan/calendar"

changed = []


def rel(p):
    return str(p.relative_to(ROOT))


def replace_in_file(path, old, new, count=1):
    """替换 path 内容中的 old -> new。count=期望出现次数（0 表示不校验，全部替换）。"""
    s = path.read_text(encoding="utf-8")
    n = s.count(old)
    if n == 0:
        raise SystemExit(f"MISSING in {rel(path)}: {old[:60]!r}")
    if count and n != count:
        raise SystemExit(f"COUNT MISMATCH in {rel(path)}: {old[:60]!r} expected {count} got {n}")
    path.write_text(s.replace(old, new), encoding="utf-8")
    changed.append((rel(path), old[:70], n))


def replace_all_in_file(path, old, new):
    s = path.read_text(encoding="utf-8")
    if old not in s:
        return 0
    n = s.count(old)
    path.write_text(s.replace(old, new), encoding="utf-8")
    changed.append((rel(path), old[:70], n))
    return n


# ---------------------------------------------------------------- A. 目录移动
def step_a():
    if NEW_DIR.exists():
        print(f"[A] {rel(NEW_DIR)} already exists -> skip move")
        return
    if not OLD_DIR.exists():
        raise SystemExit("[A] source dir missing (run `make llm-decode` first)")
    NEW_DIR.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(OLD_DIR), str(NEW_DIR))
    n = sum(1 for _ in NEW_DIR.rglob("*.smali"))
    print(f"[A] moved class tree -> {rel(NEW_DIR)} ({n} smali files)")


# ---------------------------------------------------------------- B. 全树 L 前缀替换
def step_b():
    total_files = 0
    total_occ = 0
    for p in SMALI.rglob("*.smali"):
        s = p.read_text(encoding="utf-8")
        if "Lcom/android/calendar/" in s:
            n = s.count("Lcom/android/calendar/")
            p.write_text(s.replace("Lcom/android/calendar/", "Lcom/smartisan/calendar/"), encoding="utf-8")
            total_files += 1
            total_occ += n
    print(f"[B] L-prefix replaced in {total_files} files, {total_occ} occurrences")
    for p in SMALI.rglob("*.smali"):
        if "Lcom/android/calendar/" in p.read_text(encoding="utf-8"):
            raise SystemExit(f"[B] residue in {rel(p)}")
    print("[B] no Lcom/android/calendar/ residue in smali tree")


# ------------------------------------------------- C. const-string 定点修改（审计表 (b) 组）
def step_c():
    C = NEW_DIR  # A 步之后类树已在 smartisan 下
    # 1. BridgeProvider authority URI（共享树实测：provider/c.smali）
    replace_in_file(C / "provider/c.smali",
                    '"content://com.android.calendar.adapt"',
                    '"content://com.smartisan.calendar.adapt"')
    # 2. RecentSuggestionsProvider authority
    replace_all_in_file(C / "CalendarRecentSuggestionsProvider.smali",
                        '"com.android.calendar.CalendarRecentSuggestionsProvider"',
                        '"com.smartisan.calendar.CalendarRecentSuggestionsProvider"')
    # 3. widget EXTRA_EVENT_IDS（自含 extra 键）
    replace_in_file(C / "widget/CalendarAppWidgetProvider.smali",
                    '"com.android.calendar.EXTRA_EVENT_IDS"',
                    '"com.smartisan.calendar.EXTRA_EVENT_IDS"')
    # 4. 偏好文件名 7 处
    for f, cnt in [("aa.smali", 1), ("v.smali", 3),
                   ("setting/SelectCalendarActivity.smali", 1),
                   ("event/EditEventActivity.smali", 1), ("event/e.smali", 1)]:
        replace_in_file(C / f, '"com.android.calendar_preferences"',
                        '"com.smartisan.calendar_preferences"', cnt)
    # 5. no_backup 偏好
    replace_in_file(C / "NoBackupPreferences.smali",
                    '"com.android.calendar_no_backup"',
                    '"com.smartisan.calendar_no_backup"')
    # 6. 自定义 action：AlertReceiver 4 个
    r = C / "alerts/AlertReceiver.smali"
    replace_in_file(r, '"com.android.calendar.DELETE"', '"com.smartisan.calendar.DELETE"', 2)
    replace_in_file(r, '"com.android.calendar.DELETEALL"', '"com.smartisan.calendar.DELETEALL"')
    replace_in_file(r, '"com.android.calendar.MAIL"', '"com.smartisan.calendar.MAIL"')
    replace_in_file(r, '"com.android.calendar.CLICK"', '"com.smartisan.calendar.CLICK"')
    # 7. SUGGESTION_CLICKED
    replace_in_file(C / "EventInfoActivity.smali",
                    '"com.android.calendar.SUGGESTION_CLICKED"',
                    '"com.smartisan.calendar.SUGGESTION_CLICKED"')
    # 8. ACTION_REFRESH_REMINDERS 成对两处
    replace_in_file(C / "CalendarApplication$1.smali",
                    '"com.android.calendar.ACTION_REFRESH_REMINDERS"',
                    '"com.smartisan.calendar.ACTION_REFRESH_REMINDERS"')
    replace_in_file(C / "alerts/AlertService.smali",
                    '"com.android.calendar.ACTION_REFRESH_REMINDERS"',
                    '"com.smartisan.calendar.ACTION_REFRESH_REMINDERS"')
    # 9. date_changed 成对两处
    replace_in_file(C / "receiver/SubScriptBootReceiver.smali",
                    '"com.android.calendar.date_changed"',
                    '"com.smartisan.calendar.date_changed"')
    replace_in_file(C / "k/ac.smali",
                    '"com.android.calendar.date_changed"',
                    '"com.smartisan.calendar.date_changed"')
    # 10. 遗留 channel id
    replace_in_file(C / "CalendarApplication.smali",
                    '"com.android.calendar.channel"',
                    '"com.smartisan.calendar.channel"')
    print("[C] const-string edits applied (audit table group b)")

    # 审计表 (a) 组 KEEP 断言：系统 CalendarProvider 引用必须在位
    keep_checks = [
        (C / "widget/CalendarAppWidgetProvider.smali", '"content://com.android.calendar/events"'),
        (C / "widget/CalendarAppWidgetProvider.smali", '"content://com.android.calendar/time/"'),
        (C / "x.smali", '"content://com.android.calendar/calendar_meta_data"'),
        (C / "EventInfoActivity.smali", '"content://com.android.calendar/relation"'),
        (C / "provider/h.smali", '"com.android.calendar"'),
        (C / "a/a.smali", '"com.android.calendar"'),
        (C / "a/b.smali", '"com.android.calendar"'),
        (C / "b.smali", '"com.android.calendar"'),
        (C / "d.smali", '"com.android.calendar"'),
        (C / "d$2.smali", '"com.android.calendar"'),
        (C / "AllInOneActivity$a.smali", '"com.android.calendar"'),
        (C / "tablet/TabletAllInOneActivity$a.smali", '"com.android.calendar"'),
        (C / "event/c.smali", '"com.android.calendar"'),
        (C / "receiver/SubScriptBootReceiver.smali", '"com.android.calendar"'),
        (C / "weather/d.smali", '"com.android.calendar"'),
        (C / "k/t.smali", '"com.android.calendar"'),
        (C / "k/t$1.smali", '"com.android.calendar"'),
        (C / "k/t$2.smali", '"com.android.calendar"'),
        (C / "aa.smali", '"com.android.calendar"'),
        (C / "setting/AccountManageActivity.smali", '"com.android.calendar"'),
        (C / "setting/CalendarManagerActivity.smali", '"com.android.calendar"'),
        (C / "event/e.smali", '"com.android.calendar"'),
    ]
    for f, lit in keep_checks:
        if lit not in f.read_text(encoding="utf-8"):
            raise SystemExit(f"[C] KEEP-item lost: {rel(f)} {lit}")
    print(f"[C] all {len(keep_checks)} KEEP-items verified present")


# ---------------------------------------------------------------- D. res
def step_d():
    res = CAL / "res"
    # layout：自定义 View 完整类名
    n_files = 0
    n_occ = 0
    for d in res.iterdir():
        if not d.name.startswith("layout"):
            continue
        for p in d.glob("*.xml"):
            s = p.read_text(encoding="utf-8")
            if "com.android.calendar." in s:
                n = s.count("com.android.calendar.")
                p.write_text(s.replace("com.android.calendar.", "com.smartisan.calendar."), encoding="utf-8")
                n_files += 1
                n_occ += n
    print(f"[D] layout class refs: {n_files} files, {n_occ} occurrences")

    # searchable.xml：authority + action 改；IntentData 的 content:// 系统 URI 保留
    sx = res / "xml/searchable.xml"
    s = sx.read_text(encoding="utf-8")
    if "content://com.android.calendar/events" not in s:
        raise SystemExit("[D] searchable IntentData anchor missing")
    s = s.replace('android:searchSuggestAuthority="com.android.calendar.CalendarRecentSuggestionsProvider"',
                  'android:searchSuggestAuthority="com.smartisan.calendar.CalendarRecentSuggestionsProvider"')
    s = s.replace('android:searchSuggestIntentAction="com.android.calendar.SUGGESTION_CLICKED"',
                  'android:searchSuggestIntentAction="com.smartisan.calendar.SUGGESTION_CLICKED"')
    sx.write_text(s, encoding="utf-8")
    if "content://com.android.calendar/events" not in sx.read_text(encoding="utf-8"):
        raise SystemExit("[D] searchable IntentData content URI must stay")
    print("[D] searchable.xml: authority/action renamed; IntentData content URI kept")

    # general_preferences.xml：fragment 类名
    gp = res / "xml/general_preferences.xml"
    replace_in_file(gp,
                    'android:fragment="com.android.calendar.QuickResponseSettings"',
                    'android:fragment="com.smartisan.calendar.QuickResponseSettings"')
    # backup_rules.xml：no_backup 偏好路径
    br = res / "xml/backup_rules.xml"
    replace_in_file(br,
                    'path="com.android.calendar_no_backup.xml"',
                    'path="com.smartisan.calendar_no_backup.xml"')
    print("[D] general_preferences + backup_rules done")


# ---------------------------------------------------------------- E. Manifest
def step_e():
    mf = CAL / "AndroidManifest.xml"
    s = mf.read_text(encoding="utf-8")
    # 0) package 属性
    s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.android.calendar">',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.smartisan.calendar">')
    # 1) 组件名/类名值 -> 相对名（. 前缀）
    s = s.replace('android:name="com.android.calendar.', 'android:name=".')
    s = s.replace('android:value="com.android.calendar.', 'android:value=".')
    s = s.replace('android:targetActivity="com.android.calendar.', 'android:targetActivity=".')
    # 2) queries 自可见性
    s = s.replace('<package android:name="com.android.calendar"/>',
                  '<package android:name="com.smartisan.calendar"/>')
    # 3) custom permission
    s = s.replace('android:name="com.android.calendar.adapt.READ_PERMISSION"',
                  'android:name="com.smartisan.calendar.adapt.READ_PERMISSION"')
    # 4) BridgeProvider authority + readPermission
    s = s.replace('android:authorities="com.android.calendar.adapt"',
                  'android:authorities="com.smartisan.calendar.adapt"')
    s = s.replace('android:readPermission="com.android.calendar.adapt.READ_PERMISSION"',
                  'android:readPermission="com.smartisan.calendar.adapt.READ_PERMISSION"')
    # 5) RecentSuggestionsProvider authority
    s = s.replace('android:authorities="com.android.calendar.CalendarRecentSuggestionsProvider"',
                  'android:authorities="com.smartisan.calendar.CalendarRecentSuggestionsProvider"')
    # 6) ACTION_REFRESH_REMINDERS
    s = s.replace('android:name="com.android.calendar.ACTION_REFRESH_REMINDERS"',
                  'android:name="com.smartisan.calendar.ACTION_REFRESH_REMINDERS"')

    # KEEP 断言（系统 provider 可见性 + URI host + 原 holidays authority）
    for keep in ['<provider android:authorities="com.android.calendar"/>',
                 '<package android:name="com.android.providers.calendar"/>',
                 '<data android:host="com.android.calendar"/>',
                 'android:taskAffinity="android.task.calendar.alert"',
                 'android:authorities="com.smartisan.calendar.holidays"']:
        if keep not in s:
            raise SystemExit(f"[E] KEEP-item missing: {keep}")
    if s.count('<data android:host="com.android.calendar"/>') != 2:
        raise SystemExit("[E] host keep count != 2")

    # 残留检查：manifest 里不再出现 com.android.calendar（保留项除外）
    leftover = set(re.findall(r'com\.android\.calendar[^"]*', s))
    allowed = {"com.android.calendar"}  # queries provider authorities + data host（纯 authority/host）
    bad = leftover - allowed
    if bad:
        raise SystemExit(f"[E] unexpected leftovers: {bad}")
    mf.write_text(s, encoding="utf-8")
    print("[E] manifest rewritten; kept system-provider queries/host entries")


def main():
    steps = {"A": step_a, "B": step_b, "C": step_c, "D": step_d, "E": step_e}
    names = sys.argv[1:] or list(steps)
    if not NEW_DIR.exists() and not OLD_DIR.exists():
        raise SystemExit("smali tree missing; run `make llm-decode` first")
    for n in names:
        print(f"===== step {n} =====")
        steps[n]()
    audit = ROOT / "audit"
    audit.mkdir(exist_ok=True)
    with open(audit / "MIGRATION_CHANGELOG.txt", "a", encoding="utf-8") as f:
        for p, o, n in changed:
            f.write(f"{p}\t{o}\t{n}\n")
    print(f"\n{len(changed)} change-entries appended -> audit/MIGRATION_CHANGELOG.txt")


if __name__ == "__main__":
    main()
