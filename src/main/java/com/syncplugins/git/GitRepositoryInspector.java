package com.syncplugins.git;

import com.syncplugins.sync.ChangeKind;
import com.syncplugins.sync.PendingChange;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.SystemReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class GitRepositoryInspector {
    private static final List<String> OPERATION_MARKERS = List.of(
            "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG");
    private static final List<String> OPERATION_DIRECTORIES = List.of(
            "rebase-merge", "rebase-apply", "sequencer");

    public RepositoryInfo inspect(String repositoryPath) throws SyncException {
        Path requested;
        try {
            requested = Path.of(repositoryPath).toAbsolutePath().normalize().toRealPath();
        } catch (IOException | RuntimeException exception) {
            throw new SyncException(SyncFailure.WORKTREE, "监听目录不存在：" + repositoryPath, exception);
        }
        if (!Files.isDirectory(requested)) {
            throw new SyncException(SyncFailure.WORKTREE, "监听路径不是文件夹：" + requested);
        }

        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(requested.toFile())
                .build();
             Git git = new Git(repository)) {
            if (repository.isBare()) {
                throw new SyncException(SyncFailure.WORKTREE, "不支持裸仓库，请选择包含工作区的 Git 仓库");
            }
            Path actualRoot = repository.getWorkTree().toPath().toRealPath();
            if (!actualRoot.equals(requested)) {
                throw new SyncException(SyncFailure.WORKTREE,
                        "请选择 Git 仓库根目录，而不是子目录：" + actualRoot);
            }
            rejectUnsafeRepositoryState(repository);
            String branch = currentBranch(repository);
            Ref head = repository.exactRef(Constants.HEAD);
            String headCommit = head == null || head.getObjectId() == null
                    ? null
                    : head.getObjectId().name();
            Config config = repository.getConfig();
            String remoteUrl = preferredRemote(config);
            String authorName = configuredUser(config, "name");
            String authorEmail = configuredUser(config, "email");
            if (!git.status().call().getConflicting().isEmpty()) {
                throw new SyncException(SyncFailure.WORKTREE, "仓库存在未解决的合并冲突");
            }
            return new RepositoryInfo(actualRoot, branch, headCommit, remoteUrl, authorName, authorEmail);
        } catch (SyncException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "所选目录不是 Git 仓库。请选择包含 .git 目录的仓库根目录，而不是它的上级目录或普通资料文件夹："
                            + requested,
                    exception);
        } catch (IOException | GitAPIException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法打开 Git 仓库：" + requested + "；" + conciseMessage(exception), exception);
        }
    }

    public boolean hasUncommittedChanges(String repositoryPath) throws SyncException {
        try (Git git = Git.open(Path.of(repositoryPath).toFile())) {
            rejectUnsafeRepositoryState(git.getRepository());
            var status = git.status().call();
            return status.hasUncommittedChanges()
                    || !status.getUntracked().isEmpty()
                    || !status.getConflicting().isEmpty();
        } catch (SyncException exception) {
            throw exception;
        } catch (IOException | GitAPIException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法检查工作区：" + conciseMessage(exception), exception);
        }
    }

    public boolean isLocalAheadWithoutFetch(String repositoryPath, String remoteCommit) throws SyncException {
        if (remoteCommit == null || remoteCommit.isBlank()) {
            return false;
        }
        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(Path.of(repositoryPath).toFile())
                .build()) {
            ObjectId local = repository.resolve(Constants.HEAD);
            return local != null && !local.name().equals(remoteCommit);
        } catch (IOException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法读取本地提交：" + conciseMessage(exception), exception);
        }
    }

    public List<PendingChange> listPendingChanges(String repositoryPath) throws SyncException {
        try (Git git = Git.open(Path.of(repositoryPath).toFile())) {
            rejectUnsafeRepositoryState(git.getRepository());
            var status = git.status().call();
            Path root = git.getRepository().getWorkTree().toPath();
            java.util.Map<String, ChangeKind> paths = new java.util.LinkedHashMap<>();
            status.getUntracked().forEach(path -> paths.put(normalize(path), ChangeKind.ADD));
            status.getAdded().forEach(path -> paths.put(normalize(path), ChangeKind.ADD));
            status.getChanged().forEach(path -> paths.put(normalize(path), ChangeKind.MODIFY));
            status.getModified().forEach(path -> paths.put(normalize(path), ChangeKind.MODIFY));
            status.getMissing().forEach(path -> paths.put(normalize(path), ChangeKind.DELETE));
            status.getRemoved().forEach(path -> paths.put(normalize(path), ChangeKind.DELETE));
            List<PendingChange> result = new java.util.ArrayList<>();
            for (var entry : paths.entrySet()) {
                long modified = modificationTime(root.resolve(entry.getKey()));
                result.add(new PendingChange(entry.getKey(), entry.getValue(), modified, modified));
            }
            result.sort(PendingChange.ORDER);
            return result;
        } catch (SyncException exception) {
            throw exception;
        } catch (IOException | GitAPIException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法扫描待同步文件：" + conciseMessage(exception), exception);
        }
    }

    private static long modificationTime(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.getLastModifiedTime(path).toMillis();
            }
            Path parent = path.getParent();
            return parent != null && Files.exists(parent)
                    ? Files.getLastModifiedTime(parent).toMillis()
                    : System.currentTimeMillis();
        } catch (IOException exception) {
            return System.currentTimeMillis();
        }
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static void rejectUnsafeRepositoryState(Repository repository) throws SyncException {
        Path gitDirectory = repository.getDirectory().toPath();
        try {
            Ref head = repository.exactRef(Constants.HEAD);
            if (head == null || !head.isSymbolic()) {
                throw new SyncException(SyncFailure.WORKTREE, "仓库处于 detached HEAD 状态，请先切换到分支");
            }
        } catch (IOException exception) {
            throw new SyncException(SyncFailure.WORKTREE, "无法读取当前分支", exception);
        }
        for (String marker : OPERATION_MARKERS) {
            if (Files.exists(gitDirectory.resolve(marker))) {
                throw new SyncException(SyncFailure.WORKTREE,
                        "仓库存在未完成操作（" + marker + "），请先在 Git 中完成或中止");
            }
        }
        for (String directory : OPERATION_DIRECTORIES) {
            if (Files.exists(gitDirectory.resolve(directory))) {
                throw new SyncException(SyncFailure.WORKTREE,
                        "仓库存在未完成操作（" + directory + "），请先在 Git 中完成或中止");
            }
        }
        if (Files.exists(gitDirectory.resolve("index.lock"))) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "Git 仓库正被其他程序操作（存在 index.lock），请稍后重试");
        }
    }

    private static String currentBranch(Repository repository) throws SyncException {
        try {
            Ref head = repository.exactRef(Constants.HEAD);
            if (head == null || !head.isSymbolic() || head.getTarget() == null) {
                throw new SyncException(SyncFailure.WORKTREE, "无法识别当前 Git 分支");
            }
            String target = head.getTarget().getName();
            if (!target.startsWith(Constants.R_HEADS)) {
                throw new SyncException(SyncFailure.WORKTREE, "当前 HEAD 未指向普通分支");
            }
            return Repository.shortenRefName(target);
        } catch (IOException exception) {
            throw new SyncException(SyncFailure.WORKTREE, "无法读取当前 Git 分支", exception);
        }
    }

    private static String preferredRemote(Config config) {
        Set<String> remotes = config.getSubsections("remote");
        if (remotes.isEmpty()) {
            return "";
        }
        String selected = remotes.contains("origin") ? "origin" : remotes.iterator().next();
        String url = config.getString("remote", selected, "pushurl");
        return url == null || url.isBlank() ? config.getString("remote", selected, "url") : url;
    }

    private static String configuredUser(Config local, String key) {
        String value = local.getString("user", null, key);
        if (value == null || value.isBlank()) {
            try {
                value = SystemReader.getInstance().getUserConfig().getString("user", null, key);
            } catch (IOException | ConfigInvalidException exception) {
                value = null;
            }
        }
        return value == null ? "" : value;
    }

    private static String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}

