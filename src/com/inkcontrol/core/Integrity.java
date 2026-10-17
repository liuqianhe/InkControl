package com.inkcontrol.core;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class Integrity {
    public static void verifySha256(InputStream input, String expected) throws Exception {
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("校验摘要格式错误");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!expected.equals(hex.toString())) throw new SecurityException("内置 ZIP 校验失败，请重新安装可信 APK");
    }
    private Integrity() {}
}
