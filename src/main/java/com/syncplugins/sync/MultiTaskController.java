package com.syncplugins.sync;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.config.StateStore;
import com.syncplugins.git.SyncOutcome;
import com.syncplugins.workspace.BackupRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MultiTaskController implements AutoCloseable {
    private final Path statesDirectory;
    private final Path tasksDirectory;
    private final Path secretFile;
    private final Path knownHostsFile;
    private final Path backupsDirectory;
    private final Map<String, Runtime> runtimes = new LinkedHashMap<>();
    private final List<Consumer<List<TaskSnapshot>>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean shuttingDown;

    public MultiTaskController(Path tasksDirectory, Path statesDirectory, Path secretFile,
                               Path knownHostsFile, Path backupsDirectory) {
        this.tasksDirectory = tasksDirectory;
        this.statesDirectory = statesDirectory;
        this.secretFile = secretFile;
        this.knownHostsFile = knownHostsFile;
        this.backupsDirectory = backupsDirectory;
    }

    public synchronized void start(List<AppConfig> tasks) {
        for (AppConfig config : tasks) {
            addOrUpdate(config, false);
        }
        publish();
    }

    public synchronized void addOrUpdate(AppConfig config, boolean publish) {
        Runtime existing = runtimes.remove(config.taskId);
        if (existing != null) {
            existing.controller.close();
        }
        AppState state = loadState(config.taskId);
        SyncController controller = new SyncController(
                tasksDirectory.resolve(config.taskId + ".json"),
                statesDirectory.resolve(config.taskId + ".json"),
                secretFile,
                knownHostsFile,
                backupsDirectory.resolve(config.taskId));
        Runtime runtime = new Runtime(config.copy(), controller);
        runtimes.put(config.taskId, runtime);
        controller.addListener(status -> {
            runtime.status = status;
            publish();
        });
        controller.start(config.copy(), state);
        if (publish) {
            publish();
        }
    }

    public synchronized void removeTask(String taskId) {
        Runtime runtime = runtimes.remove(taskId);
        if (runtime != null) {
            runtime.controller.close();
        }
        publish();
    }

    public synchronized TaskSnapshot task(String taskId) {
        Runtime runtime = runtimes.get(taskId);
        return runtime == null ? null : snapshot(runtime);
    }

    public synchronized SyncController taskController(String taskId) {
        Runtime runtime = runtimes.get(taskId);
        if (runtime == null) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        return runtime.controller;
    }

    public List<TaskSnapshot> snapshots() {
        synchronized (this) {
            return runtimes.values().stream()
                    .map(this::snapshot)
                    .sorted(Comparator.comparing(snapshot -> snapshot.config().taskName,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public void addListener(Consumer<List<TaskSnapshot>> listener) {
        listeners.add(listener);
        listener.accept(snapshots());
    }

    public void syncAll() {
        runtimes.values().forEach(runtime -> runtime.controller.syncNow());
    }

    public void pauseAll(boolean paused) {
        runtimes.values().forEach(runtime -> runtime.controller.setPaused(paused));
    }

    public CompletableFuture<?> downloadFromCloud(String taskId) {
        Runtime runtime = runtimes.get(taskId);
        return runtime == null
                ? CompletableFuture.failedFuture(new IllegalArgumentException("任务不存在"))
                : runtime.controller.downloadFromCloud();
    }

    public List<BackupRecord> backups(String taskId) {
        Runtime runtime = runtimes.get(taskId);
        return runtime == null ? List.of() : runtime.controller.backups();
    }

    public CompletableFuture<Void> restoreBackup(String taskId, BackupRecord record) {
        Runtime runtime = runtimes.get(taskId);
        return runtime == null
                ? CompletableFuture.failedFuture(new IllegalArgumentException("任务不存在"))
                : runtime.controller.restoreBackup(record);
    }

    public void deleteBackup(String taskId, BackupRecord record) {
        Runtime runtime = runtimes.get(taskId);
        if (runtime != null) {
            runtime.controller.deleteBackup(record);
        }
    }

    public void shutdownGracefully() {
        shuttingDown = true;
        List<CompletableFuture<SyncOutcome>> futures = new ArrayList<>();
        for (Runtime runtime : List.copyOf(runtimes.values())) {
            futures.add(runtime.controller.syncNow());
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Uncommitted files remain local and are queued again on next start.
        }
        close();
    }

    private AppState loadState(String taskId) {
        try {
            return new StateStore(statesDirectory.resolve(taskId + ".json")).load();
        } catch (RuntimeException exception) {
            return new AppState();
        }
    }

    private TaskSnapshot snapshot(Runtime runtime) {
        return new TaskSnapshot(runtime.config.copy(), runtime.status);
    }

    private void publish() {
        List<TaskSnapshot> snapshots = snapshots();
        for (Consumer<List<TaskSnapshot>> listener : listeners) {
            listener.accept(snapshots);
        }
    }

    @Override
    public synchronized void close() {
        shuttingDown = true;
        runtimes.values().forEach(runtime -> runtime.controller.close());
        runtimes.clear();
    }

    private static final class Runtime {
        private final AppConfig config;
        private final SyncController controller;
        private volatile SyncStatus status = new SyncStatus(
                false, false, false, false, false,
                "", "", "正在启动", "", 0, 0, null,
                false, 0, 0, "", 0, 0, false, false);

        private Runtime(AppConfig config, SyncController controller) {
            this.config = config;
            this.controller = controller;
        }
    }
}
