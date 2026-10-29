package com.syncplugins.sync;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.config.ConfigStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.file.attribute.FileTime;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SyncControllerIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void watchesDirectoryDebouncesAndPushesAutomatically() throws Exception {
        Path remote = temp.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            // Simulated GitHub remote.
        }
        Path work = temp.resolve("work");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve("base.txt"), "base");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
            git.push().setRemote(remote.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }

        AppConfig config = new AppConfig();
        config.repositoryPath = work.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "main";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        config.privateKeyPath = "";
        config.debounceMillis = 500;
        config.maxDebounceMillis = 2_000;
        config.pollIntervalSeconds = 10;

        Path configFile = temp.resolve("config.json");
        Path stateFile = temp.resolve("state.json");
        new ConfigStore(configFile).save(config);
        AppState state = new AppState();
        try (SyncController controller = new SyncController(
                configFile, stateFile, temp.resolve("secret.bin"), temp.resolve("known_hosts"),
                temp.resolve("backups"))) {
            controller.start(config, state);
            Thread.sleep(500);
            Files.writeString(work.resolve("watched.txt"), "automatic");

            await(Duration.ofSeconds(8), () -> "automatic".equals(remoteFile(remote, "watched.txt")));
            assertThat(controller.status().lastError()).isBlank();
        }
    }

    @Test
    void drainsMultipleFilesOneByOneInModificationOrder() throws Exception {
        Path remote = temp.resolve("queue-remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            // Simulated GitHub remote.
        }
        Path work = temp.resolve("queue-work");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve("base.txt"), "base");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
            git.push().setRemote(remote.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }
        Files.writeString(work.resolve("z.txt"), "z");
        Files.writeString(work.resolve("a.txt"), "a");
        Files.writeString(work.resolve("m.txt"), "m");
        Files.setLastModifiedTime(work.resolve("a.txt"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(work.resolve("m.txt"), FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(work.resolve("z.txt"), FileTime.fromMillis(3_000));

        AppConfig config = new AppConfig();
        config.repositoryPath = work.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "main";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        config.privateKeyPath = "";
        config.debounceMillis = 500;
        config.maxDebounceMillis = 2_000;
        config.pollIntervalSeconds = 10;
        Path configFile = temp.resolve("queue-config.json");
        new ConfigStore(configFile).save(config);
        try (SyncController controller = new SyncController(
                configFile, temp.resolve("queue-state.json"), temp.resolve("queue-secret.bin"),
                temp.resolve("queue-known_hosts"), temp.resolve("queue-backups"))) {
            var statuses = new CopyOnWriteArrayList<SyncStatus>();
            controller.addListener(statuses::add);
            controller.start(config, new AppState());
            await(Duration.ofSeconds(20), () ->
                    remoteFile(remote, "a.txt") != null
                            && remoteFile(remote, "m.txt") != null
                            && remoteFile(remote, "z.txt") != null);
            try (Git git = Git.open(work.toFile())) {
                var messages = new java.util.ArrayList<String>();
                git.log().setMaxCount(3).call().forEach(commit -> messages.add(commit.getFullMessage()));
                assertThat(messages.get(0)).contains("z.txt");
                assertThat(messages.get(1)).contains("m.txt");
                assertThat(messages.get(2)).contains("a.txt");
            }
            assertThat(statuses).anySatisfy(status -> {
                assertThat(status.progressTotal()).isEqualTo(3);
                assertThat(status.currentFile()).isNotBlank();
            });
        }
    }

    @Test
    void skipsOversizeFileAndContinuesWithLaterFiles() throws Exception {
        Path remote = temp.resolve("skip-remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            // Simulated GitHub remote.
        }
        Path work = temp.resolve("skip-work");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve("base.txt"), "base");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
            git.push().setRemote(remote.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }
        Path large = work.resolve("large.bin");
        try (var channel = Files.newByteChannel(large,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(100L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }
        Files.writeString(work.resolve("good.txt"), "good");
        Files.setLastModifiedTime(large, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(work.resolve("good.txt"), FileTime.fromMillis(2_000));

        AppConfig config = new AppConfig();
        config.repositoryPath = work.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "main";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        config.privateKeyPath = "";
        config.debounceMillis = 500;
        config.maxDebounceMillis = 2_000;
        config.pollIntervalSeconds = 10;
        Path configFile = temp.resolve("skip-config.json");
        new ConfigStore(configFile).save(config);

        try (SyncController controller = new SyncController(
                configFile, temp.resolve("skip-state.json"), temp.resolve("skip-secret.bin"),
                temp.resolve("skip-known_hosts"), temp.resolve("skip-backups"))) {
            controller.start(config, new AppState());
            await(Duration.ofSeconds(20), () -> "good".equals(remoteFile(remote, "good.txt")));
            await(Duration.ofSeconds(5), () -> controller.status().failedCount() == 1);
            assertThat(controller.status().lastError()).contains("100 MB");
            assertThat(remoteFile(remote, "large.bin")).isNull();
        }
    }

    private static void await(Duration timeout, ThrowingBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待条件超时：" + timeout);
    }

    private static String remoteFile(Path remote, String file) throws Exception {
        try (Git git = Git.open(remote.toFile())) {
            ObjectId head = git.getRepository().resolve("refs/heads/main");
            if (head == null) {
                return null;
            }
            ObjectId tree;
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(head);
                tree = commit.getTree().getId();
            }
            try (var walk = new org.eclipse.jgit.treewalk.TreeWalk(git.getRepository())) {
                walk.addTree(tree);
                walk.setRecursive(true);
                while (walk.next()) {
                    if (walk.getPathString().equals(file)) {
                        return new String(git.getRepository().open(walk.getObjectId(0)).getBytes());
                    }
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
