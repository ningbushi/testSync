package com.syncplugins.git;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.workspace.BackupManager;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CloudSyncServiceTest {
    @TempDir
    Path temp;

    @Test
    void detectsRemoteUpdateBacksUpDirtyFilesAndDownloads() throws Exception {
        Path remote = temp.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            // Empty bare remote.
        }
        Path seed = temp.resolve("seed");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
            Files.writeString(seed.resolve("file.txt"), "base");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
            git.push().setRemote(remote.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }

        Path local = temp.resolve("local");
        try (Git ignored = Git.cloneRepository()
                .setURI(remote.toUri().toString())
                .setBranch("main")
                .setDirectory(local.toFile())
                .call()) {
            // Local clone.
        }
        AppConfig config = new AppConfig();
        config.repositoryPath = local.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "main";
        config.privateKeyPath = "";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        AppState state = new AppState();
        state.lastRemoteCommit = head(local);

        Path other = temp.resolve("other");
        try (Git clone = Git.cloneRepository()
                .setURI(remote.toUri().toString())
                .setBranch("main")
                .setDirectory(other.toFile())
                .call()) {
            Files.writeString(other.resolve("file.txt"), "cloud update");
            Files.writeString(other.resolve("cloud-only.txt"), "cloud");
            clone.add().addFilepattern(".").call();
            clone.commit().setMessage("cloud")
                    .setAuthor("Other", "other@example.com")
                    .setCommitter("Other", "other@example.com")
                    .call();
            clone.push().setRemote("origin").call();
        }

        Files.writeString(local.resolve("file.txt"), "local change");
        Files.writeString(local.resolve("local-untracked.txt"), "local");
        BackupManager backups = new BackupManager(temp.resolve("backups"));
        CloudSyncService cloud = new CloudSyncService(
                new KnownHostsStore(temp.resolve("known_hosts")), backups);

        assertThat(cloud.check(config, state, new char[0]).updateAvailable()).isTrue();
        DownloadResult result = cloud.download(config, state, new char[0]);

        assertThat(result.changed()).isTrue();
        assertThat(result.backup()).isNotNull();
        assertThat(local.resolve("file.txt")).hasContent("cloud update");
        assertThat(local.resolve("cloud-only.txt")).hasContent("cloud");
        assertThat(local.resolve("local-untracked.txt")).doesNotExist();
        assertThat(backups.list()).hasSize(1);

        backups.restore(result.backup());
        assertThat(local.resolve("file.txt")).hasContent("local change");
        assertThat(local.resolve("local-untracked.txt")).hasContent("local");
    }

    @Test
    void keepsOnlyThreeBackups() throws Exception {
        Path local = temp.resolve("retention-local");
        Files.createDirectories(local);
        try (Git git = Git.init().setInitialBranch("main").setDirectory(local.toFile()).call()) {
            Files.writeString(local.resolve("file.txt"), "base");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
        }
        BackupManager manager = new BackupManager(temp.resolve("retention-backups"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(local.resolve("untracked-" + index + ".txt"), "value");
            manager.create(local, 3);
            Thread.sleep(2);
        }

        assertThat(manager.list()).hasSize(3);
    }

    private static String head(Path repository) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            return head == null ? null : head.name();
        }
    }
}
