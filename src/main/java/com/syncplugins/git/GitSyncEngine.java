package com.syncplugins.git;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.security.KnownHostKey;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SshTransportFactory;
import com.syncplugins.security.UnknownHostKeyException;
import com.syncplugins.sync.ChangeKind;
import com.syncplugins.sync.PendingChange;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GitSyncEngine {
    public static final long GITHUB_MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final DateTimeFormatter COMMIT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GitRepositoryInspector inspector;
    private final KnownHostsStore knownHostsStore;

    public GitSyncEngine(GitRepositoryInspector inspector, KnownHostsStore knownHostsStore) {
        this.inspector = inspector;
        this.knownHostsStore = knownHostsStore;
    }

    public String testConnection(AppConfig config, char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        boolean sshRemote = isSshRemote(config.remoteUrl);
        Path privateKey = sshRemote ? requireKey(config.privateKeyPath) : null;
        rejectKeyInsideWorktree(config, privateKey);
        SshTransportFactory ssh = sshRemote
                ? new SshTransportFactory(privateKey, passphrase, knownHostsStore.file())
                : null;
        try (Git git = Git.open(Path.of(config.repositoryPath).toFile())) {
            RemoteSnapshot remote = fetchRemote(git, config, ssh);
            return remote.commit();
        } catch (IOException | GitAPIException exception) {
            throw translate(exception, ssh);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    public SyncOutcome synchronize(AppConfig config, AppState state, char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        config.validate();
        inspector.inspect(config.repositoryPath);
        boolean sshRemote = isSshRemote(config.remoteUrl);
        Path privateKey = sshRemote ? requireKey(config.privateKeyPath) : null;
        rejectKeyInsideWorktree(config, privateKey);
        SshTransportFactory ssh = sshRemote
                ? new SshTransportFactory(privateKey, passphrase, knownHostsStore.file())
                : null;
        try (Git git = Git.open(Path.of(config.repositoryPath).toFile())) {
            Repository repository = git.getRepository();
            RemoteSnapshot remote;
            boolean remoteAvailable = true;
            try {
                try {
                    remote = fetchRemote(git, config, ssh);
                } catch (GitAPIException | IOException exception) {
                    throw translate(exception, ssh);
                }
                verifyRemoteBaseline(repository, remote, state);
            } catch (SyncException exception) {
                if (exception.failure() != SyncFailure.NETWORK) {
                    throw exception;
                }
                remoteAvailable = false;
                remote = new RemoteSnapshot(state.lastRemoteCommit);
                state.lastError = exception.getMessage();
            }

            Status status = git.status().call();
            Set<String> changed = collectChangedPaths(status);
            rejectOversizeFiles(repository.getWorkTree().toPath(), status);

            int changedCount = changed.size();
            boolean committed = false;
            if (!changed.isEmpty()) {
                git.add().addFilepattern(".").call();
                Status stagedStatus = git.status().call();
                boolean staged = hasStagedChanges(stagedStatus);
                if (staged) {
                    String message = "SyncPlugins auto-sync: " + Math.max(1, changedCount);
                    git.commit()
                            .setMessage(message)
                            .setAuthor(config.authorName, config.authorEmail)
                            .setCommitter(config.authorName, config.authorEmail)
                            .setAllowEmpty(false)
                            .call();
                    committed = true;
                    state.pendingPush = true;
                    state.lastCommitAt = System.currentTimeMillis();
                }
            }

            ObjectId localHead = repository.resolve(Constants.HEAD);
            if (localHead == null) {
                if (!changed.isEmpty()) {
                    throw new SyncException(SyncFailure.WORKTREE, "文件已暂存，但仓库没有可提交的初始内容");
                }
                state.lastError = "";
                return new SyncOutcome(SyncOutcome.Status.NO_CHANGES, 0, "仓库没有需要同步的提交");
            }

            if (!remoteAvailable) {
                if (!committed && changedCount == 0 && !state.pendingPush) {
                    throw new SyncException(SyncFailure.NETWORK,
                            "网络不可用，等待恢复后重新检查远端");
                }
                state.pendingPush = true;
                state.lastError = committed
                        ? "网络不可用，改动已在本地提交，稍后自动重试推送"
                        : "网络不可用，已有本地提交等待推送";
                return new SyncOutcome(SyncOutcome.Status.COMMITTED_PENDING,
                        changedCount, state.lastError);
            }

            boolean needsPush = remote.commit() == null || !localHead.name().equals(remote.commit());
            if (!needsPush) {
                state.pendingPush = false;
                state.lastSuccessAt = System.currentTimeMillis();
                state.lastError = "";
                return new SyncOutcome(SyncOutcome.Status.NO_CHANGES, changedCount,
                        committed ? "本地改动已提交" : "没有需要同步的改动");
            }

            push(git, config, ssh, localHead.name());
            state.lastRemoteCommit = localHead.name();
            state.pendingPush = false;
            state.lastSuccessAt = System.currentTimeMillis();
            state.lastError = "";
            return new SyncOutcome(SyncOutcome.Status.PUSHED, changedCount,
                    targetName(config) + " 已推送到 GitHub");
        } catch (SyncException exception) {
            throw exception;
        } catch (IOException | GitAPIException exception) {
            throw translate(exception, ssh);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    public SyncOutcome synchronizeSingle(AppConfig config, AppState state, char[] passphrase,
                                         PendingChange change)
            throws SyncException, UnknownHostKeyException {
        config.validate();
        inspector.inspect(config.repositoryPath);
        boolean sshRemote = isSshRemote(config.remoteUrl);
        Path privateKey = sshRemote ? requireKey(config.privateKeyPath) : null;
        rejectKeyInsideWorktree(config, privateKey);
        SshTransportFactory ssh = sshRemote
                ? new SshTransportFactory(privateKey, passphrase, knownHostsStore.file())
                : null;
        try (Git git = Git.open(Path.of(config.repositoryPath).toFile())) {
            Repository repository = git.getRepository();
            RemoteSnapshot remote;
            try {
                remote = fetchRemote(git, config, ssh);
            } catch (GitAPIException | IOException exception) {
                throw translate(exception, ssh);
            }
            verifyRemoteBaseline(repository, remote, state);
            rejectSingleOversize(repository.getWorkTree().toPath(), change);
            String path = change.relativePath();
            if (change.kind() == ChangeKind.DELETE) {
                git.add().setUpdate(true).addFilepattern(path).call();
            } else {
                git.add().addFilepattern(path).call();
            }
            String message = "SyncPlugins auto-sync: " + action(change.kind()) + " "
                    + shorten(path, 180);
            git.commit()
                    .setOnly(path)
                    .setMessage(message)
                    .setAuthor(config.authorName, config.authorEmail)
                    .setCommitter(config.authorName, config.authorEmail)
                    .setAllowEmpty(false)
                    .call();
            state.lastCommitAt = System.currentTimeMillis();
            state.pendingPush = true;
            ObjectId localHead = repository.resolve(Constants.HEAD);
            if (localHead == null) {
                throw new SyncException(SyncFailure.WORKTREE, "文件提交后无法读取本地 HEAD");
            }
            if (remote.commit() == null || !localHead.name().equals(remote.commit())) {
                push(git, config, ssh, localHead.name());
                state.lastRemoteCommit = localHead.name();
            }
            state.pendingPush = false;
            state.lastSuccessAt = System.currentTimeMillis();
            state.lastError = "";
            return new SyncOutcome(SyncOutcome.Status.PUSHED, 1,
                    action(change.kind()) + " " + path + " 已推送");
        } catch (SyncException exception) {
            throw exception;
        } catch (IOException | GitAPIException exception) {
            throw translate(exception, ssh);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    public void pushExistingCommits(AppConfig config, AppState state, char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        boolean sshRemote = isSshRemote(config.remoteUrl);
        Path privateKey = sshRemote ? requireKey(config.privateKeyPath) : null;
        SshTransportFactory ssh = sshRemote
                ? new SshTransportFactory(privateKey, passphrase, knownHostsStore.file())
                : null;
        try (Git git = Git.open(Path.of(config.repositoryPath).toFile())) {
            RemoteSnapshot remote = fetchRemote(git, config, ssh);
            verifyRemoteBaseline(git.getRepository(), remote, state);
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            if (head != null && (remote.commit() == null || !head.name().equals(remote.commit()))) {
                push(git, config, ssh, head.name());
                state.lastRemoteCommit = head.name();
                state.lastSuccessAt = System.currentTimeMillis();
            } else if (head != null) {
                state.lastRemoteCommit = head.name();
                state.lastSuccessAt = System.currentTimeMillis();
            }
            state.pendingPush = false;
            state.lastError = "";
        } catch (SyncException exception) {
            throw exception;
        } catch (IOException | GitAPIException exception) {
            throw translate(exception, ssh);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    private RemoteSnapshot fetchRemote(Git git, AppConfig config, SshTransportFactory ssh)
            throws GitAPIException, IOException, SyncException {
        String tracking = trackingRef(config.branch);
        RefSpec spec = new RefSpec("+" + Constants.R_HEADS + config.branch + ":" + tracking);
        var command = git.fetch()
                .setRemote(config.remoteUrl)
                .setRefSpecs(spec)
                .setTimeout(30);
        if (ssh != null) {
            command.setTransportConfigCallback(transport -> configureSsh(transport, ssh));
        }
        FetchResult result;
        try {
            result = command.call();
        } catch (GitAPIException exception) {
            String message = exception.getMessage();
            if (message != null
                    && ((message.contains("Remote branch") && message.contains("not found"))
                    || (message.contains("Remote does not have")
                    && message.contains("available for fetch")))) {
                return new RemoteSnapshot(null);
            }
            throw exception;
        }
        Ref advertisedRef = result.getAdvertisedRef(Constants.R_HEADS + config.branch);
        return new RemoteSnapshot(advertisedRef == null || advertisedRef.getObjectId() == null
                ? null
                : advertisedRef.getObjectId().name());
    }

    private void verifyRemoteBaseline(Repository repository, RemoteSnapshot remote, AppState state)
            throws SyncException, IOException {
        if (state.lastRemoteCommit != null && remote.commit() == null) {
            throw new SyncException(SyncFailure.REMOTE_CONFLICT,
                    "GitHub 分支已不存在，已暂停自动同步。请手动处理后继续。");
        }
        if (state.lastRemoteCommit != null && !state.lastRemoteCommit.equals(remote.commit())) {
            throw new SyncException(SyncFailure.REMOTE_CONFLICT,
                    "GitHub 分支已被其他位置修改，已暂停自动同步。请手动处理后再继续。");
        }
        ObjectId localHead = repository.resolve(Constants.HEAD);
        if (localHead != null && remote.commit() != null && !localHead.name().equals(remote.commit())) {
            ObjectId remoteHead = repository.resolve(remote.commit());
            if (remoteHead == null || !isAncestor(repository, remoteHead, localHead)) {
                throw new SyncException(SyncFailure.REMOTE_CONFLICT,
                        "本地分支与 GitHub 分支已经分叉或落后，已暂停自动同步，不会自动合并或覆盖远端。");
            }
        }
        if (state.lastRemoteCommit == null) {
            state.lastRemoteCommit = remote.commit();
        }
    }

    private void push(Git git, AppConfig config, SshTransportFactory ssh, String localHead)
            throws GitAPIException, SyncException, IOException {
        PushCommand command = git.push()
                .setRemote(config.remoteUrl)
                .setRefSpecs(new RefSpec(Constants.R_HEADS + config.branch
                        + ":" + Constants.R_HEADS + config.branch))
                .setTimeout(30);
        if (ssh != null) {
            command.setTransportConfigCallback(transport -> configureSsh(transport, ssh));
        }
        Iterable<PushResult> results = command.call();
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status != RemoteRefUpdate.Status.OK
                        && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD
                            || status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        throw new SyncException(SyncFailure.REMOTE_CONFLICT,
                                "推送被拒绝：GitHub 分支已变化，已保留本地提交并暂停同步");
                    }
                    throw new SyncException(SyncFailure.UNKNOWN,
                            "推送失败：" + status + "；" + safe(update.getMessage()));
                }
            }
        }
        ObjectId current = git.getRepository().resolve(Constants.HEAD);
        if (current == null || !current.name().equals(localHead)) {
            throw new SyncException(SyncFailure.WORKTREE, "推送期间本地 HEAD 发生变化，请检查仓库状态");
        }
    }

    private static boolean isAncestor(Repository repository, ObjectId ancestor, ObjectId descendant)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit ancestorCommit = walk.parseCommit(ancestor);
            RevCommit descendantCommit = walk.parseCommit(descendant);
            return walk.isMergedInto(ancestorCommit, descendantCommit);
        }
    }

    private static Set<String> collectChangedPaths(Status status) {
        Set<String> paths = new HashSet<>();
        paths.addAll(status.getAdded());
        paths.addAll(status.getChanged());
        paths.addAll(status.getRemoved());
        paths.addAll(status.getMissing());
        paths.addAll(status.getModified());
        paths.addAll(status.getUntracked());
        paths.addAll(status.getConflicting());
        return paths;
    }

    private static boolean hasStagedChanges(Status status) {
        return !status.getAdded().isEmpty()
                || !status.getChanged().isEmpty()
                || !status.getRemoved().isEmpty()
                || !status.getConflicting().isEmpty();
    }

    private static void rejectOversizeFiles(Path root, Status status) throws SyncException {
        Set<String> candidates = new HashSet<>();
        candidates.addAll(status.getAdded());
        candidates.addAll(status.getChanged());
        candidates.addAll(status.getModified());
        candidates.addAll(status.getUntracked());
        for (String relative : candidates) {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                long size = Files.size(file);
                if (size > GITHUB_MAX_FILE_SIZE) {
                    throw new SyncException(SyncFailure.FILE_TOO_LARGE,
                            "文件超过 GitHub 100 MB 限制：" + relative);
                }
            } catch (IOException exception) {
                throw new SyncException(SyncFailure.WORKTREE,
                        "无法读取文件大小：" + relative, exception);
            }
        }
    }

    private void rejectSingleOversize(Path root, PendingChange change) throws SyncException {
        if (change.kind() == ChangeKind.DELETE) {
            return;
        }
        Path file = change.resolve(root);
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "待同步文件不存在或路径无效：" + change.relativePath());
        }
        try {
            if (Files.size(file) > GITHUB_MAX_FILE_SIZE) {
                throw new SyncException(SyncFailure.FILE_TOO_LARGE,
                        "文件超过 GitHub 100 MB 限制：" + change.relativePath());
            }
        } catch (IOException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法读取文件大小：" + change.relativePath(), exception);
        }
    }

    private SyncException translate(Exception exception, SshTransportFactory ssh)
            throws UnknownHostKeyException {
        if (ssh != null) {
            KnownHostKey rejected = ssh.rejectedHostKey();
            if (rejected != null) {
                throw new UnknownHostKeyException(rejected);
            }
        }
        Throwable root = rootCause(exception);
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("auth") || lower.contains("passphrase") || lower.contains("privatekey")
                || lower.contains("permission denied") || lower.contains("not allowed")) {
            return new SyncException(SyncFailure.AUTHENTICATION,
                    "SSH 认证失败，请检查私钥、口令以及 GitHub 公钥配置：" + message, exception);
        }
        if (lower.contains("non-fast-forward") || lower.contains("rejected")) {
            return new SyncException(SyncFailure.REMOTE_CONFLICT,
                    "GitHub 分支已发生变化，已暂停自动同步：" + message, exception);
        }
        if (lower.contains("unknownhost") || lower.contains("connect") || lower.contains("timeout")
                || lower.contains("timed out") || lower.contains("network") || lower.contains("unreachable")) {
            return new SyncException(SyncFailure.NETWORK,
                    "无法连接 GitHub：" + message, exception);
        }
        return new SyncException(SyncFailure.UNKNOWN, "Git 同步失败：" + message, exception);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static Path requireKey(String privateKeyPath) throws SyncException {
        try {
            Path path = Path.of(privateKeyPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new SyncException(SyncFailure.AUTHENTICATION, "SSH 私钥文件不存在：" + path);
            }
            return path;
        } catch (SyncException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SyncException(SyncFailure.AUTHENTICATION, "SSH 私钥路径无效", exception);
        }
    }

    private static void rejectKeyInsideWorktree(AppConfig config, Path privateKey) throws SyncException {
        if (privateKey == null) {
            return;
        }
        Path workTree = Path.of(config.repositoryPath).toAbsolutePath().normalize();
        if (privateKey.startsWith(workTree)) {
            throw new SyncException(SyncFailure.AUTHENTICATION,
                    "SSH 私钥位于被监听仓库中，请把私钥移到仓库目录之外");
        }
    }

    private static boolean isSshRemote(String remoteUrl) {
        String lower = remoteUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("ssh://") || (!lower.contains("://") && lower.contains("@"));
    }

    private static void configureSsh(Transport transport, SshTransportFactory ssh) {
        if (transport instanceof SshTransport sshTransport) {
            sshTransport.setSshSessionFactory(ssh.sessionFactory());
        }
    }

    private static String trackingRef(String branch) {
        String safeBranch = branch.replace('\\', '/');
        return "refs/syncplugins/remote/" + safeBranch;
    }

    private static String targetName(AppConfig config) {
        return config.branch + " 分支";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String action(ChangeKind kind) {
        return switch (kind) {
            case ADD -> "add";
            case MODIFY -> "modify";
            case DELETE -> "delete";
        };
    }

    private static String shorten(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    private record RemoteSnapshot(String commit) {
    }
}
