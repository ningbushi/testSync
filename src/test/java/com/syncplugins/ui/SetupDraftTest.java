package com.syncplugins.ui;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.InputMemory;
import com.syncplugins.config.SetupDraft;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetupDraftTest {
    @Test
    void newTaskNeverInheritsExistingTaskId() {
        AppConfig existing = completeTask("existing-id");
        InputMemory memory = new InputMemory();
        memory.draft = SetupDraft.from(existing);

        AppConfig newTask = new AppConfig();
        SetupDraft draft = SetupDialog.draftForSetup(newTask, memory);

        assertThat(draft.taskId).isEmpty();
        assertThat(draft.taskName).isEmpty();
        assertThat(draft.repositoryPath).isEqualTo(existing.repositoryPath);
    }

    @Test
    void editingExistingTaskPreservesTaskIdentity() {
        AppConfig existing = completeTask("existing-id");

        SetupDraft draft = SetupDialog.draftForSetup(existing, new InputMemory());

        assertThat(draft.taskId).isEqualTo("existing-id");
        assertThat(draft.taskName).isEqualTo("Existing");
    }

    private static AppConfig completeTask(String taskId) {
        AppConfig config = new AppConfig();
        config.taskId = taskId;
        config.taskName = "Existing";
        config.repositoryPath = "W:\\repo";
        config.remoteUrl = "git@github.com:owner/repo.git";
        config.branch = "main";
        config.privateKeyPath = "W:\\key";
        config.authorName = "User";
        config.authorEmail = "user@example.com";
        return config;
    }
}
