package com.syncplugins.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathUtil {
    private PathUtil() {
    }

    public static Path requireDirectory(String value, String label) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(label + "不能为空");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException(label + "不存在或不是文件夹：" + path);
        }
        return path.toRealPath();
    }

    public static Path requireFile(String value, String label) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(label + "不能为空");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException(label + "不存在或不是文件：" + path);
        }
        return path.toRealPath();
    }

    public static String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
