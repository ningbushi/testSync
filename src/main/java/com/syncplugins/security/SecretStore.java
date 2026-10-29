package com.syncplugins.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SecretStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path secretFile;

    public SecretStore(Path secretFile) {
        this.secretFile = secretFile;
    }

    public void savePassphrase(char[] passphrase) throws IOException {
        savePassphrase("", passphrase);
    }

    public void savePassphrase(String privateKeyPath, char[] passphrase) throws IOException {
        Map<String, String> values = readAll();
        String key = normalize(privateKeyPath);
        if (passphrase == null || passphrase.length == 0) {
            values.remove(key);
        } else {
            values.put(key, new String(passphrase));
        }
        if (values.isEmpty()) {
            clear();
            return;
        }
        requireWindows();
        byte[] clearText = MAPPER.writeValueAsBytes(values);
        byte[] encrypted = Crypt32Util.cryptProtectData(clearText);
        writeAtomically(encrypted);
        java.util.Arrays.fill(clearText, (byte) 0);
    }

    public Optional<char[]> loadPassphrase() throws IOException {
        return loadPassphrase("");
    }

    public Optional<char[]> loadPassphrase(String privateKeyPath) throws IOException {
        Map<String, String> values = readAll();
        String normalized = normalize(privateKeyPath);
        String value = values.get(normalized);
        if (value == null && !normalized.isEmpty()) {
            value = values.get("");
        }
        return Optional.ofNullable(value)
                .filter(stored -> !stored.isEmpty())
                .map(String::toCharArray);
    }

    private Map<String, String> readAll() throws IOException {
        if (!Files.isRegularFile(secretFile)) {
            return new LinkedHashMap<>();
        }
        requireWindows();
        byte[] encrypted = Files.readAllBytes(secretFile);
        byte[] clearText = Crypt32Util.cryptUnprotectData(encrypted);
        try {
            try {
                Map<String, String> values = MAPPER.readValue(clearText,
                        new TypeReference<LinkedHashMap<String, String>>() {
                        });
                return values == null ? new LinkedHashMap<>() : values;
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                String legacy = new String(clearText, StandardCharsets.UTF_8);
                Map<String, String> migrated = new LinkedHashMap<>();
                if (!legacy.isEmpty()) {
                    migrated.put("", legacy);
                }
                return migrated;
            }
        } finally {
            java.util.Arrays.fill(clearText, (byte) 0);
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(secretFile);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void writeAtomically(byte[] data) throws IOException {
        Path parent = secretFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = secretFile.resolveSibling(secretFile.getFileName() + ".tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, secretFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, secretFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requireWindows() throws IOException {
        if (!Platform.isWindows()) {
            throw new IOException("DPAPI 私钥口令存储仅支持 Windows");
        }
    }
}

