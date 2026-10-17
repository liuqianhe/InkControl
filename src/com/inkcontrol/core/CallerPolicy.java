package com.inkcontrol.core;

/** Provider 的调用权限规则。调用方身份由 Binder 与系统包管理器提供。 */
public final class CallerPolicy {
    public static boolean read(int caller, int self, int systemui, int launcher) {
        return caller == self || caller == 1000 || systemui >= 0 && caller == systemui || launcher >= 0 && caller == launcher;
    }
    public static boolean report(String role, int caller, int systemui, int launcher) {
        return "system".equals(role) && caller == 1000 || "systemui".equals(role) && systemui >= 0 && caller == systemui || "launcher".equals(role) && launcher >= 0 && caller == launcher;
    }
    private CallerPolicy() {}
}
