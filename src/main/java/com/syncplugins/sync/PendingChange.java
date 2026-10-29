package com.syncplugins.sync;

import java.nio.file.Path;
import java.util.Comparator;

public final class PendingChange {
    public static final Comparator<PendingChange> ORDER = Comparator
            .comparingLong(PendingChange::orderTime)
            .thenComparing(PendingChange::relativePath);

    private final String relativePath;
    private final long orderTime;
    private volatile ChangeKind kind;
    private volatile long modifiedAt;
    private volatile long lastMutationAt;
    private volatile int retries;
    private volatile String failure;

    public PendingChange(String relativePath, ChangeKind kind, long orderTime, long modifiedAt) {
        this.relativePath = relativePath.replace('\\', '/');
        this.kind = kind;
        this.orderTime = orderTime;
        this.modifiedAt = modifiedAt;
        this.lastMutationAt = modifiedAt;
    }

    public String relativePath() {
        return relativePath;
    }

    public ChangeKind kind() {
        return kind;
    }

    public long orderTime() {
        return orderTime;
    }

    public long modifiedAt() {
        return modifiedAt;
    }

    public long lastMutationAt() {
        return lastMutationAt;
    }

    public int retries() {
        return retries;
    }

    public String failure() {
        return failure;
    }

    public void update(ChangeKind newKind, long newModifiedAt) {
        kind = newKind;
        modifiedAt = newModifiedAt;
        lastMutationAt = System.currentTimeMillis();
        failure = null;
        retries = 0;
    }

    public void markFailed(String message) {
        retries++;
        failure = message;
    }

    public boolean hasFailed() {
        return failure != null && !failure.isBlank();
    }

    public String displayPath() {
        return relativePath;
    }

    public Path resolve(Path root) {
        return root.resolve(relativePath).normalize();
    }
}

