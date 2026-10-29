package com.syncplugins.workspace;

import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.git.GitRepositoryInspector;
import com.syncplugins.git.GitSyncEngine;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceBootstrapServiceTest {
    @TempDir
    Path temp;

    @Test
    void initializesOrdinaryDirectoryAgainstEmptyRemote() throws Exception {
        Path remote = bareRemote("init-remote.git");
        Path local = temp.resolve("ordinary-folder");
        Files.createDirectories(local);
        Files.writeString(local.resolve("one.txt"), "one");
        WorkspaceBootstrapService service = service();

        WorkspaceInspection inspection = service.inspect(local.toString(),
                remote.toUri().toString(), "main", "", new char[0]);
        service.prepare(local.toString(), remote.toUri().toString(), "main",
                "", new char[0], null, 3);

        assertThat(inspection.mode()).isEqualTo(WorkspaceMode.INIT_LOCAL);
        assertThat(local.resolve(".git")).exists();
        assertThat(local.resolve("one.txt")).hasContent("one");

        GitRepositoryInspector inspector = new GitRepositoryInspector();
        GitSyncEngine engine = new GitSyncEngine(inspector,
                new KnownHostsStore(temp.resolve("init-known_hosts")));
        AppConfig config = new AppConfig();
        config.repositoryPath = local.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "main";
        config.privateKeyPath = "";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        AppState state = new AppState();
        for (var change : inspector.listPendingChanges(local.toString())) {
            engine.synchronizeSingle(config, state, new char[0], change);
        }
        try (Git remoteGit = Git.open(remote.toFile())) {
            assertThat(remoteGit.getRepository().resolve("refs/heads/main")).isNotNull();
        }
    }

    @Test
    void createsMissingDirectoryAndClonesRemote() throws Exception {
        Path remote = remoteWithFiles("clone-remote.git", Map.of(
                "remote.txt", "from cloud",
                "nested/data.txt", "nested"));
        Path local = temp.resolve("new-download-location");
        WorkspaceBootstrapService service = service();

        WorkspaceInspection inspection = service.inspect(local.toString(),
                remote.toUri().toString(), "main", "", new char[0]);
        service.prepare(local.toString(), remote.toUri().toString(), "main",
                "", new char[0], null, 3);

        assertThat(inspection.mode()).isEqualTo(WorkspaceMode.CLONE_REMOTE);
        assertThat(local.resolve("remote.txt")).hasContent("from cloud");
        assertThat(local.resolve("nested/data.txt")).hasContent("nested");
    }

    @Test
    void createsNewRemoteBranchFromCurrentHead() throws Exception {
        Path remote = bareRemote("new-branch-remote.git");
        Path local = temp.resolve("new-branch-local");
        Files.createDirectories(local);
        Files.writeString(local.resolve("feature.txt"), "feature");
        WorkspaceBootstrapService service = service();
        WorkspaceInspection inspection = service.inspect(local.toString(),
                remote.toUri().toString(), "feature/demo", "", new char[0]);

        assertThat(inspection.createsBranch()).isTrue();
        service.prepare(local.toString(), remote.toUri().toString(), "feature/demo",
                "", new char[0], null, 3);

        AppConfig config = new AppConfig();
        config.repositoryPath = local.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "feature/demo";
        config.privateKeyPath = "";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        GitRepositoryInspector inspector = new GitRepositoryInspector();
        GitSyncEngine engine = new GitSyncEngine(inspector,
                new KnownHostsStore(temp.resolve("new-branch-known_hosts")));
        AppState state = new AppState();
        for (var change : inspector.listPendingChanges(local.toString())) {
            engine.synchronizeSingle(config, state, new char[0], change);
        }
        try (Git remoteGit = Git.open(remote.toFile())) {
            assertThat(remoteGit.getRepository().resolve("refs/heads/feature/demo")).isNotNull();
        }
    }

    @Test
    void createsNewLocalBranchInExistingRepository() throws Exception {
        Path local = temp.resolve("existing-local");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(local.toFile()).call()) {
            Files.writeString(local.resolve("base.txt"), "base");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
        }
        Path remote = bareRemote("existing-branch-remote.git");
        WorkspaceBootstrapService service = service();

        WorkspaceInspection inspection = service.inspect(local.toString(),
                remote.toUri().toString(), "feature/local", "", new char[0]);
        service.prepare(local.toString(), remote.toUri().toString(), "feature/local",
                "", new char[0], null, 3);

        assertThat(inspection.createsBranch()).isTrue();
        assertThat(inspection.baseBranch()).isEqualTo("main");
        try (Git git = Git.open(local.toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo("feature/local");
        }
    }

    @Test
    void previewsAndResolvesConflictModes() throws Exception {
        Path remote = remoteWithFiles("conflict-remote.git", Map.of(
                "same.txt", "remote",
                "remote-only.txt", "remote-only"));
        WorkspaceBootstrapService service = service();

        Path cloud = localConflict("cloud-local");
        WorkspaceInspection cloudInspection = service.inspect(cloud.toString(),
                remote.toUri().toString(), "main", "", new char[0]);
        assertThat(cloudInspection.mode()).isEqualTo(WorkspaceMode.CONFLICT);
        assertThat(cloudInspection.conflictingFiles()).containsExactly("same.txt");
        assertThat(cloudInspection.localOnlyFiles()).containsExactly("local-only.txt");
        assertThat(cloudInspection.remoteOnlyFiles()).containsExactly("remote-only.txt");
        service.prepare(cloud.toString(), remote.toUri().toString(), "main",
                "", new char[0], ConflictResolution.CLOUD_WINS, 3);
        assertThat(cloud.resolve("same.txt")).hasContent("remote");
        assertThat(cloud.resolve("local-only.txt")).doesNotExist();
        assertThat(serviceBackups()).hasSize(1);

        Path local = localConflict("local-win");
        service.prepare(local.toString(), remote.toUri().toString(), "main",
                "", new char[0], ConflictResolution.LOCAL_WINS, 3);
        assertThat(local.resolve("same.txt")).hasContent("local");
        assertThat(local.resolve("local-only.txt")).hasContent("keep-local");
        assertThat(local.resolve("remote-only.txt")).doesNotExist();
        assertThat(local.resolve(".git")).exists();

        Path both = localConflict("both-win");
        service.prepare(both.toString(), remote.toUri().toString(), "main",
                "", new char[0], ConflictResolution.KEEP_BOTH, 3);
        assertThat(both.resolve("same.txt")).hasContent("remote");
        assertThat(both.resolve("local-only.txt")).hasContent("keep-local");
        assertThat(both.resolve("remote-only.txt")).hasContent("remote-only");
        try (var files = Files.list(both)) {
            assertThat(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("same.local-"))).isTrue();
        }
    }

    private Path localConflict(String name) throws Exception {
        Path local = temp.resolve(name);
        Files.createDirectories(local);
        Files.writeString(local.resolve("same.txt"), "local");
        Files.writeString(local.resolve("local-only.txt"), "keep-local");
        return local;
    }

    private WorkspaceBootstrapService service() {
        return new WorkspaceBootstrapService(
                new KnownHostsStore(temp.resolve("known_hosts")),
                new BackupManager(temp.resolve("backups")));
    }

    private java.util.List<BackupRecord> serviceBackups() {
        return new BackupManager(temp.resolve("backups")).list();
    }

    private Path bareRemote(String name) throws Exception {
        Path remote = temp.resolve(name);
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            return remote;
        }
    }

    private Path remoteWithFiles(String name, Map<String, String> files) throws Exception {
        Path remote = bareRemote(name);
        Path work = temp.resolve(name + "-source");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call()) {
            for (var entry : files.entrySet()) {
                Path file = work.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
            }
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
            git.push().setRemote(remote.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }
        return remote;
    }
}
