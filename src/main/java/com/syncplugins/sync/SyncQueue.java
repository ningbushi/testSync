package com.syncplugins.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SyncQueue {
    private final Map<String, PendingChange> changes = new LinkedHashMap<>();

    public synchronized void merge(Collection<PendingChange> incoming) {
        for (PendingChange change : incoming) {
            PendingChange existing = changes.get(change.relativePath());
            if (existing == null) {
                changes.put(change.relativePath(), change);
            } else if (existing.kind() != change.kind()
                    || existing.modifiedAt() != change.modifiedAt()) {
                existing.update(change.kind(), change.modifiedAt());
            }
        }
    }

    public synchronized PendingChange nextReady(long now, long stableMillis) {
        return changes.values().stream()
                .filter(change -> !change.hasFailed())
                .filter(change -> now - change.lastMutationAt() >= stableMillis)
                .min(PendingChange.ORDER)
                .orElse(null);
    }

    public synchronized void complete(String relativePath) {
        changes.remove(relativePath);
    }

    public synchronized void markFailed(String relativePath, String message) {
        PendingChange change = changes.get(relativePath);
        if (change != null) {
            change.markFailed(message);
        }
    }

    public synchronized int size() {
        return changes.size();
    }

    public synchronized int failedCount() {
        return (int) changes.values().stream().filter(PendingChange::hasFailed).count();
    }

    public synchronized List<PendingChange> snapshot() {
        List<PendingChange> result = new ArrayList<>(changes.values());
        result.sort(PendingChange.ORDER);
        return result;
    }

    public synchronized void clear() {
        changes.clear();
    }
}

