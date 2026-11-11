package com.inkcontrol.ui;

import android.app.Activity;
import android.os.Bundle;

import com.inkcontrol.platform.ConfigProvider;

/**
 * 开机自启的无界面入口：{@code am start -n com.inkcontrol/com.inkcontrol.ui.WakeActivity}。
 *
 * <p>这台机器的进程管控会拦下系统进程对墨控 {@code ConfigProvider} 的拉起
 * （日志里表现为 {@code Unknown authority com.inkcontrol.state} / {@code it's restricted}），
 * 于是 system_server、SystemUI 里的 {@code RuntimeConfig} 一直只能拿到「全部关闭」的快照，
 * 翻页动画与全刷扫过这类跑在系统进程里的功能就全部按原厂瞬切运行。
 * 和 {@link ToggleActivity} 一样，厂商拦 provider / service，不拦 {@code startActivity}，
 * 所以后端开机时显式启动这个透明、无历史、不占任务栈的页面把进程叫醒。
 *
 * <p>页面自己什么都不显示：只向自己的 Provider 发一次 {@code wake}，这次跨进程调用会创建
 * {@code :state} 进程，Provider 随即通知所有已注入的进程重读配置，然后立刻结束。
 * 用子线程做这次调用，避免启动 Provider 时阻塞主线程；无论成功失败都会结束页面。
 */
public final class WakeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try { getContentResolver().call(ConfigProvider.URI, "wake", null, null); }
            catch (Throwable ignored) { /* 叫不醒就交给下次开机或运行守护重试 */ }
            runOnUiThread(this::finish);
        }, "inkcontrol-wake").start();
    }
}
