package com.inkcontrol.core;

/** PID 可能复用，必须连同进程开始时间、模块版本和修订号一起确认。 */
public final class RuntimeIdentity {
    public static boolean matches(int pid, String start, String version, long revision,
            String currentPid, String currentStart, String expectedVersion, long expectedRevision) {
        return pid > 0 && start != null && !start.isEmpty() && String.valueOf(pid).equals(currentPid)
                && start.equals(currentStart) && expectedVersion.equals(version) && revision == expectedRevision;
    }
    private RuntimeIdentity() {}
}
