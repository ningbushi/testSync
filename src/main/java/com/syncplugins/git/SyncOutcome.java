package com.syncplugins.git;

public record SyncOutcome(Status status, int changedFiles, String message) {
    public enum Status {
        NO_CHANGES,
        PUSHED,
        COMMITTED_PENDING
    }
}

