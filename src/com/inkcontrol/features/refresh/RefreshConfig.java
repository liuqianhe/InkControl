package com.inkcontrol.features.refresh;

import com.inkcontrol.core.Configuration;

/** 自动刷新的运行时快照；只解释配置，不执行。 */
final class RefreshConfig {
    final boolean enabled, touch, key, reading;
    final int interval, debounce, delay, period;
    private RefreshConfig(boolean enabled, boolean touch, boolean key, boolean reading, int interval, int debounce, int delay, int period) {
        this.enabled = enabled; this.touch = touch; this.key = key; this.reading = reading;
        this.interval = interval; this.debounce = debounce; this.delay = delay; this.period = period;
    }
    static RefreshConfig from(Configuration config) {
        return new RefreshConfig(
                config.enabled("refresh.master"),
                config.enabled("refresh.touch"),
                config.enabled("refresh.key"),
                config.enabled("refresh.reading"),
                number(config.value("refresh.interval"), 10),
                number(config.value("refresh.debounce"), 1000),
                number(config.value("refresh.delay"), 500),
                number(config.value("refresh.period"), 300));
    }
    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException error) { return fallback; }
    }
}
