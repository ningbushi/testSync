package com.syncplugins.sync;

import com.syncplugins.git.SyncFailure;

public record SyncStatus(
        boolean watching,
        boolean paused,
        boolean syncing,
        boolean pendingPush,
        boolean halted,
        String branch,
        String repositoryPath,
        String message,
        String lastError,
        long lastSuccessAt,
        long lastCommitAt,
        SyncFailure failure,
        boolean remoteUpdateAvailable,
        int queueSize,
        int failedCount,
        String currentFile,
        int progressDone,
        int progressTotal,
        boolean downloading,
        boolean restoring) {
}

