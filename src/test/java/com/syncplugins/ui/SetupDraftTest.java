package com.syncplugins.ui;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.InputMemory;
import com.syncplugins.config.SetupDraft;
import com.syncplugins.config.SyncMode;
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
        assertThat(draft.syncMode).isEqualTo(SyncMode.REALTIME);
    }

    @Test
    void draftRoundTripPreservesScheduledInterval() {
        AppConfig config = completeTask("interval-id");
        config.pollIntervalSeconds = 420;
        config.syncMode = SyncMode.SCHEDULED;

        SetupDraft draft = SetupDraft.from(config);
        AppConfig restored = new AppConfig();
        draft.applyTo(restored);

        assertThat(restored.pollIntervalSeconds).isEqualTo(420);
        assertThat(restored.syncMode).isEqualTo(SyncMode.SCHEDULED);
    }

    @Test
    void parsesUserEnteredPollingIntervalAndDetectsMetadataOnlyEdit() {
        AppConfig existing = completeTask("same-id");
        AppConfig candidate = existing.copy();
        candidate.pollIntervalSeconds = SetupDialog.parsePollInterval("777");

        assertThat(candidate.pollIntervalSeconds).isEqualTo(777);
        assertThat(SetupDialog.sameWorkspaceBinding(existing, candidate)).isTrue();
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
        config.syncMode = SyncMode.REALTIME;
        return config;
    }
}
