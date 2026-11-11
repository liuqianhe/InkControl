package com.inkcontrol.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.inkcontrol.features.external.IdleCommand;

/**
 * 「待机与联网」三项的无界面入口：墨控被「强制停止」时用它。
 *
 * <p>透明、无历史、不占任务栈，和 {@link ToggleActivity} 同一套思路——被 force-stop 的应用
 * 处于 stopped 状态，静态广播不会投递（`am broadcast --include-stopped-packages` 也只能解决
 * 投递，本机海信的安全服务仍会拦后台拉起），而 {@code startActivity} 不受这条限制。
 *
 * <pre>am start -n com.inkcontrol/com.inkcontrol.ui.IdleActivity --es policy always --es net 30</pre>
 */
public final class IdleActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        // 做完就退，不留界面；失败原因由 Toast 交代。
        IdleCommand.runAsync(this,
                intent == null ? null : intent.getStringExtra(IdleCommand.EXTRA_POLICY),
                intent == null ? null : intent.getStringExtra(IdleCommand.EXTRA_NET),
                intent == null ? null : intent.getStringExtra(IdleCommand.EXTRA_SLEEP),
                this::finish);
    }
}
