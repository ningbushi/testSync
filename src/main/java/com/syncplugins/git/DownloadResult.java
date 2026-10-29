package com.syncplugins.git;

import com.syncplugins.workspace.BackupRecord;

public record DownloadResult(boolean changed, String message, BackupRecord backup) {
}

