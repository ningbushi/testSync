package com.syncplugins.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskRepositoryTest {
    @TempDir
    Path temp;

    @Test
    void migratesLegacySingleTaskConfiguration() throws Exception {
        Path legacy = temp.resolve("config.json");
        AppConfig config = new AppConfig();
        config.repositoryPath = "W:\\legacy";
        config.remoteUrl = "git@github.com:owner/repo.git";
        config.branch = "main";
        config.privateKeyPath = "W:\\key";
        config.authorName = "User";
        config.authorEmail = "user@example.com";
        new ConfigStore(legacy).save(config);

        TaskRepository repository = new TaskRepository(temp.resolve("tasks"), legacy);
        List<AppConfig> tasks = repository.loadAll();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).taskId).isNotBlank();
        assertThat(tasks.get(0).taskName).isEqualTo("legacy");
        assertThat(Files.isRegularFile(repository.configFile(tasks.get(0).taskId))).isTrue();
    }

    @Test
    void storesMultipleIndependentTasks() throws Exception {
        TaskRepository repository = new TaskRepository(
                temp.resolve("multi-tasks"), temp.resolve("missing-legacy.json"));
        AppConfig first = valid("first", "W:\\first");
        AppConfig second = valid("second", "W:\\second");

        AppConfig savedFirst = repository.saveNew(first);
        AppConfig savedSecond = repository.saveNew(second);

        assertThat(savedFirst.taskId).isNotEqualTo(savedSecond.taskId);
        assertThat(repository.loadAll()).hasSize(2);
    }

    @Test
    void rejectsDuplicateLocalFolder() throws Exception {
        TaskRepository repository = new TaskRepository(
                temp.resolve("duplicate-tasks"), temp.resolve("missing.json"));
        repository.saveNew(valid("first", "W:\\same-folder"));

        assertThatThrownBy(() -> repository.saveNew(valid("second", "W:\\same-folder")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("已被任务");
    }

    @Test
    void persistsPerTaskScanIntervalAndMode() throws Exception {
        TaskRepository repository = new TaskRepository(
                temp.resolve("interval-tasks"), temp.resolve("none.json"));
        AppConfig config = valid("interval", "W:\\interval");
        config.pollIntervalSeconds = 777;
        config.syncMode = SyncMode.REALTIME;

        AppConfig saved = repository.saveNew(config);
        AppConfig loaded = repository.loadAll().get(0);

        assertThat(saved.taskId).isEqualTo(loaded.taskId);
        assertThat(loaded.pollIntervalSeconds).isEqualTo(777);
        assertThat(loaded.syncMode).isEqualTo(SyncMode.REALTIME);
    }

    private static AppConfig valid(String name, String path) {
        AppConfig config = new AppConfig();
        config.taskName = name;
        config.repositoryPath = path;
        config.remoteUrl = "git@github.com:owner/" + name + ".git";
        config.branch = "main";
        config.privateKeyPath = "W:\\key";
        config.authorName = "User";
        config.authorEmail = "user@example.com";
        return config;
    }
}

