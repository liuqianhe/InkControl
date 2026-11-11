package com.inkcontrol.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.inkcontrol.core.ConditionalRestore;

/** 只记录写入所有权；用户配置仍以应用ConfigStore为准。 */
final class SettingsEffects {
    private static final String KEY = "eink_keyguard_style";
    static void apply(Context context) {
        // 安全输入法开关与锁屏样式各自持有写入所有权，互不干扰。
        com.inkcontrol.features.fix.SafeInputEffects.apply(context);
        SharedPreferences ledger = context.createDeviceProtectedStorageContext().getSharedPreferences("inkcontrol-owned-settings", Context.MODE_PRIVATE);
        int current = Settings.Global.getInt(context.getContentResolver(), KEY, 0);
        if (RuntimeConfig.enabled("lock.style")) {
            int logical = Integer.parseInt(RuntimeConfig.value("lock.style"));
            // 原厂 EinkKeyguardController 只接受 0–9 的 eink_keyguard_style（样式 10+ 会被它重置回 0）。
            // 逻辑 10（编辑排版-横-逆）与 11（透明书封）都复用物理 9，具体画哪一种由 LockCanvas
            // 按配置里的逻辑值判定；这里只把逻辑值折算成物理值再写系统。
            int target = logical > 9 ? 9 : logical;
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
