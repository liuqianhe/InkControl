package com.inkcontrol.core;

/** 写入账本只恢复仍为自己写入值的字段，保留用户后续修改。 */
public final class ConditionalRestore {
    public static boolean owns(String current, String written) { return written != null && written.equals(current); }
    public static int stockLockStyle(int candidate) { return candidate >= 0 && candidate <= 4 ? candidate : 0; }
    private ConditionalRestore() {}
}
