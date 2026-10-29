package com.syncplugins.git;

public final class SyncException extends Exception {
    private final SyncFailure failure;

    public SyncException(SyncFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public SyncException(SyncFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public SyncFailure failure() {
        return failure;
    }
}

