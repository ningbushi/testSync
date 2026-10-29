package com.syncplugins.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class TaskRepository {
    private final Path tasksDirectory;
    private final Path legacyConfigFile;

    public TaskRepository(Path tasksDirectory, Path legacyConfigFile) {
        this.tasksDirectory = tasksDirectory;
        this.legacyConfigFile = legacyConfigFile;
    }

    public synchronized List<AppConfig> loadAll() throws IOException {
        Files.createDirectories(tasksDirectory);
        migrateLegacyIfNeeded();
        List<AppConfig> tasks = new ArrayList<>();
        try (Stream<Path> files = Files.list(tasksDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    AppConfig config = new ConfigStore(file).load();
                    if (config.taskId == null || config.taskId.isBlank()) {
                        config.taskId = file.getFileName().toString().replaceFirst("\\.json$", "");
                    }
                    if (config.taskName == null || config.taskName.isBlank()) {
                        config.taskName = defaultName(config);
                    }
                    tasks.add(config);
                } catch (RuntimeException ignored) {
                    // Ignore a damaged task file and keep other tasks running.
                }
            }
        }
        tasks.sort(Comparator.comparing(task -> task.taskName, String.CASE_INSENSITIVE_ORDER));
        return tasks;
    }

    public synchronized AppConfig saveNew(AppConfig source) throws IOException {
        AppConfig config = source.copy();
        if (config.taskId == null || config.taskId.isBlank()) {
            config.taskId = UUID.randomUUID().toString();
        }
        if (config.taskName == null || config.taskName.isBlank()) {
            config.taskName = defaultName(config);
        }
        save(config);
        return config;
    }

    public synchronized void save(AppConfig config) throws IOException {
        Files.createDirectories(tasksDirectory);
        if (config.taskId == null || config.taskId.isBlank()) {
            config.taskId = UUID.randomUUID().toString();
        }
        if (config.taskName == null || config.taskName.isBlank()) {
            config.taskName = defaultName(config);
        }
        new ConfigStore(configFile(config.taskId)).save(config);
    }

    public synchronized void delete(String taskId) throws IOException {
        Files.deleteIfExists(configFile(taskId));
    }

    public synchronized void saveAll(List<AppConfig> tasks) throws IOException {
        for (AppConfig task : tasks) {
            save(task);
        }
    }

    public Path configFile(String taskId) {
        if (taskId == null || !taskId.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("任务 ID 无效");
        }
        return tasksDirectory.resolve(taskId + ".json");
    }

    public Path tasksDirectory() {
        return tasksDirectory;
    }

    private void migrateLegacyIfNeeded() throws IOException {
        try (Stream<Path> files = Files.list(tasksDirectory)) {
            if (files.anyMatch(path -> path.getFileName().toString().endsWith(".json"))) {
                return;
            }
        }
        if (!Files.isRegularFile(legacyConfigFile)) {
            return;
        }
        AppConfig legacy = new ConfigStore(legacyConfigFile).load();
        if (!legacy.isComplete()) {
            return;
        }
        legacy.taskName = defaultName(legacy);
        saveNew(legacy);
    }

    private static String defaultName(AppConfig config) {
        String path = config.repositoryPath == null ? "" : config.repositoryPath;
        Path fileName = path.isBlank() ? null : Path.of(path).getFileName();
        return fileName == null ? "同步任务" : fileName.toString();
    }
}
