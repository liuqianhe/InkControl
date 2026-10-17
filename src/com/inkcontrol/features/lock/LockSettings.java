package com.inkcontrol.features.lock;

import com.inkcontrol.platform.RuntimeConfig;

/** 旧键名只作为功能适配标识；值全部来自统一配置。 */
final class LockSettings {
    static int read(String key, int fallback) {
        String id;
        switch (key) {
            case "einklyric_delay_ms": id = "lock.delay"; break;
            case "einklyric_first_mode": id = "lock.first"; break;
            case "einklyric_update_mode": id = "lock.update"; break;
            case "einklyric_lyric_mode": id = "lock.lyricmode"; break;
            case "einklyric_statusbar": return RuntimeConfig.enabled("lock.statusbar") ? 1 : 0;
            default: return 0;
        }
        return RuntimeConfig.enabled(id) ? Integer.parseInt(RuntimeConfig.value(id)) : -1;
    }
    private LockSettings() {}
}
