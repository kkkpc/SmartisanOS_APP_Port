package dbg;

import android.content.Context;

/**
 * 日志插桩辅助类。
 *
 * 目的：让 smali 侧的插桩点尽量短（通常 1~2 行 invoke-static），
 * 同时把"要打印什么"集中在 Java 里，便于 AI 分析与后续调整。
 *
 * 典型 smali 用法：
 *   invoke-static {p0}, Ldbg/Trace;->onResume(Landroid/content/Context;)V
 *   invoke-static {p0, v1, v2}, Ldbg/Trace;->state(Landroid/content/Context;JJ)V
 */
public final class Trace {

    private Trace() {
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public static void onCreate(Context ctx) {
        Logger.i("lifecycle", "AllInOneActivity.onCreate");
    }

    /**
     * @param ctx       Activity
     * @param viewTime  控制器当前时间（this.j.c()），-1 表示未知
     * @param viewType  视图类型（this.j.f()）
     */
    public static void onResume(Context ctx, long viewTime, int viewType) {
        Logger.i("lifecycle", "AllInOneActivity.onResume"
                + " viewTime=" + viewTime
                + " viewType=" + viewType
                + " now=" + System.currentTimeMillis());
    }

    public static void onPause(Context ctx) {
        Logger.i("lifecycle", "AllInOneActivity.onPause");
    }

    /**
     * 离开前台时记录的时间戳（onUserLeaveHint 里写入静态 R 字段）
     */
    public static void onUserLeaveHint(Context ctx, long leaveAt) {
        Logger.i("lifecycle", "onUserLeaveHint leaveAt=" + leaveAt);
    }

    /**
     * 后台返回时的"跳回今天"判定决策点（插桩在判定块入口，零额外寄存器）。
     *
     * @param now     当前时刻（ms）
     * @param leaveAt R 字段：离开前台时刻（0 表示无记录）
     */
    public static void onSnapLeave(long now, long leaveAt) {
        long elapsed = now - leaveAt;
        // 与原判定逻辑等价：leaveAt != 0 && elapsed >= 1 小时
        boolean wouldJump = (leaveAt != 0L) && (elapsed >= 3600000L);
        Logger.i("snap", "decision"
                + " now=" + now
                + " leaveAt=" + leaveAt
                + " elapsed=" + elapsed + "ms"
                + " wouldJump=" + wouldJump
                + (wouldJump ? " => ORIGINAL_LOGIC_JUMPS_TO_TODAY" : " => keep_position"));
    }

    /**
     * 判定块执行后，记录控制器最终时间是否被改成"现在"。
     */
    public static void onSnapApplied(long beforeTime, long afterTime) {
        Logger.i("snap", "applied"
                + " beforeTime=" + beforeTime
                + " afterTime=" + afterTime
                + (beforeTime == afterTime ? " => position_kept" : " => position_changed"));
    }

    public static void onSaveInstanceState(Context ctx, long viewTime, int viewType) {
        Logger.i("lifecycle", "onSaveInstanceState viewTime=" + viewTime + " viewType=" + viewType);
    }

    public static void onNewIntent(Context ctx, String action, long targetTime) {
        Logger.i("lifecycle", "onNewIntent action=" + action + " targetTime=" + targetTime);
    }

    // ------------------------------------------------------------------
    // 关键业务
    // ------------------------------------------------------------------

    /**
     * 视图刷新（updateFragments）——UI 渲染实际发生的地方。
     */
    public static void updateFragments(Context ctx, long viewTime, int viewType, boolean snapFlag) {
        Logger.i("ui", "updateFragments"
                + " viewTime=" + viewTime
                + " viewType=" + viewType
                + " snapFlag=" + snapFlag);
    }
}
