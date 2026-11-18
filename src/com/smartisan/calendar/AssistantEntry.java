package com.smartisan.calendar;

import android.app.Activity;
import android.view.View;
import android.view.View.OnClickListener;

/**
 * 标题栏“日程助手”按钮的点击桥接（smali 侧最小接线）。
 *
 * 宿主 AllInOneActivity.b(int) 在初始化标题栏时通过
 * TitleBar.addRightImageView(standard_icon_filter_selector) 添加按钮，
 * 并 setOnClickListener(new AssistantEntry(this))。
 * 点击时把宿主 Activity 交给 assistant.CalendarAssistant.show(Activity)。
 *
 * 顶层类（非内部类），避免 smali 名 AllInOneActivity$AssistantEntry 的
 * “$”在补丁字符串里的转义问题。实现 OnClickListener，smali 引用为
 * Landroid/view/View$OnClickListener; 接口（$ 只出现在框架接口名里，
 * 该名在宿主 smali 中本来就大量存在，风险低）。
 */
public final class AssistantEntry implements OnClickListener {

    private final Activity activity;

    public AssistantEntry(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onClick(View view) {
        assistant.CalendarAssistant.show(activity);
    }
}
