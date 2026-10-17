package com.inkcontrol.platform;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.inkcontrol.core.Integrity;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 只运行随 APK 签名分发的固定控制器；用户值通过 stdin 传递。 */
final class RootBridge {
    private final Context context;
    RootBridge(Context context) { this.context = context.createDeviceProtectedStorageContext(); }

    File payload() throws Exception {
        File directory = new File(context.getFilesDir(), "controller-" + BuildInfo.BACKEND_SHA256);
        File marker = new File(directory, "verified");
        if (marker.isFile()) return directory;
        File zip = new File(context.getCacheDir(), "inkcontrol_backend.zip");
        try (InputStream input = context.getAssets().open("inkcontrol_backend.zip")) { Files.copy(input, zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        verify(zip);
        directory.mkdirs();
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!(entry.getName().startsWith("lib/") || entry.getName().equals("bin/inkctl")) || entry.isDirectory()) continue;
                File target = new File(directory, entry.getName());
                if (!target.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new SecurityException("ZIP 路径无效");
                target.getParentFile().mkdirs();
                Files.copy(input, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.write(marker.toPath(), new byte[]{1});
        return directory;
    }
    static void verify(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            Integrity.verifySha256(input, BuildInfo.BACKEND_SHA256);
        }
    }
    String run(String command, String input) throws Exception {
        if (!command.matches("status|apply")) throw new IllegalArgumentException("命令未注册");
        String controller = new File(payload(), "bin/inkctl").getCanonicalPath();
        if (!controller.matches("[a-zA-Z0-9_./-]+")) throw new SecurityException("控制器路径无效");
        Process process;
        try { process = new ProcessBuilder("su", "-c", "/system/bin/sh " + controller + " " + command).redirectErrorStream(true).start(); }
        catch (IOException error) { throw new IOException("未找到 Root（su 不可用）", error); }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) { if (result.size() < 262144) result.write(buffer, 0, count); }
            } catch (IOException ignored) { }
        }, "inkctl-output");
        reader.start();
        try (OutputStream stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Root 授权或操作超时"); }
        reader.join(2000);
        String text = new String(result.toByteArray(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) throw new IOException(text.isEmpty() ? "Root 未授权或控制器不可用" : text);
        return text;
    }
}
