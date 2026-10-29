package com.syncplugins.sync;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.config.ConfigStore;
import com.syncplugins.config.StateStore;
import com.syncplugins.config.SyncMode;
import com.syncplugins.git.CloudSyncService;
import com.syncplugins.git.DownloadResult;
import com.syncplugins.git.GitRepositoryInspector;
import com.syncplugins.git.GitSyncEngine;
import com.syncplugins.git.RemoteCheck;
import com.syncplugins.git.SyncException;
import com.syncplugins.git.SyncFailure;
import com.syncplugins.git.SyncOutcome;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SecretStore;
import com.syncplugins.security.UnknownHostKeyException;
import com.syncplugins.watch.RecursiveDirectoryWatcher;
import com.syncplugins.workspace.BackupManager;
import com.syncplugins.workspace.BackupRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SyncController implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SyncController.class.getName());
    private final ConfigStore configStore;
    private final StateStore stateStore;
    private final SecretStore secretStore;
    private final GitSyncEngine engine;
    private final CloudSyncService cloudSync;
    private final GitRepositoryInspector inspector;
    private final BackupManager backupManager;
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.ExecutorService syncExecutor;
    private final SyncQueue queue = new SyncQueue();
    private final List<Consumer<SyncStatus>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean syncInFlight = new AtomicBoolean();
    private final AtomicBoolean remoteCheckInFlight = new AtomicBoolean();
    private final Object debounceLock = new Object();

    private volatile AppConfig config;
    private volatile AppState state;
    private volatile RecursiveDirectoryWatcher watcher;
    private volatile ScheduledFuture<?> debounceFuture;
    private volatile ScheduledFuture<?> pollFuture;
    private volatile ScheduledFuture<?> networkRetryFuture;
    private volatile boolean halted;
    private volatile boolean rerunRequested;
    private volatile boolean shuttingDown;
    private volatile boolean remoteUpdateAvailable;
    private volatile long lastMutationNanos;
    private volatile String message = "正在启动";
    private volatile String lastError = "";
    private volatile String currentFile = "";
    private volatile int progressDone;
    private volatile int progressTotal;
    private volatile boolean downloading;
    private volatile boolean restoring;
    private volatile SyncFailure lastFailure;
    private volatile int networkRetryCount;

    public SyncController(Path configFile, Path stateFile, Path secretFile,
                          Path knownHostsFile, Path backupsDirectory) {
        this.configStore = new ConfigStore(configFile);
        this.stateStore = new StateStore(stateFile);
        this.secretStore = new SecretStore(secretFile);
        this.inspector = new GitRepositoryInspector();
        this.backupManager = new BackupManager(backupsDirectory);
        this.engine = new GitSyncEngine(inspector, new KnownHostsStore(knownHostsFile));
        this.cloudSync = new CloudSyncService(new KnownHostsStore(knownHostsFile), backupManager);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("syncplugins-scheduler").daemon(true).factory());
        this.syncExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("syncplugins-git").daemon(true).factory());
    }

    public void start(AppConfig initialConfig, AppState initialState) {
        this.config = initialConfig.copy();
        this.state = initialState.copy();
        this.halted = config.paused;
        this.message = config.paused ? "同步已暂停" : "正在启动";
        if (!config.paused) {
            installRealtimeWatcherIfNeeded();
            triggerDrain(false, Duration.ZERO);
        } else {
            publish();
        }
        schedulePolling();
    }

    public void addListener(Consumer<SyncStatus> listener) {
        listeners.add(listener);
        listener.accept(status());
    }

    public void updateConfig(AppConfig updated) {
        AppConfig previous = config;
        try {
            configStore.save(updated);
        } catch (IOException exception) {
            setError(SyncFailure.UNKNOWN, "无法保存配置：" + exception.getMessage());
            return;
        }
        cancelWatcher();
        cancelDebounce();
        cancelPeriodicTasks();
        queue.clear();
        this.config = updated.copy();
        if (previous == null
                || !previous.repositoryPath.equals(updated.repositoryPath)
                || !previous.remoteUrl.equals(updated.remoteUrl)
                || !previous.branch.equals(updated.branch)) {
            state.lastRemoteCommit = null;
            state.pendingPush = false;
            persistStateQuietly();
        }
        this.halted = config.paused;
        this.lastFailure = null;
        this.lastError = "";
        this.remoteUpdateAvailable = false;
        this.message = config.paused ? "同步已暂停" : "配置已更新";
        schedulePolling();
        if (!config.paused) {
            installRealtimeWatcherIfNeeded();
            triggerDrain(false, Duration.ZERO);
        } else {
            publish();
        }
    }

    public void setPaused(boolean paused) {
        AppConfig updated = config.copy();
        updated.paused = paused;
        try {
            configStore.save(updated);
        } catch (IOException exception) {
            setError(SyncFailure.UNKNOWN, "无法保存暂停状态：" + exception.getMessage());
            return;
        }
        config = updated;
        halted = paused;
        if (paused) {
            cancelWatcher();
            cancelDebounce();
            cancelPeriodicTasks();
            message = "同步已暂停";
            publish();
        } else {
            message = "正在恢复同步";
            schedulePolling();
            installRealtimeWatcherIfNeeded();
            triggerDrain(false, Duration.ZERO);
        }
    }

    public void resumeAfterFailure() {
        halted = false;
        lastFailure = null;
        lastError = "";
        setPaused(false);
    }

    public CompletableFuture<SyncOutcome> syncNow() {
        return triggerDrain(true, Duration.ZERO);
    }

    public CompletableFuture<DownloadResult> downloadFromCloud() {
        CompletableFuture<DownloadResult> completion = new CompletableFuture<>();
        if (config == null) {
            completion.completeExceptionally(new IllegalStateException("配置尚未加载"));
            return completion;
        }
        if (!syncInFlight.compareAndSet(false, true)) {
            completion.completeExceptionally(new IllegalStateException("当前已有同步任务运行"));
            return completion;
        }
        syncInFlight.set(true);
        syncExecutor.submit(() -> {
            char[] passphrase = new char[0];
            try {
                passphrase = loadPassphrase();
                downloading = true;
                progressDone = 0;
                progressTotal = 0;
                message = "正在从云端下载";
                publish();
                DownloadResult result = cloudSync.download(config.copy(), state, passphrase);
                queue.clear();
                rerunRequested = false;
                remoteUpdateAvailable = false;
                message = result.message();
                lastError = "";
                lastFailure = null;
                persistStateQuietly();
                completion.complete(result);
            } catch (SyncException exception) {
                lastFailure = exception.failure();
                lastError = exception.getMessage();
                message = "云端下载失败";
                completion.completeExceptionally(exception);
            } catch (UnknownHostKeyException exception) {
                lastFailure = SyncFailure.AUTHENTICATION;
                lastError = exception.getMessage();
                message = "需要确认 GitHub 主机密钥";
                completion.completeExceptionally(exception);
            } catch (Exception exception) {
                lastFailure = SyncFailure.UNKNOWN;
                lastError = exception.getMessage();
                message = "云端下载失败";
                completion.completeExceptionally(exception);
            } finally {
                Arrays.fill(passphrase, '\0');
                downloading = false;
                syncInFlight.set(false);
                publish();
            }
        });
        return completion;
    }

    public List<BackupRecord> backups() {
        return backupManager.list();
    }

    public CompletableFuture<Void> restoreBackup(BackupRecord record) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (!syncInFlight.compareAndSet(false, true)) {
            completion.completeExceptionally(new IllegalStateException("当前已有任务运行"));
            return completion;
        }
        syncExecutor.submit(() -> {
            try {
                restoring = true;
                progressDone = 0;
                progressTotal = 0;
                message = "正在恢复备份 " + record.id();
                publish();
                backupManager.restore(record);
                queue.clear();
                refreshQueue();
                message = "已恢复备份 " + record.id();
                rerunRequested = true;
                completion.complete(null);
            } catch (Exception exception) {
                completion.completeExceptionally(exception);
            } finally {
                restoring = false;
                syncInFlight.set(false);
                publish();
                if (rerunRequested && !halted && !config.paused && !shuttingDown) {
                    triggerDrain(false, Duration.ZERO);
                }
            }
        });
        return completion;
    }

    public void deleteBackup(BackupRecord record) {
        try {
            backupManager.delete(record);
            message = "已删除备份 " + record.id();
        } catch (IOException exception) {
            setError(SyncFailure.UNKNOWN, "无法删除备份：" + exception.getMessage());
        }
    }

    public SyncStatus status() {
        AppConfig current = config;
        AppState currentState = state;
        return new SyncStatus(
                current != null && !halted && !current.paused
                        && (current.syncMode == SyncMode.SCHEDULED
                        ? pollFuture != null : watcher != null),
                current == null || current.paused,
                syncInFlight.get(),
                currentState != null && currentState.pendingPush,
                halted,
                current == null ? "" : current.branch,
                current == null ? "" : current.repositoryPath,
                message,
                lastError,
                currentState == null ? 0 : currentState.lastSuccessAt,
                currentState == null ? 0 : currentState.lastCommitAt,
                lastFailure,
                remoteUpdateAvailable,
                queue.size(),
                queue.failedCount(),
                currentFile,
                progressDone,
                progressTotal,
                downloading,
                restoring);
    }

    public void shutdownGracefully() {
        if (config != null) {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (syncInFlight.get() && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                long remainingMillis = Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                triggerDrain(true, Duration.ZERO).get(remainingMillis, TimeUnit.MILLISECONDS);
                while (syncInFlight.get() && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
            } catch (Exception ignored) {
                // Uncommitted files remain in the worktree for the next launch.
            }
        }
        shuttingDown = true;
        close();
    }

    private CompletableFuture<SyncOutcome> triggerDrain(boolean manual, Duration delay) {
        CompletableFuture<SyncOutcome> completion = new CompletableFuture<>();
        if (config == null) {
            completion.completeExceptionally(new IllegalStateException("同步配置尚未加载"));
            return completion;
        }
        if (!manual && (halted || config.paused)) {
            completion.complete(null);
            return completion;
        }
        if (syncInFlight.get()) {
            rerunRequested = true;
            completion.complete(null);
            return completion;
        }
        scheduler.schedule(() -> {
            if (!manual && (halted || config.paused)) {
                completion.complete(null);
                return;
            }
            if (!syncInFlight.compareAndSet(false, true)) {
                rerunRequested = true;
                completion.complete(null);
                return;
            }
            rerunRequested = false;
            syncExecutor.submit(() -> runQueue(completion));
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
        return completion;
    }

    private void runQueue(CompletableFuture<SyncOutcome> completion) {
        char[] passphrase = new char[0];
        int processed = 0;
        boolean unstable = false;
        try {
            passphrase = loadPassphrase();
            refreshQueue();
            long stableMillis = config.debounceMillis;
            progressDone = 0;
            progressTotal = Math.max(0, queue.size() - queue.failedCount());
            while (!halted && !config.paused && !shuttingDown) {
                PendingChange change = queue.nextReady(System.currentTimeMillis(), stableMillis);
                if (change == null) {
                    unstable = queue.size() > queue.failedCount();
                    break;
                }
                currentFile = change.displayPath();
                message = "正在同步队列：" + change.displayPath();
                publish();
                try {
                    engine.synchronizeSingle(config.copy(), state, passphrase, change);
                    queue.complete(change.relativePath());
                    processed++;
                    progressDone++;
                    if (queue.failedCount() == 0) {
                        lastError = "";
                        lastFailure = null;
                    }
                    persistStateQuietly();
                } catch (SyncException exception) {
                    if (exception.failure() == SyncFailure.FILE_TOO_LARGE
                            || exception.failure() == SyncFailure.UNKNOWN) {
                        queue.markFailed(change.relativePath(), exception.getMessage());
                        progressDone++;
                        lastFailure = exception.failure();
                        lastError = exception.getMessage();
                        message = "已跳过失败文件：" + change.relativePath();
                        publish();
                    } else {
                        throw exception;
                    }
                }
            }
            if (queue.size() == 0 && !unstable) {
                engine.pushExistingCommits(config.copy(), state, passphrase);
                persistStateQuietly();
            }
            cancelNetworkRetry();
            networkRetryCount = 0;
            message = processed > 0
                    ? "已完成 " + processed + " 个文件的逐文件同步"
                    : queue.failedCount() > 0
                    ? "队列完成，但有 " + queue.failedCount() + " 个文件失败"
                    : "没有需要同步的改动";
            SyncOutcome outcome = new SyncOutcome(
                    processed > 0 ? SyncOutcome.Status.PUSHED : SyncOutcome.Status.NO_CHANGES,
                    processed, message);
            completion.complete(outcome);
        } catch (SyncException exception) {
            if (shuttingDown || Thread.currentThread().isInterrupted()) {
                completion.complete(null);
            } else {
                handleGlobalFailure(exception);
                completion.completeExceptionally(exception);
            }
        } catch (UnknownHostKeyException exception) {
            if (shuttingDown || Thread.currentThread().isInterrupted()) {
                completion.complete(null);
                return;
            }
            lastFailure = SyncFailure.AUTHENTICATION;
            lastError = exception.getMessage();
            message = "需要确认 GitHub 主机密钥";
            completion.completeExceptionally(exception);
            halt();
        } catch (Exception exception) {
            if (shuttingDown || Thread.currentThread().isInterrupted()) {
                completion.complete(null);
                return;
            }
            lastFailure = SyncFailure.UNKNOWN;
            lastError = exception.getMessage();
            message = "同步发生未预期错误";
            completion.completeExceptionally(exception);
            halt();
        } finally {
            Arrays.fill(passphrase, '\0');
            currentFile = "";
            syncInFlight.set(false);
            publish();
            if (unstable && !halted && !config.paused && !shuttingDown) {
                triggerDrain(false, Duration.ofMillis(500));
            } else if (rerunRequested && !halted && !config.paused && !shuttingDown) {
                triggerDrain(false, Duration.ofMillis(config.debounceMillis));
            }
        }
    }

    private void handleGlobalFailure(SyncException exception) {
        lastFailure = exception.failure();
        lastError = exception.getMessage();
        state.lastError = lastError;
        persistStateQuietly();
        message = failureMessage(exception.failure());
        LOGGER.log(exception.failure() == SyncFailure.NETWORK ? Level.WARNING : Level.SEVERE,
                "同步失败：" + exception.getMessage(), exception);
        if (exception.failure() == SyncFailure.NETWORK) {
            scheduleNetworkRetry();
        } else {
            halt();
        }
    }

    private void refreshQueue() throws SyncException {
        queue.merge(inspector.listPendingChanges(config.repositoryPath));
    }

    private void onDirectoryChanged() {
        synchronized (debounceLock) {
            if (halted || config == null || config.paused || shuttingDown) {
                return;
            }
            if (syncInFlight.get()) {
                rerunRequested = true;
                return;
            }
            lastMutationNanos = System.nanoTime();
            scheduleDebounceTick();
        }
    }

    private void scheduleDebounceTick() {
        if (debounceFuture != null && !debounceFuture.isDone()) {
            return;
        }
        debounceFuture = scheduler.schedule(this::debounceTick,
                Math.max(100, config.debounceMillis / 2), TimeUnit.MILLISECONDS);
    }

    private void debounceTick() {
        synchronized (debounceLock) {
            if (halted || config == null || config.paused || shuttingDown) {
                return;
            }
            long quietNanos = System.nanoTime() - lastMutationNanos;
            long quietLimit = TimeUnit.MILLISECONDS.toNanos(config.debounceMillis);
            if (quietNanos < quietLimit) {
                debounceFuture = scheduler.schedule(this::debounceTick,
                        Math.max(100, TimeUnit.NANOSECONDS.toMillis(quietLimit - quietNanos)),
                        TimeUnit.MILLISECONDS);
                return;
            }
            lastMutationNanos = 0;
            triggerDrain(false, Duration.ZERO);
        }
    }

    private void installRealtimeWatcherIfNeeded() {
        if (watcher != null || config == null || config.syncMode != SyncMode.REALTIME) {
            return;
        }
        try {
            RecursiveDirectoryWatcher newWatcher = new RecursiveDirectoryWatcher(
                    Path.of(config.repositoryPath), this::onDirectoryChanged);
            newWatcher.start();
            watcher = newWatcher;
        } catch (IOException exception) {
            setError(SyncFailure.WORKTREE,
                    "无法启动实时监控，请改用定时扫描：" + exception.getMessage());
        }
    }

    private void schedulePolling() {
        if (config == null) {
            return;
        }
        pollFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (halted || config.paused || shuttingDown) {
                return;
            }
            if (!syncInFlight.get()) {
                try {
                    if (inspector.hasUncommittedChanges(config.repositoryPath)
                            || inspector.isLocalAheadWithoutFetch(config.repositoryPath,
                            state.lastRemoteCommit)) {
                        triggerDrain(false, Duration.ZERO);
                    }
                } catch (SyncException exception) {
                    setError(exception.failure(), exception.getMessage());
                }
            }
            checkRemoteInBackground();
        }, config.pollIntervalSeconds, config.pollIntervalSeconds, TimeUnit.SECONDS);
    }

    private void checkRemoteInBackground() {
        if (!remoteCheckInFlight.compareAndSet(false, true)) {
            return;
        }
        AppConfig snapshot = config.copy();
        syncExecutor.submit(() -> {
            char[] passphrase = new char[0];
            try {
                passphrase = loadPassphrase();
                RemoteCheck check = cloudSync.check(snapshot, state, passphrase);
                remoteUpdateAvailable = check.updateAvailable();
                publish();
            } catch (Exception exception) {
                LOGGER.log(Level.FINE, "后台检查远端失败", exception);
            } finally {
                Arrays.fill(passphrase, '\0');
                remoteCheckInFlight.set(false);
            }
        });
    }

    private char[] loadPassphrase() throws IOException {
        char[] value = secretStore.loadPassphrase(config.privateKeyPath)
                .orElseGet(() -> new char[0]);
        if (value.length == 0) {
            char[] legacy = secretStore.loadPassphrase().orElseGet(() -> new char[0]);
            if (legacy.length > 0) {
                secretStore.savePassphrase(config.privateKeyPath, legacy);
                Arrays.fill(legacy, '\0');
                return secretStore.loadPassphrase(config.privateKeyPath)
                        .orElseGet(() -> new char[0]);
            }
        }
        return value;
    }

    private void scheduleNetworkRetry() {
        if (shuttingDown) {
            return;
        }
        cancelNetworkRetry();
        networkRetryCount = Math.min(networkRetryCount + 1, 6);
        long seconds = Math.min(300, 5L * (1L << (networkRetryCount - 1)));
        networkRetryFuture = scheduler.schedule(() -> {
            if (!halted && !config.paused && !shuttingDown) {
                triggerDrain(false, Duration.ZERO);
            }
        }, seconds, TimeUnit.SECONDS);
    }

    private void cancelNetworkRetry() {
        ScheduledFuture<?> retry = networkRetryFuture;
        networkRetryFuture = null;
        if (retry != null) {
            retry.cancel(false);
        }
    }

    private void setError(SyncFailure failure, String error) {
        lastFailure = failure;
        lastError = error;
        message = failureMessage(failure);
        publish();
    }

    private void halt() {
        halted = true;
        cancelWatcher();
        message = "自动同步已暂停，请处理后继续";
        publish();
    }

    private void cancelWatcher() {
        RecursiveDirectoryWatcher current = watcher;
        watcher = null;
        if (current != null) {
            current.close();
        }
    }

    private void cancelDebounce() {
        ScheduledFuture<?> current = debounceFuture;
        debounceFuture = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private void cancelPeriodicTasks() {
        ScheduledFuture<?> poll = pollFuture;
        pollFuture = null;
        if (poll != null) {
            poll.cancel(false);
        }
    }

    private void persistStateQuietly() {
        try {
            stateStore.save(state);
        } catch (IOException exception) {
            lastError = (lastError == null || lastError.isBlank() ? "" : lastError + "；")
                    + "无法保存运行状态：" + exception.getMessage();
        }
    }

    private void publish() {
        SyncStatus current = status();
        for (Consumer<SyncStatus> listener : listeners) {
            listener.accept(current);
        }
    }

    private static String failureMessage(SyncFailure failure) {
        return switch (failure) {
            case AUTHENTICATION -> "SSH 认证失败";
            case NETWORK -> "网络不可用，等待重试";
            case REMOTE_CONFLICT -> "远端分支发生冲突";
            case WORKTREE -> "Git 工作区需要处理";
            case FILE_TOO_LARGE -> "存在超过 GitHub 限制的文件";
            case UNKNOWN -> "同步失败";
        };
    }

    @Override
    public void close() {
        shuttingDown = true;
        cancelWatcher();
        cancelDebounce();
        cancelPeriodicTasks();
        cancelNetworkRetry();
        scheduler.shutdownNow();
        syncExecutor.shutdownNow();
    }
}
