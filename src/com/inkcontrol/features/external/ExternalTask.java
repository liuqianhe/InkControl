package com.inkcontrol.features.external;

import android.content.Context;

import java.util.concurrent.Callable;

/**
 * 外部入口共用的一段：后台跑任务、用 Toast 回一句结果、然后在主线程回调 {@code done}。
 *
 * <p>写配置要走 root 下发，耗时可能到几秒，所以不能在广播接收器的主线程里直接做；
 * {@code goAsync()} 撑住进程 + 这里切线程，处理完再放行。
 */
final class ExternalTask {
    static void run(Context context, Callable<String> task, Runnable done) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String text;
            try {
                text = task.call();
            } catch (Throwable error) {
                // 外部调用没有界面，失败原因除了 Toast 再留一份到 logcat，
                // 免得 Toast 一闪而过时只能猜（`adb logcat -s InkControl`）。
                android.util.Log.e("InkControl", "外部调用失败", error);
                String reason = error.getMessage();
                text = "墨控：" + (reason == null || reason.isEmpty() ? error.toString() : reason);
            }
            final String message = text;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                    // 后台应用在部分 ROM 上不给弹 Toast，失败也不影响改动本身。
                }
                if (done != null) done.run();
            });
        }, "inkcontrol-external").start();
    }

    private ExternalTask() { }

    /**
     * 改完配置后叫醒已经打开的墨控界面：外部入口是在应用进程里写的配置，界面拿的是
     * 打开页面那一刻的快照，不发这条通知就会一直显示旧值（「广播改了但界面没动」）。
     */
    static void notifyUi(Context context) {
        try {
            context.getContentResolver().notifyChange(
                    android.net.Uri.withAppendedPath(com.inkcontrol.platform.ConfigProvider.URI, "panel-toggle"),
                    null);
        } catch (Throwable ignored) {
            // 界面没开或通知失败都不影响配置本身。
        }
    }
}
