package com.syncplugins.workspace;

import java.nio.file.Path;
import java.util.List;

public record WorkspaceInspection(
        Path localPath,
        String remoteUrl,
        WorkspaceMode mode,
        String branch,
        String baseBranch,
        List<String> remoteBranches,
        List<String> localOnlyFiles,
        List<String> remoteOnlyFiles,
        List<String> conflictingFiles,
        String remoteHead) {
    public boolean createsBranch() {
        return !remoteBranches.contains(branch);
    }
}

