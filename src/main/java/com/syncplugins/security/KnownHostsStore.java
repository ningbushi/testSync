package com.syncplugins.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class KnownHostsStore {
    private final Path file;

    public KnownHostsStore(Path file) {
        this.file = file;
    }

    public void accept(KnownHostKey key) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = key.host() + " " + key.encodedKey() + System.lineSeparator();
        if (Files.isRegularFile(file)
                && Files.readString(file, StandardCharsets.UTF_8).contains(key.encodedKey())) {
            return;
        }
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Path file() {
        return file;
    }
}

