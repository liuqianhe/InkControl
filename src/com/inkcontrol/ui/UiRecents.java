package com.inkcontrol.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 墨控是否出现在最近任务（后台任务列表）里。
 *
 * <p>纯界面偏好：不进入功能配置、不参与后端校验，和「提示时长」一样放在 SharedPreferences 里。
 * 清单里已经默认 {@code excludeFromRecents="true"}，这里只负责按偏好把当前任务改回来。
 */
final class UiRecents {
    /** 默认隐藏。 */
    static final boolean DEFAULT = true;

    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("ui-recents", Context.MODE_PRIVATE);
    }

    static boolean hidden(Context context) {
        return preferences(context).getBoolean("hidden", DEFAULT);
    }

    static void setHidden(Context context, boolean value) {
        preferences(context).edit().putBoolean("hidden", value).commit();
    }

    /**
     * 按偏好设置当前任务在最近任务里的可见性。
     *
     * <p>清单里的 {@code excludeFromRecents} 是静态的、关不掉，所以「显示」这一侧必须在运行时
     * 用 {@code AppTask} 把标志改回 false。系统拒绝或机型不支持时保持清单默认值，界面不受影响。
     */
    static void apply(MainActivity activity) {
        boolean hidden = hidden(activity);
        try {
            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            int task = activity.getTaskId();
            for (ActivityManager.AppTask entry : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = entry.getTaskInfo();
                if (info != null && info.taskId == task) {
                    entry.setExcludeFromRecents(hidden);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到任务（或系统拒绝）时按清单默认走
        }
    }

    private UiRecents() { }
}
