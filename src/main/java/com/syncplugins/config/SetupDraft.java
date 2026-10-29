package com.syncplugins.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class SetupDraft {
    public String repositoryPath = "";
    public String remoteUrl = "";
    public String branch = "";
    public String privateKeyPath = "";
    public String authorName = "";
    public String authorEmail = "";
    public boolean autoStart = true;
    public boolean startMinimized = true;
    public long pollIntervalSeconds = 30;

    public static SetupDraft from(AppConfig config) {
        SetupDraft draft = new SetupDraft();
        if (config != null) {
            draft.repositoryPath = config.repositoryPath;
            draft.remoteUrl = config.remoteUrl;
            draft.branch = config.branch;
            draft.privateKeyPath = config.privateKeyPath;
            draft.authorName = config.authorName;
            draft.authorEmail = config.authorEmail;
            draft.autoStart = config.autoStart;
            draft.startMinimized = config.startMinimized;
            draft.pollIntervalSeconds = config.pollIntervalSeconds;
        }
        return draft;
    }

    public void applyTo(AppConfig config) {
        config.repositoryPath = nonNull(repositoryPath);
        config.remoteUrl = nonNull(remoteUrl);
        config.branch = nonNull(branch);
        config.privateKeyPath = nonNull(privateKeyPath);
        config.authorName = nonNull(authorName);
        config.authorEmail = nonNull(authorEmail);
        config.autoStart = autoStart;
        config.startMinimized = startMinimized;
        config.pollIntervalSeconds = pollIntervalSeconds;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}

