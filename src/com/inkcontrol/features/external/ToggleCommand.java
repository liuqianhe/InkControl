package com.inkcontrol.features.external;

import android.content.Context;

import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.platform.ApplicationServices;

/**
 * 应用外部切换总开关的唯一出口。广播（{@link ToggleReceiver}）与透明页（{@code ToggleActivity}）
 * 都调这里，外部脚本只需记住一个 action 加一个 {@code feature} 参数。
 *
 * <p>只开放四个总开关：遮挡息屏、锁屏主题、自动刷新、翻页动画。细节参数（延时、分块数量、
 * 名单之类）不对外暴露，免得脚本把配置改成半开状态。
 *
 * <p>走的是和界面同一条写入链路（{@link ApplicationServices#change}），因此照样会跑环境检查：
 * 后端没就绪时广播会失败并把原因写进日志，不会留下「开关看着开了、实际没生效」的状态。
 */
public final class ToggleCommand {
    /** 外部入口用的 action：{@code am broadcast -a com.inkcontrol.action.TOGGLE -p com.inkcontrol --es feature cover}。 */
    public static final String ACTION = "com.inkcontrol.action.TOGGLE";
    /** 要切换的项，取别名或功能 id 都行。 */
    public static final String EXTRA_FEATURE = "feature";
    /** 可选：1 开启、0 关闭；不给就取反。 */
    public static final String EXTRA_VALUE = "value";

    /** {功能 id, 外部别名}；别名专给命令行用，好记也好敲。 */
    private static final String[][] ALLOWED = {
            {"wave.lock", "cover"},
            {"lock.style", "lock"},
            {"refresh.master", "refresh"},
            {"pageanim.master", "pageanim"},
    };

    /** 别名或功能 id → 功能 id；不认识返回 null。 */
    public static String resolve(String name) {
        if (name == null) return null;
        String key = name.trim();
        for (String[] pair : ALLOWED) {
            if (pair[0].equals(key) || pair[1].equalsIgnoreCase(key)) return pair[0];
        }
        return null;
    }

    /**
     * 切换一项并返回给调用方看的一句话。{@code value} 为 null 表示取反，1 开、0 关。
     *
     * <p>会阻塞到配置落盘并下发完成，不要在主线程调用。
     */
    public static String apply(Context context, String name, Integer value) throws Exception {
        String id = resolve(name);
        if (id == null) {
            throw new IllegalArgumentException("不认识的开关：" + name + "（可用 cover / lock / refresh / pageanim）");
        }
        ApplicationServices services = new ApplicationServices(context.getApplicationContext());
        Configuration config = services.configuration();
        boolean target = value == null ? !config.enabled(id) : value != 0;
        services.change(id, target, config.value(id));
        return (target ? "已开启：" : "已关闭：") + FeatureCatalog.get(id).title;
    }

    /**
     * 后台执行 {@link #apply}，结果用 Toast 回一句，然后在主线程回调 {@code done}。
     * 广播接收器与透明页共用这一段，省得各写一份线程切换。
     */
    public static void runAsync(Context context, String name, Integer value, Runnable done) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String text;
            try {
                text = apply(app, name, value);
            } catch (Throwable error) {
                String reason = error.getMessage();
                text = "墨控：" + (reason == null || reason.isEmpty() ? error.toString() : reason);
            }
            final String message = text;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                    // 后台应用在部分 ROM 上不给弹 Toast，失败也不影响开关本身。
                }
                if (done != null) done.run();
            });
        }, "inkcontrol-toggle").start();
    }

    private ToggleCommand() { }
}
