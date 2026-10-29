package com.syncplugins.git;

import java.nio.file.Path;

public record RepositoryInfo(
        Path workTree,
        String branch,
        String headCommit,
        String suggestedRemoteUrl,
        String authorName,
        String authorEmail) {
}

