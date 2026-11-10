package com.inkcontrol.features.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 应用外部切换总开关的静态入口。
 *
 * <pre>am broadcast -a com.inkcontrol.action.TOGGLE -p com.inkcontrol --es feature cover</pre>
 *
 * <p>{@code -p} 必须带上：Android 8 起静态注册的接收器收不到隐式广播，不带包名会被
 * {@code BroadcastQueue} 拦成 "Background execution not allowed"，命令成功、开关不动。
 *
 * <p>写配置要走 root 下发，耗时可能超过几秒，所以用 {@code goAsync()} 把进程留住，
 * 处理完再 {@code finish()}。
 */
public final class ToggleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String name = intent.getStringExtra(ToggleCommand.EXTRA_FEATURE);
        Integer value = intent.hasExtra(ToggleCommand.EXTRA_VALUE)
                ? intent.getIntExtra(ToggleCommand.EXTRA_VALUE, 1) : null;
        final PendingResult pending = goAsync();
        ToggleCommand.runAsync(context, name, value, pending::finish);
    }
}
