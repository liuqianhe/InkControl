package com.inkcontrol.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.inkcontrol.features.external.ToggleCommand;

/**
 * 外部切换总开关的无界面入口：墨控被「强制停止」时用它。
 *
 * <p>透明、无历史、不占任务栈，和 {@link MottoActivity} 同一套思路——厂商的进程管控只拦
 * provider / service 的拉起，不拦 {@code startActivity}，所以被 force-stop 之后广播叫不醒
 * 墨控的时候，这条还能用。
 *
 * <pre>am start -a com.inkcontrol.action.TOGGLE -n com.inkcontrol/com.inkcontrol.ui.ToggleActivity --es feature cover</pre>
 */
public final class ToggleActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String name = intent == null ? null : intent.getStringExtra(ToggleCommand.EXTRA_FEATURE);
        Integer value = intent != null && intent.hasExtra(ToggleCommand.EXTRA_VALUE)
                ? intent.getIntExtra(ToggleCommand.EXTRA_VALUE, 1) : null;
        // 切完就退，不留界面；失败原因由 Toast 交代。
        ToggleCommand.runAsync(this, name, value, this::finish);
    }
}
