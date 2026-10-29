package com.syncplugins.system;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class AutoStartManager {
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "SyncPlugins";

    public boolean setEnabled(boolean enabled) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return false;
        }
        if (!enabled) {
            run("reg.exe", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
            return true;
        }
        Path launcher = installedLauncher();
        if (launcher == null) {
            return false;
        }
        String command = "\"" + launcher + "\" --background";
        Process process = new ProcessBuilder(
                "reg.exe", "add", RUN_KEY, "/v", VALUE_NAME,
                "/t", "REG_SZ", "/d", command, "/f")
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("写入开机启动项超时");
            }
            if (process.exitValue() != 0) {
                throw new IOException("写入开机启动项失败，退出码 " + process.exitValue());
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("写入开机启动项被中断", exception);
        }
    }

    public boolean isSupportedInstallation() {
        return installedLauncher() != null;
    }

    private static Path installedLauncher() {
        String jpackagePath = System.getProperty("jpackage.app-path");
        String command = jpackagePath;
        if (command == null || command.isBlank()) {
            command = ProcessHandle.current().info().command().orElse("");
        }
        if (command.isBlank()) {
            return null;
        }
        String executable = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        if (executable.equals("java.exe") || executable.equals("javaw.exe")) {
            return null;
        }
        return Path.of(command).toAbsolutePath().normalize();
    }

    private static void run(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("更新开机启动项超时");
            }
            if (process.exitValue() != 0 && process.exitValue() != 1) {
                throw new IOException("更新开机启动项失败，退出码 " + process.exitValue());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("更新开机启动项被中断", exception);
        }
    }
}

