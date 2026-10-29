package com.syncplugins.workspace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.syncplugins.git.GitRepositoryInspector;
import com.syncplugins.git.SyncException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BackupManager {
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path root;

    public BackupManager(Path root) {
        this.root = root;
    }

    public BackupRecord create(Path localPath, int retention) throws IOException, SyncException {
        Files.createDirectories(root);
        String id = ID_FORMAT.format(Instant.now());
        Path backupDirectory = root.resolve(id);
        int suffix = 1;
        while (Files.exists(backupDirectory)) {
            backupDirectory = root.resolve(id + "-" + suffix++);
        }
        Files.createDirectories(backupDirectory);
        String gitRef = null;
        String originalBranch = "";
        String originalHead = "";
        boolean fileBackup;

        Path gitDir = findGitDirectory(localPath);
        if (gitDir == null) {
            copyTree(localPath, backupDirectory.resolve("files"), true);
            fileBackup = true;
        } else {
            try (Git git = Git.open(localPath.toFile())) {
                Repository repository = git.getRepository();
                originalBranch = repository.getBranch();
                ObjectId head = repository.resolve(Constants.HEAD);
                originalHead = head == null ? "" : head.name();
                RevCommit snapshot = git.stashCreate().call();
                ObjectId backupCommit = snapshot != null ? snapshot.getId() : head;
                if (backupCommit != null) {
                    gitRef = "refs/heads/syncplugins-backup/" + backupDirectory.getFileName();
                    RefUpdate update = repository.updateRef(gitRef);
                    update.setNewObjectId(backupCommit);
                    update.setForceUpdate(true);
                    update.update();
                }
                fileBackup = copyUntracked(git, localPath, backupDirectory.resolve("files"));
            } catch (GitAPIException exception) {
                copyTree(localPath, backupDirectory.resolve("files"), true);
                fileBackup = true;
            }
        }

        BackupRecord record = new BackupRecord(backupDirectory.getFileName().toString(),
                System.currentTimeMillis(), localPath.toAbsolutePath().toString(),
                originalBranch, originalHead, gitRef, fileBackup);
        MAPPER.writeValue(backupDirectory.resolve("backup.json").toFile(), record);
        prune(retention);
        return record;
    }

    public List<BackupRecord> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<BackupRecord> records = new ArrayList<>();
        try (var directories = Files.list(root)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                Path metadata = directory.resolve("backup.json");
                if (Files.isRegularFile(metadata)) {
                    try {
                        records.add(MAPPER.readValue(metadata.toFile(), BackupRecord.class));
                    } catch (IOException ignored) {
                        // Keep loading the other backups.
                    }
                }
            });
        } catch (IOException ignored) {
            return List.of();
        }
        records.sort(Comparator.comparingLong(BackupRecord::createdAt).reversed());
        return records;
    }

    public void restore(BackupRecord record) throws IOException, SyncException {
        Path localPath = Path.of(record.localPath());
        if (!Files.isDirectory(localPath)) {
            throw new IOException("备份对应的本地目录不存在：" + localPath);
        }
        if (record.gitBackupRef() != null && !record.gitBackupRef().isBlank()) {
            try (Git git = Git.open(localPath.toFile())) {
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .setRef(record.gitBackupRef()).call();
            } catch (GitAPIException exception) {
                throw new SyncException(com.syncplugins.git.SyncFailure.WORKTREE,
                        "无法恢复 Git 备份：" + exception.getMessage(), exception);
            }
        }
        Path files = root.resolve(record.id()).resolve("files");
        if (Files.isDirectory(files)) {
            copyTree(files, localPath, false);
        }
    }

    public void delete(BackupRecord record) throws IOException {
        Path directory = root.resolve(record.id()).normalize();
        if (!directory.startsWith(root.normalize())) {
            throw new IOException("备份路径无效");
        }
        if (record.gitBackupRef() != null && !record.gitBackupRef().isBlank()) {
            Path localPath = Path.of(record.localPath());
            if (Files.isDirectory(localPath)) {
                try (Git git = Git.open(localPath.toFile())) {
                    git.getRepository().updateRef(record.gitBackupRef()).delete();
                } catch (Exception ignored) {
                    // The file backup may be deleted even if its ref is already gone.
                }
            }
        }
        deleteTree(directory);
    }

    private void prune(int retention) {
        List<BackupRecord> records = list();
        for (int index = Math.max(1, retention); index < records.size(); index++) {
            try {
                delete(records.get(index));
            } catch (IOException ignored) {
                // A locked old backup can be removed on the next pass.
            }
        }
    }

    private static boolean copyUntracked(Git git, Path root, Path destination)
            throws IOException, GitAPIException {
        var untracked = git.status().call().getUntracked();
        boolean copied = false;
        for (String relative : untracked) {
            Path source = root.resolve(relative);
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Path target = destination.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            copied = true;
        }
        return copied;
    }

    static void copyTree(Path source, Path destination, boolean includeGit) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!includeGit && directory.getFileName() != null
                        && directory.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relative = source.relativize(directory);
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = source.relativize(file);
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path findGitDirectory(Path localPath) throws SyncException {
        try {
            Repository repository = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                    .findGitDir(localPath.toFile()).build();
            Path result = repository.getDirectory().toPath();
            repository.close();
            return result;
        } catch (IllegalArgumentException | IOException exception) {
            return null;
        }
    }
}

