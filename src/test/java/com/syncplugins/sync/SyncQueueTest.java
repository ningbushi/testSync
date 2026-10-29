package com.syncplugins.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncQueueTest {
    @Test
    void ordersByTimeThenPathAndCoalescesRepeatedChanges() {
        SyncQueue queue = new SyncQueue();
        queue.merge(List.of(
                new PendingChange("b.txt", ChangeKind.ADD, 200, 200),
                new PendingChange("a.txt", ChangeKind.ADD, 100, 100),
                new PendingChange("c.txt", ChangeKind.ADD, 100, 100)));

        assertThat(queue.nextReady(2_000, 1_000).relativePath()).isEqualTo("a.txt");
        queue.merge(List.of(new PendingChange("a.txt", ChangeKind.MODIFY, 900, 900)));

        assertThat(queue.snapshot().get(0).relativePath()).isEqualTo("a.txt");
        assertThat(queue.snapshot().get(0).kind()).isEqualTo(ChangeKind.MODIFY);
        assertThat(queue.nextReady(System.currentTimeMillis() + 10_000, 500).relativePath())
                .isEqualTo("a.txt");
    }

    @Test
    void skipsFailedFilesButCountsThem() {
        SyncQueue queue = new SyncQueue();
        queue.merge(List.of(
                new PendingChange("bad.bin", ChangeKind.ADD, 1, 1),
                new PendingChange("good.txt", ChangeKind.ADD, 2, 2)));
        queue.markFailed("bad.bin", "too large");

        assertThat(queue.failedCount()).isEqualTo(1);
        assertThat(queue.nextReady(System.currentTimeMillis() + 10_000, 1).relativePath())
                .isEqualTo("good.txt");
    }
}

