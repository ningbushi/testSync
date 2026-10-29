package com.syncplugins.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BackupRecord(
        String id,
        long createdAt,
        String localPath,
        String originalBranch,
        String originalHead,
        String gitBackupRef,
        boolean fileBackup) {
}

