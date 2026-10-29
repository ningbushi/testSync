package com.syncplugins.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class InputMemoryStore {
    public static final int LIMIT = 10;
    private final Path path;

    public InputMemoryStore(Path path) {
        this.path = path;
    }

    public InputMemory load() {
        try {
            InputMemory memory = JsonFileStore.read(path, InputMemory.class, new InputMemory());
            if (memory.draft == null) {
                memory.draft = new SetupDraft();
            }
            normalize(memory);
            return memory;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取输入历史：" + path + "；" + exception.getMessage(), exception);
        }
    }

    public void save(InputMemory memory) throws IOException {
        normalize(memory);
        JsonFileStore.write(path, memory);
    }

    public void remember(InputMemory memory, SetupDraft draft) {
        memory.draft = draft;
        rememberValue(memory.repositoryPaths, draft.repositoryPath, true);
        rememberValue(memory.remoteUrls, draft.remoteUrl, true);
        rememberValue(memory.branches, draft.branch, false);
        rememberValue(memory.privateKeyPaths, draft.privateKeyPath, true);
        rememberValue(memory.authorNames, draft.authorName, false);
        rememberValue(memory.authorEmails, draft.authorEmail, false);
    }

    public void clear(InputMemory memory) {
        memory.draft = new SetupDraft();
        memory.draftPending = false;
        memory.draftUpdatedAt = 0;
        memory.repositoryPaths.clear();
        memory.remoteUrls.clear();
        memory.branches.clear();
        memory.privateKeyPaths.clear();
        memory.authorNames.clear();
        memory.authorEmails.clear();
    }

    private static void normalize(InputMemory memory) {
        memory.repositoryPaths = normalizeList(memory.repositoryPaths, true);
        memory.remoteUrls = normalizeList(memory.remoteUrls, true);
        memory.branches = normalizeList(memory.branches, false);
        memory.privateKeyPaths = normalizeList(memory.privateKeyPaths, true);
        memory.authorNames = normalizeList(memory.authorNames, false);
        memory.authorEmails = normalizeList(memory.authorEmails, false);
    }

    private static List<String> normalizeList(List<String> source, boolean ignoreCase) {
        List<String> result = new ArrayList<>();
        if (source != null) {
            for (String value : source) {
                if (value != null && !value.isBlank()) {
                    rememberValue(result, value.trim(), ignoreCase);
                }
            }
        }
        return result;
    }

    private static void rememberValue(List<String> values, String raw, boolean ignoreCase) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        values.removeIf(existing -> ignoreCase
                ? existing.toLowerCase(Locale.ROOT).equals(value.toLowerCase(Locale.ROOT))
                : existing.equals(value));
        values.add(0, value);
        while (values.size() > LIMIT) {
            values.remove(values.size() - 1);
        }
    }

    public Path path() {
        return path;
    }
}
