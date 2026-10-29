package com.syncplugins.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InputMemoryStoreTest {
    @TempDir
    Path temp;

    @Test
    void persistsDraftsAndKeepsMostRecentTenValues() throws Exception {
        InputMemoryStore store = new InputMemoryStore(temp.resolve("ui-state.json"));
        InputMemory memory = store.load();
        for (int index = 0; index < 12; index++) {
            SetupDraft draft = new SetupDraft();
            draft.repositoryPath = "W:\\repo-" + index;
            draft.remoteUrl = "git@github.com:owner/repo.git";
            draft.privateKeyPath = "W:\\keys\\key-" + index;
            draft.authorName = "user-" + index;
            draft.authorEmail = "user-" + index + "@example.com";
            store.remember(memory, draft);
        }
        store.save(memory);

        InputMemory loaded = store.load();
        assertThat(loaded.repositoryPaths).hasSize(10).startsWith("W:\\repo-11");
        assertThat(loaded.privateKeyPaths).hasSize(10);
        assertThat(loaded.draft.authorEmail).isEqualTo("user-11@example.com");
        loaded.draftPending = true;
        loaded.draft.repositoryPath = "W:\\unsaved-folder";
        store.save(loaded);
        assertThat(store.load().draft.repositoryPath).isEqualTo("W:\\unsaved-folder");
    }

    @Test
    void migratesVersionOneConfigToSchemaTwo() throws Exception {
        Path configFile = temp.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "schemaVersion": 1,
                  "repositoryPath": "W:\\\\repo",
                  "remoteUrl": "git@github.com:owner/repo.git",
                  "branch": "main",
                  "privateKeyPath": "W:\\\\key",
                  "authorName": "User",
                  "authorEmail": "user@example.com"
                }
                """);

        AppConfig config = new ConfigStore(configFile).load();

        assertThat(config.schemaVersion).isEqualTo(2);
        assertThat(Files.readString(configFile)).contains("\"schemaVersion\" : 2");
    }
}

