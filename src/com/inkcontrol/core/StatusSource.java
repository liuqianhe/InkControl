package com.inkcontrol.core;

public interface StatusSource {
    /** 必须基于当前进程/启动身份核实；null 表示无法确认。 */
    Evidence evidence(FeatureDefinition feature);
    final class Evidence {
        public final boolean enabled;
        public final String value, error;
        public final long revision;
        public Evidence(boolean enabled, String value, String error) {
            this(enabled, value, error, -1);
        }
        public Evidence(boolean enabled, String value, String error, long revision) {
            this.enabled = enabled;
            this.value = value;
            this.error = error;
            this.revision = revision;
        }
    }
}
