package com.syncplugins.sync;

import com.syncplugins.config.AppConfig;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTaskControllerTest {
    @TempDir
    Path temp;

    @Test
    void monitorsAndPushesTwoIndependentWorkspacesConcurrently() throws Exception {
        Fixture first = fixture("A");
        Fixture second = fixture("B");
        Files.writeString(first.work().resolve("a.txt"), "A");
        Files.writeString(second.work().resolve("b.txt"), "B");

        Path tasksDir = temp.resolve("tasks");
        Path statesDir = temp.resolve("states");
        Files.createDirectories(tasksDir);
        new ConfigStore(tasksDir.resolve("task-a.json")).save(first.config());
        new ConfigStore(tasksDir.resolve("task-b.json")).save(second.config());

        try (MultiTaskController controller = new MultiTaskController(
                tasksDir, statesDir, temp.resolve("secret.bin"),
                temp.resolve("known_hosts"), temp.resolve("backups"))) {
            List<List<TaskSnapshot>> updates = new CopyOnWriteArrayList<>();
            controller.addListener(updates::add);
            controller.start(List.of(first.config(), second.config()));

            try {
                await(Duration.ofSeconds(20), () ->
                        remoteFile(first.remote(), "a.txt") != null
                                && remoteFile(second.remote(), "b.txt") != null);
            } catch (AssertionError error) {
                String diagnostics = controller.snapshots().stream()
                        .map(snapshot -> snapshot.config().taskName + " => " + snapshot.status())
                        .reduce("", (left, right) -> left + "\n" + right);
                throw new AssertionError(error.getMessage() + diagnostics, error);
            }

            assertThat(controller.snapshots()).hasSize(2);
            assertThat(updates).anySatisfy(tasks -> assertThat(tasks).hasSize(2));
        }
    }

    private Fixture fixture(String suffix) throws Exception {
        Path remote = temp.resolve("remote-" + suffix + ".git");
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            // Bare remote.
        }
        Path work = temp.resolve("work-" + suffix);
        try (Git git = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve("base.txt"), suffix);
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
        config.taskId = "task-" + suffix.toLowerCase();
        config.taskName = "Task " + suffix;
        config.repositoryPath = work.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "main";
        config.privateKeyPath = "";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        config.debounceMillis = 500;
        config.pollIntervalSeconds = 10;
        return new Fixture(work, remote, config);
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

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private record Fixture(Path work, Path remote, AppConfig config) {
    }
}
