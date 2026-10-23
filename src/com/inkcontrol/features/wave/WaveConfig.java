package com.inkcontrol.features.wave;

import com.inkcontrol.core.Configuration;

/** 体感息屏唤醒的运行时快照；只解释配置，不执行。 */
final class WaveConfig {
    final boolean enabled, lock, wake;
    final int coverDelay;
    private WaveConfig(boolean enabled, boolean lock, boolean wake, int coverDelay) {
        this.enabled = enabled; this.lock = lock; this.wake = wake; this.coverDelay = coverDelay;
    }
    static WaveConfig from(Configuration config) {
        return new WaveConfig(
                config.enabled("wave.master"),
                config.enabled("wave.lock"),
                config.enabled("wave.wake"),
                number(config.value("wave.coverDelay"), 1000));
    }
    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException error) { return fallback; }
    }
}
