package com.syncplugins.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppConfig {
    public int schemaVersion = 2;
    public String taskId = "";
    public String taskName = "";
    public String repositoryPath = "";
    public String remoteUrl = "";
    public String branch = "";
    public String privateKeyPath = "";
    public String authorName = "";
    public String authorEmail = "";
    public boolean autoStart = true;
    public boolean startMinimized = true;
    public boolean paused = false;
    public long debounceMillis = 3_000;
    public long maxDebounceMillis = 30_000;
    public long pollIntervalSeconds = 30;
    public int backupLimit = 3;

    @JsonIgnore
    public boolean isComplete() {
        return !repositoryPath.isBlank()
                && !remoteUrl.isBlank()
                && !branch.isBlank()
                && !privateKeyPath.isBlank()
                && !authorName.isBlank()
                && !authorEmail.isBlank();
    }

    public AppConfig copy() {
        AppConfig copy = new AppConfig();
        copy.schemaVersion = schemaVersion;
        copy.taskId = taskId;
        copy.taskName = taskName;
        copy.repositoryPath = repositoryPath;
        copy.remoteUrl = remoteUrl;
        copy.branch = branch;
        copy.privateKeyPath = privateKeyPath;
        copy.authorName = authorName;
        copy.authorEmail = authorEmail;
        copy.autoStart = autoStart;
        copy.startMinimized = startMinimized;
        copy.paused = paused;
        copy.debounceMillis = debounceMillis;
        copy.maxDebounceMillis = maxDebounceMillis;
        copy.pollIntervalSeconds = pollIntervalSeconds;
        copy.backupLimit = backupLimit;
        return copy;
    }

    public void validate() {
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        if (taskId != null && !taskId.isBlank()
                && !taskId.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("任务 ID 包含无效字符");
        }
        if (debounceMillis < 500 || debounceMillis > 60_000) {
            throw new IllegalArgumentException("同步防抖时间必须在 0.5 到 60 秒之间");
        }
        if (maxDebounceMillis < debounceMillis || maxDebounceMillis > 300_000) {
            throw new IllegalArgumentException("最大等待时间必须大于防抖时间且不超过 5 分钟");
        }
        if (pollIntervalSeconds < 1 || pollIntervalSeconds > 3_600) {
            throw new IllegalArgumentException("检查间隔必须在 1 秒到 1 小时之间");
        }
        if (backupLimit < 1 || backupLimit > 10) {
            throw new IllegalArgumentException("备份数量必须在 1 到 10 之间");
        }
    }
}

