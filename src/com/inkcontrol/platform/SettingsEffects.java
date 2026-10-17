package com.inkcontrol.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.inkcontrol.core.ConditionalRestore;

/** 只记录写入所有权；用户配置仍以应用ConfigStore为准。 */
final class SettingsEffects {
    private static final String KEY = "eink_keyguard_style";
    static void apply(Context context) {
        SharedPreferences ledger = context.createDeviceProtectedStorageContext().getSharedPreferences("inkcontrol-owned-settings", Context.MODE_PRIVATE);
        int current = Settings.Global.getInt(context.getContentResolver(), KEY, 0);
        if (RuntimeConfig.enabled("lock.style")) {
            int target = Integer.parseInt(RuntimeConfig.value("lock.style"));
            int before = ledger.getInt("style.before", ConditionalRestore.stockLockStyle(current));
            if (!ledger.contains("style.written")) ledger.edit().putInt("style.before", before).putInt("style.written", target).commit();
            else ledger.edit().putInt("style.written", target).commit();
            if (current != target && !Settings.Global.putInt(context.getContentResolver(), KEY, target)) throw new IllegalStateException("锁屏样式写入失败");
        } else if (ledger.contains("style.written")) {
            if (ConditionalRestore.owns(String.valueOf(current), String.valueOf(ledger.getInt("style.written", -1)))) {
                int previous = ConditionalRestore.stockLockStyle(ledger.getInt("style.before", 0));
                if (!Settings.Global.putInt(context.getContentResolver(), KEY, previous)) throw new IllegalStateException("原厂样式恢复失败");
            }
            ledger.edit().clear().commit();
        }
    }
    private SettingsEffects() {}
}
