package com.syncplugins.workspace;

import com.syncplugins.config.AppConfig;
import com.syncplugins.git.SyncException;
import com.syncplugins.git.SyncFailure;
import com.syncplugins.security.KnownHostKey;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SshTransportFactory;
import com.syncplugins.security.UnknownHostKeyException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.Transport;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WorkspaceBootstrapService {
    private static final DateTimeFormatter CONFLICT_TIME = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final KnownHostsStore knownHostsStore;
    private final BackupManager backupManager;

    public WorkspaceBootstrapService(KnownHostsStore knownHostsStore, BackupManager backupManager) {
        this.knownHostsStore = knownHostsStore;
        this.backupManager = backupManager;
    }

    public WorkspaceInspection inspect(String localPathText, String remoteUrl,
                                       String requestedBranch, String privateKeyPath,
                                       char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        Path localPath = Path.of(localPathText).toAbsolutePath().normalize();
        rejectNestedRepository(localPath);
        RemoteProbe remote = probeRemote(remoteUrl, privateKeyPath, passphrase);
        boolean existingRepository = isExistingRepository(localPath);
        String currentBranch = existingRepository ? currentBranch(localPath) : null;
        String branch = requestedBranch != null && !requestedBranch.isBlank()
                ? requestedBranch.trim()
                : existingRepository ? currentBranch : effectiveBranch(localPath, null, remote);
        validateBranchName(branch);
        if (existingRepository) {
            String baseBranch = remote.branches().contains(branch) ? branch : currentBranch;
            return new WorkspaceInspection(localPath, remoteUrl, WorkspaceMode.EXISTING_REPO,
                    branch, baseBranch, remote.branches(), List.of(), List.of(), List.of(),
                    remote.headFor(baseBranch));
        }
        boolean localEmpty = isMissingOrEmpty(localPath);
        boolean remoteEmpty = remote.branches().isEmpty();
        String baseBranch = null;
        String remoteHead = null;
        if (!remoteEmpty) {
            baseBranch = remote.branches().contains(branch)
                    ? branch
                    : remote.defaultBranch() != null ? remote.defaultBranch()
                    : remote.branches().get(0);
            remoteHead = remote.headFor(baseBranch);
        }
        if (remoteHead == null) {
            return new WorkspaceInspection(localPath, remoteUrl, WorkspaceMode.INIT_LOCAL,
                    branch, baseBranch, remote.branches(), List.of(), List.of(), List.of(), null);
        }
        if (localEmpty) {
            return new WorkspaceInspection(localPath, remoteUrl, WorkspaceMode.CLONE_REMOTE,
                    branch, baseBranch, remote.branches(), List.of(), List.of(), List.of(), remoteHead);
        }
        Path temporaryClone = cloneForInspection(remoteUrl, baseBranch, privateKeyPath, passphrase);
        try {
            DiffResult diff;
            try {
                diff = compareTrees(localPath, temporaryClone);
            } catch (IOException exception) {
                throw new SyncException(SyncFailure.WORKTREE,
                        "无法比较本地和云端文件：" + exception.getMessage(), exception);
            }
            return new WorkspaceInspection(localPath, remoteUrl, WorkspaceMode.CONFLICT,
                    branch, baseBranch, remote.branches(), diff.localOnly(), diff.remoteOnly(),
                    diff.conflicting(), remoteHead);
        } finally {
            deleteQuietly(temporaryClone);
            deleteQuietly(temporaryClone.getParent());
        }
    }

    public WorkspaceInspection prepare(String localPathText, String remoteUrl, String branch,
                                       String privateKeyPath, char[] passphrase,
                                       ConflictResolution resolution, int backupLimit)
            throws SyncException, UnknownHostKeyException {
        WorkspaceInspection inspection = inspect(localPathText, remoteUrl, branch,
                privateKeyPath, passphrase);
        Path localPath = inspection.localPath();
        try {
            if (inspection.mode() == WorkspaceMode.EXISTING_REPO) {
                prepareExistingRepository(localPath, remoteUrl, inspection.branch(),
                        inspection.remoteBranches(), privateKeyPath, passphrase);
                return inspection;
            }
            if (inspection.mode() == WorkspaceMode.INIT_LOCAL) {
                Files.createDirectories(localPath);
                initialize(localPath, inspection.branch());
                return inspection;
            }
            if (inspection.mode() == WorkspaceMode.CLONE_REMOTE) {
                String cloneBranch = inspection.baseBranch() == null
                        ? inspection.branch() : inspection.baseBranch();
                cloneInto(localPath, remoteUrl, cloneBranch, privateKeyPath, passphrase);
                switchToBranch(localPath, inspection.branch(), false);
                return inspection;
            }
            if (resolution == null) {
                throw new SyncException(SyncFailure.WORKTREE, "本地与云端均有内容，请选择初始化处理方式");
            }
            backupManager.create(localPath, backupLimit);
            String baseBranch = inspection.baseBranch() == null
                    ? inspection.branch() : inspection.baseBranch();
            Path clone = cloneForInspection(remoteUrl, baseBranch, privateKeyPath, passphrase);
            try {
                applyConflictResolution(localPath, clone, inspection, resolution);
                switchToBranch(localPath, inspection.branch(), false);
            } finally {
                deleteQuietly(clone);
                deleteQuietly(clone.getParent());
            }
            return inspection;
        } catch (IOException | GitAPIException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "初始化本地工作区失败：" + exception.getMessage(), exception);
        }
    }

    public RemoteProbe probeRemote(String remoteUrl, String privateKeyPath, char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        SshTransportFactory ssh = null;
        try {
            if (isSshRemote(remoteUrl)) {
                ssh = new SshTransportFactory(Path.of(privateKeyPath).toAbsolutePath().normalize(),
                        passphrase, knownHostsStore.file());
            }
            LsRemoteCommand command = Git.lsRemoteRepository()
                    .setRemote(remoteUrl)
                    .setHeads(true)
                    .setTags(false)
                    .setTimeout(30);
            if (ssh != null) {
                SshTransportFactory factory = ssh;
                command.setTransportConfigCallback(transport -> configureSsh(transport, factory));
            }
            Iterable<Ref> refs = command.call();
            List<String> branches = new ArrayList<>();
            java.util.Map<String, String> branchHeads = new java.util.HashMap<>();
            String headCommit = null;
            String defaultBranch = null;
            for (Ref ref : refs) {
                if (ref.getName().equals(Constants.HEAD) && ref.isSymbolic()
                        && ref.getTarget() != null
                        && ref.getTarget().getName().startsWith(Constants.R_HEADS)) {
                    defaultBranch = ref.getTarget().getName()
                            .substring(Constants.R_HEADS.length());
                }
                if (ref.getName().startsWith(Constants.R_HEADS)) {
                    String name = ref.getName().substring(Constants.R_HEADS.length());
                    branches.add(name);
                    if (ref.getObjectId() != null) {
                        branchHeads.put(name, ref.getObjectId().name());
                    }
                }
            }
            branches.sort(Comparator.naturalOrder());
            if (!branches.isEmpty()) {
                if (defaultBranch == null || !branches.contains(defaultBranch)) {
                    defaultBranch = branches.contains("main") ? "main" : branches.get(0);
                }
                for (Ref ref : refs) {
                    if (ref.getName().equals(Constants.R_HEADS + defaultBranch)
                            && ref.getObjectId() != null) {
                        headCommit = ref.getObjectId().name();
                    }
                }
            }
            return new RemoteProbe(defaultBranch, branches, headCommit, branchHeads);
        } catch (GitAPIException exception) {
            KnownHostKey unknown = ssh == null ? null : ssh.rejectedHostKey();
            if (unknown != null) {
                throw new UnknownHostKeyException(unknown);
            }
            throw translate(exception);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    private void prepareExistingRepository(Path localPath, String remoteUrl, String targetBranch,
                                           List<String> remoteBranches, String privateKeyPath,
                                           char[] passphrase)
            throws IOException, GitAPIException, SyncException, UnknownHostKeyException {
        try (Git git = Git.open(localPath.toFile())) {
            if (git.getRepository().getBranch().equals(targetBranch)) {
                return;
            }
            Ref localBranch = git.getRepository().exactRef(Constants.R_HEADS + targetBranch);
            if (localBranch != null) {
                git.checkout().setName(targetBranch).call();
                return;
            }
            if (remoteBranches.contains(targetBranch)) {
                String tracking = "refs/syncplugins/switch/" + targetBranch;
                fetchBranch(git, remoteUrl, targetBranch, tracking, privateKeyPath, passphrase);
                Ref fetched = git.getRepository().exactRef(tracking);
                if (fetched == null || fetched.getObjectId() == null) {
                    throw new SyncException(SyncFailure.REMOTE_CONFLICT,
                            "无法获取远端分支：" + targetBranch);
                }
                git.checkout().setCreateBranch(true).setName(targetBranch)
                        .setStartPoint(fetched.getObjectId().name()).call();
            } else {
                git.checkout().setCreateBranch(true).setName(targetBranch)
                        .setStartPoint(Constants.HEAD).call();
            }
        }
    }

    private void fetchBranch(Git git, String remoteUrl, String branch, String tracking,
                             String privateKeyPath, char[] passphrase)
            throws GitAPIException, SyncException, UnknownHostKeyException {
        SshTransportFactory ssh = null;
        try {
            if (isSshRemote(remoteUrl)) {
                ssh = new SshTransportFactory(Path.of(privateKeyPath).toAbsolutePath().normalize(),
                        passphrase, knownHostsStore.file());
            }
            var command = git.fetch()
                    .setRemote(remoteUrl)
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                            "+" + Constants.R_HEADS + branch + ":" + tracking))
                    .setTimeout(30);
            if (ssh != null) {
                SshTransportFactory factory = ssh;
                command.setTransportConfigCallback(transport -> configureSsh(transport, factory));
            }
            command.call();
        } catch (GitAPIException exception) {
            KnownHostKey unknown = ssh == null ? null : ssh.rejectedHostKey();
            if (unknown != null) {
                throw new UnknownHostKeyException(unknown);
            }
            throw translate(exception);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    private static void switchToBranch(Path localPath, String targetBranch, boolean onlyIfExists)
            throws IOException, GitAPIException {
        try (Git git = Git.open(localPath.toFile())) {
            if (git.getRepository().getBranch().equals(targetBranch)) {
                return;
            }
            Ref local = git.getRepository().exactRef(Constants.R_HEADS + targetBranch);
            if (local != null) {
                git.checkout().setName(targetBranch).call();
            } else if (!onlyIfExists) {
                git.checkout().setCreateBranch(true).setName(targetBranch)
                        .setStartPoint(Constants.HEAD).call();
            }
        }
    }

    private Path cloneForInspection(String remoteUrl, String branch, String privateKeyPath,
                                    char[] passphrase)
            throws SyncException, UnknownHostKeyException {
        try {
            Path temporary = Files.createTempDirectory("syncplugins-remote-");
            Path target = temporary.resolve("repo");
            cloneInto(target, remoteUrl, branch, privateKeyPath, passphrase);
            return target;
        } catch (IOException | GitAPIException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法创建临时比较目录：" + exception.getMessage(), exception);
        }
    }

    private void cloneInto(Path localPath, String remoteUrl, String branch,
                           String privateKeyPath, char[] passphrase)
            throws IOException, GitAPIException, SyncException, UnknownHostKeyException {
        Files.createDirectories(localPath);
        if (!isMissingOrEmpty(localPath)) {
            throw new SyncException(SyncFailure.WORKTREE, "克隆目标目录必须为空：" + localPath);
        }
        SshTransportFactory ssh = null;
        try {
            if (isSshRemote(remoteUrl)) {
                ssh = new SshTransportFactory(Path.of(privateKeyPath).toAbsolutePath().normalize(),
                        passphrase, knownHostsStore.file());
            }
            CloneCommand command = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setBranch(branch)
                    .setDirectory(localPath.toFile())
                    .setTimeout(60);
            if (ssh != null) {
                SshTransportFactory factory = ssh;
                command.setTransportConfigCallback(transport -> configureSsh(transport, factory));
            }
            command.call().close();
        } catch (GitAPIException exception) {
            KnownHostKey unknown = ssh == null ? null : ssh.rejectedHostKey();
            if (unknown != null) {
                throw new UnknownHostKeyException(unknown);
            }
            throw translate(exception);
        } finally {
            if (ssh != null) {
                ssh.close();
            }
        }
    }

    private static void initialize(Path localPath, String branch) throws IOException, GitAPIException {
        try (Git git = Git.init().setDirectory(localPath.toFile()).setInitialBranch(branch).call()) {
            // Repository stays uncommitted until the file queue creates its first commit.
        }
    }

    private static void applyConflictResolution(Path localPath, Path remoteClone,
                                                WorkspaceInspection inspection,
                                                ConflictResolution resolution) throws IOException {
        String timestamp = CONFLICT_TIME.format(Instant.now());
        if (resolution == ConflictResolution.KEEP_BOTH) {
            for (String relative : inspection.conflictingFiles()) {
                Path localFile = localPath.resolve(relative);
                if (Files.isRegularFile(localFile)) {
                    Path renamed = conflictName(localFile, timestamp);
                    Files.move(localFile, renamed, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        if (resolution == ConflictResolution.CLOUD_WINS) {
            clearDirectory(localPath);
        }
        Path source = resolution == ConflictResolution.LOCAL_WINS
                ? remoteClone.resolve(".git")
                : remoteClone;
        copyTree(source, localPath.resolve(source.equals(remoteClone.resolve(".git")) ? ".git" : ""));
        if (resolution == ConflictResolution.LOCAL_WINS) {
            Path gitTarget = localPath.resolve(".git");
            if (!Files.exists(gitTarget)) {
                copyTree(remoteClone.resolve(".git"), gitTarget);
            }
        }
    }

    private static Path conflictName(Path file, String timestamp) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String renamed = dot > 0
                ? name.substring(0, dot) + ".local-" + timestamp + name.substring(dot)
                : name + ".local-" + timestamp;
        return file.resolveSibling(renamed);
    }

    private static DiffResult compareTrees(Path local, Path remote) throws IOException {
        Set<String> localFiles = listFiles(local);
        Set<String> remoteFiles = listFiles(remote);
        List<String> localOnly = new ArrayList<>();
        List<String> remoteOnly = new ArrayList<>();
        List<String> conflicting = new ArrayList<>();
        for (String relative : localFiles) {
            if (!remoteFiles.contains(relative)) {
                localOnly.add(relative);
            } else if (!sameContent(local.resolve(relative), remote.resolve(relative))) {
                conflicting.add(relative);
            }
        }
        for (String relative : remoteFiles) {
            if (!localFiles.contains(relative)) {
                remoteOnly.add(relative);
            }
        }
        localOnly.sort(String::compareTo);
        remoteOnly.sort(String::compareTo);
        conflicting.sort(String::compareTo);
        return new DiffResult(localOnly, remoteOnly, conflicting);
    }

    private static Set<String> listFiles(Path root) throws IOException {
        Set<String> result = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return directory.getFileName() != null
                        && directory.getFileName().toString().equals(".git")
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (Files.isRegularFile(file)) {
                    result.add(root.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static boolean sameContent(Path left, Path right) throws IOException {
        if (Files.size(left) != Files.size(right)) {
            return false;
        }
        try {
            return MessageDigest.isEqual(sha256(left), sha256(right));
        } catch (Exception exception) {
            throw new IOException("无法比较文件内容：" + left, exception);
        }
    }

    private static byte[] sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Path destination = target.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void clearDirectory(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                BackupManager.deleteTree(child);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            BackupManager.deleteTree(path);
        } catch (IOException ignored) {
            // Best-effort temporary cleanup.
        }
    }

    private static boolean isExistingRepository(Path path) throws SyncException {
        return isGitRepository(path);
    }

    private static boolean isGitRepository(Path path) throws SyncException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (org.eclipse.jgit.lib.Repository repository =
                     new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                             .findGitDir(path.toFile()).build()) {
            return repository.getWorkTree().toPath().toRealPath().equals(path.toRealPath());
        } catch (IllegalArgumentException exception) {
            return false;
        } catch (IOException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法检查本地 Git 仓库：" + exception.getMessage(), exception);
        }
    }

    private static void rejectNestedRepository(Path path) throws SyncException {
        Path parent = path.getParent();
        while (parent != null) {
            if (Files.isDirectory(parent.resolve(".git"))) {
                throw new SyncException(SyncFailure.WORKTREE,
                        "不能在另一个 Git 仓库内部创建嵌套仓库：" + parent);
            }
            parent = parent.getParent();
        }
    }

    private static boolean isMissingOrEmpty(Path path) throws SyncException {
        if (!Files.exists(path)) {
            return true;
        }
        if (!Files.isDirectory(path)) {
            throw new SyncException(SyncFailure.WORKTREE, "本地路径不是文件夹：" + path);
        }
        try (var children = Files.list(path)) {
            return children.findAny().isEmpty();
        } catch (IOException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法读取本地目录：" + exception.getMessage(), exception);
        }
    }

    private String effectiveBranch(Path localPath, String requested, RemoteProbe remote)
            throws SyncException {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (remote.defaultBranch() != null) {
            return remote.defaultBranch();
        }
        if (isExistingRepository(localPath)) {
            try (Git git = Git.open(localPath.toFile())) {
                return git.getRepository().getBranch();
            } catch (IOException exception) {
                throw new SyncException(SyncFailure.WORKTREE,
                        "无法读取本地分支：" + exception.getMessage(), exception);
            }
        }
        return "main";
    }

    private static String currentBranch(Path localPath) throws SyncException {
        try (Git git = Git.open(localPath.toFile())) {
            return git.getRepository().getBranch();
        } catch (IOException exception) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "无法读取当前分支：" + exception.getMessage(), exception);
        }
    }

    private static void validateRemoteBranch(RemoteProbe remote, String branch) throws SyncException {
        if (!remote.branches().isEmpty() && !remote.branches().contains(branch)) {
            throw new SyncException(SyncFailure.REMOTE_CONFLICT,
                    "GitHub 远端不存在分支：" + branch);
        }
    }

    private static void validateBranchName(String branch) throws SyncException {
        if (branch == null || branch.isBlank()
                || !org.eclipse.jgit.lib.Repository.isValidRefName(Constants.R_HEADS + branch)) {
            throw new SyncException(SyncFailure.WORKTREE,
                    "Git 分支名称无效：" + branch);
        }
    }

    private static boolean isSshRemote(String remoteUrl) {
        String lower = remoteUrl == null ? "" : remoteUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("ssh://") || (!lower.contains("://") && lower.contains("@"));
    }

    private static SyncException translate(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("auth") || lower.contains("permission denied")
                || lower.contains("not allowed")) {
            return new SyncException(SyncFailure.AUTHENTICATION,
                    "SSH 认证失败，请检查私钥和 GitHub 公钥配置：" + message, exception);
        }
        if (lower.contains("connect") || lower.contains("timeout")
                || lower.contains("unreachable") || lower.contains("network")) {
            return new SyncException(SyncFailure.NETWORK, "无法连接 GitHub：" + message, exception);
        }
        return new SyncException(SyncFailure.UNKNOWN, "仓库操作失败：" + message, exception);
    }

    private static void configureSsh(Transport transport, SshTransportFactory ssh) {
        if (transport instanceof SshTransport sshTransport) {
            sshTransport.setSshSessionFactory(ssh.sessionFactory());
        }
    }

    public record RemoteProbe(String defaultBranch, List<String> branches, String head,
                              java.util.Map<String, String> branchHeads) {
        public String headFor(String branch) {
            return branchHeads.get(branch);
        }
    }

    private record DiffResult(List<String> localOnly, List<String> remoteOnly,
                              List<String> conflicting) {
    }
}
