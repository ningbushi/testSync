package com.syncplugins.git;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SshTransportFactory;
import com.syncplugins.security.UnknownHostKeyException;
import com.syncplugins.workspace.BackupManager;
import com.syncplugins.workspace.BackupRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CloudSyncService {
    private final KnownHostsStore knownHostsStore;
    private final BackupManager backupManager;

    public CloudSyncService(KnownHostsStore knownHostsStore, BackupManager backupManager) {
        this.knownHostsStore = knownHostsStore;
        this.backupManager = backupManager;
    }

    public RemoteCheck check(AppConfig config, AppState state, char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        boolean sshRemote = isSshRemote(config.remoteUrl);
        Path key = sshRemote ? Path.of(config.privateKeyPath) : null;
        SshTransportFactory ssh = sshRemote
                ? new SshTransportFactory(key, passphrase, knownHostsStore.file())
                : null;
        try (Git git = Git.open(Path.of(config.repositoryPath).toFile())) {
            String remote = fetch(git, config, ssh);
            ObjectId local = git.getRepository().resolve(Constants.HEAD);
            boolean changed = remote != null
                    && (state.lastRemoteCommit == null
                    || !remote.equals(state.lastRemoteCommit)
                    || local == null
                    || !remote.equals(local.name()));
            return new RemoteCheck(remote, changed);
        } catch (IOException | GitAPIException exception) {
            throw translate(exception, ssh);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    public DownloadResult download(AppConfig config, AppState state, char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        boolean sshRemote = isSshRemote(config.remoteUrl);
        Path key = sshRemote ? Path.of(config.privateKeyPath) : null;
        SshTransportFactory ssh = sshRemote
                ? new SshTransportFactory(key, passphrase, knownHostsStore.file())
                : null;
        try (Git git = Git.open(Path.of(config.repositoryPath).toFile())) {
            String remote = fetch(git, config, ssh);
            if (remote == null) {
                throw new SyncException(SyncFailure.REMOTE_CONFLICT, "GitHub 分支尚不存在");
            }
            ObjectId remoteId = git.getRepository().resolve(remote);
            ObjectId local = git.getRepository().resolve(Constants.HEAD);
            if (remoteId == null) {
                throw new SyncException(SyncFailure.REMOTE_CONFLICT,
                        "无法在本地读取 GitHub 分支提交");
            }
            var status = git.status().call();
            boolean dirty = status.hasUncommittedChanges()
                    || !status.getUntracked().isEmpty()
                    || !status.getConflicting().isEmpty();
            boolean fastForward = !dirty && local != null
                    && (local.equals(remoteId) || isAncestor(git, local, remoteId));
            if (fastForward && local != null && local.equals(remoteId)) {
                state.lastRemoteCommit = remote;
                return new DownloadResult(false, "云端没有新的内容", null);
            }

            BackupRecord backup = null;
            List<String> untracked = new ArrayList<>(status.getUntracked());
            if (dirty || !fastForward) {
                try {
                    backup = backupManager.create(Path.of(config.repositoryPath), config.backupLimit);
                } catch (IOException exception) {
                    throw new SyncException(SyncFailure.WORKTREE,
                            "无法创建下载前备份：" + exception.getMessage(), exception);
                }
            }

            for (String relative : untracked) {
                Path target = Path.of(config.repositoryPath).resolve(relative).normalize();
                if (target.startsWith(Path.of(config.repositoryPath).toAbsolutePath().normalize())) {
                    Files.deleteIfExists(target);
                }
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remote).call();
            state.lastRemoteCommit = remote;
            state.pendingPush = false;
            state.lastSuccessAt = System.currentTimeMillis();
            state.lastError = "";
            return new DownloadResult(true, "已从云端下载 " + config.branch + " 分支",
                    backup);
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

    private static String fetch(Git git, AppConfig config, SshTransportFactory ssh)
            throws IOException, GitAPIException, SyncException {
        String tracking = "refs/syncplugins/cloud/" + config.branch.replace('\\', '/');
        var command = git.fetch()
                .setRemote(config.remoteUrl)
                .setRefSpecs(new RefSpec("+" + Constants.R_HEADS + config.branch + ":" + tracking))
                .setTimeout(30);
        if (ssh != null) {
            command.setTransportConfigCallback(transport -> configureSsh(transport, ssh));
        }
        FetchResult result = command.call();
        Ref advertised = result.getAdvertisedRef(Constants.R_HEADS + config.branch);
        return advertised == null || advertised.getObjectId() == null
                ? null : advertised.getObjectId().name();
    }

    private static boolean isAncestor(Git git, ObjectId ancestor, ObjectId descendant)
            throws IOException {
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit ancestorCommit = walk.parseCommit(ancestor);
            RevCommit descendantCommit = walk.parseCommit(descendant);
            return walk.isMergedInto(ancestorCommit, descendantCommit);
        }
    }

    private static SyncException translate(Exception exception, SshTransportFactory ssh)
            throws UnknownHostKeyException {
        if (ssh != null && ssh.rejectedHostKey() != null) {
            throw new UnknownHostKeyException(ssh.rejectedHostKey());
        }
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("auth") || lower.contains("permission denied")) {
            return new SyncException(SyncFailure.AUTHENTICATION,
                    "SSH 认证失败：" + message, exception);
        }
        if (lower.contains("connect") || lower.contains("timeout") || lower.contains("network")) {
            return new SyncException(SyncFailure.NETWORK,
                    "无法连接 GitHub：" + message, exception);
        }
        if (lower.contains("reject") || lower.contains("non-fast-forward")) {
            return new SyncException(SyncFailure.REMOTE_CONFLICT,
                    "远端分支状态发生变化：" + message, exception);
        }
        return new SyncException(SyncFailure.UNKNOWN,
                "云端下载失败：" + message, exception);
    }

    private static void configureSsh(Transport transport, SshTransportFactory ssh) {
        if (transport instanceof SshTransport sshTransport) {
            sshTransport.setSshSessionFactory(ssh.sessionFactory());
        }
    }

    private static boolean isSshRemote(String remoteUrl) {
        String lower = remoteUrl == null ? "" : remoteUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("ssh://") || (!lower.contains("://") && lower.contains("@"));
    }
}
