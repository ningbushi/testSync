package com.syncplugins.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppState {
    public int schemaVersion = 1;
    public String lastRemoteCommit;
    public boolean pendingPush;
    public long lastSuccessAt;
    public long lastCommitAt;
    public String lastError = "";

    public AppState copy() {
        AppState copy = new AppState();
        copy.schemaVersion = schemaVersion;
        copy.lastRemoteCommit = lastRemoteCommit;
        copy.pendingPush = pendingPush;
        copy.lastSuccessAt = lastSuccessAt;
        copy.lastCommitAt = lastCommitAt;
        copy.lastError = lastError;
        return copy;
    }
}

