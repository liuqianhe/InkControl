package com.inkcontrol.features.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 「待机与联网」四项的静态广播入口，调试时不用进界面逐个点。
 *
 * <pre>am broadcast -a com.inkcontrol.action.IDLE -p com.inkcontrol --es policy always --es net 30 --es sleep 3 --es screen 300</pre>
 *
 * <p>{@code -p} 必须带上：Android 8 起静态注册的接收器收不到隐式广播，不带包名会被拦成
 * "Background execution not allowed"，命令成功、设置不动。
 */
public final class IdleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        IdleCommand.runAsync(context,
                intent.getStringExtra(IdleCommand.EXTRA_POLICY),
                intent.getStringExtra(IdleCommand.EXTRA_NET),
                intent.getStringExtra(IdleCommand.EXTRA_SLEEP),
                intent.getStringExtra(IdleCommand.EXTRA_SCREEN),
                pending::finish);
    }
}
