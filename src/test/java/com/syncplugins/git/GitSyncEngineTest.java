package com.syncplugins.git;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.security.KnownHostsStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitSyncEngineTest {
    @TempDir
    Path temp;

    @Test
    void commitsAndPushesInitialDirtyChanges() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.work.resolve("new file.txt"), "hello");
        Files.createDirectories(fixture.work.resolve("nested"));
        Files.writeString(fixture.work.resolve("nested/data.txt"), "nested");

        SyncOutcome outcome = fixture.engine.synchronize(fixture.config, fixture.state, new char[0]);

        String diagnostic;
        try (Git git = Git.open(fixture.work.toFile())) {
            diagnostic = "status=" + git.status().call()
                    + ", local=" + localHead(fixture.work)
                    + ", remote=" + remoteHead(fixture.remote)
                    + ", outcome=" + outcome;
        }
        assertThat(outcome.status()).withFailMessage(diagnostic).isEqualTo(SyncOutcome.Status.PUSHED);
        assertThat(outcome.changedFiles()).isEqualTo(2);
        assertThat(remoteFile(fixture.remote, "new file.txt")).isEqualTo("hello");
        assertThat(remoteFile(fixture.remote, "nested/data.txt")).isEqualTo("nested");
        assertThat(fixture.state.pendingPush).isFalse();
        assertThat(fixture.state.lastRemoteCommit).isEqualTo(remoteHead(fixture.remote));
        assertThat(lastCommitMessage(fixture.work)).startsWith("SyncPlugins auto-sync:");
    }

    @Test
    void synchronizesModificationRenameAndDeletion() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.work.resolve("base.txt"), "changed");
        Files.move(fixture.work.resolve("old.txt"), fixture.work.resolve("renamed.txt"));
        Files.delete(fixture.work.resolve("delete-me.txt"));
        fixture.engine.synchronize(fixture.config, fixture.state, new char[0]);

        assertThat(remoteFile(fixture.remote, "base.txt")).isEqualTo("changed");
        assertThat(remoteFile(fixture.remote, "renamed.txt")).isEqualTo("old");
        assertThat(remoteFile(fixture.remote, "delete-me.txt")).isNull();
        assertThat(remoteFile(fixture.remote, "old.txt")).isNull();
    }

    @Test
    void ignoresGitIgnoredFilesAndAvoidsEmptyCommit() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.work.resolve("ignored.log"), "ignored");
        String before = localHead(fixture.work);

        SyncOutcome outcome = fixture.engine.synchronize(fixture.config, fixture.state, new char[0]);

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.NO_CHANGES);
        assertThat(localHead(fixture.work)).isEqualTo(before);
        assertThat(remoteFile(fixture.remote, "ignored.log")).isNull();
    }

    @Test
    void pausesWhenRemoteMovedAfterPreviousSync() throws Exception {
        Fixture fixture = fixture();
        fixture.engine.synchronize(fixture.config, fixture.state, new char[0]);
        Files.writeString(fixture.work.resolve("local.txt"), "local edit");
        String localHead = localHead(fixture.work);

        Path other = temp.resolve("other");
        try (Git clone = Git.cloneRepository()
                .setURI(fixture.remote.toUri().toString())
                .setBranch("main")
                .setDirectory(other.toFile())
                .call()) {
            Files.writeString(other.resolve("remote.txt"), "remote edit");
            clone.add().addFilepattern(".").call();
            clone.commit().setMessage("remote edit")
                    .setAuthor("Other", "other@example.com")
                    .setCommitter("Other", "other@example.com")
                    .call();
            clone.push().setRemote("origin").call();
        }

        assertThatThrownBy(() -> fixture.engine.synchronize(
                fixture.config, fixture.state, new char[0]))
                .isInstanceOfSatisfying(SyncException.class,
                        exception -> assertThat(exception.failure())
                                .withFailMessage(exception.getMessage())
                                .isEqualTo(SyncFailure.REMOTE_CONFLICT));
        assertThat(localHead(fixture.work)).isEqualTo(localHead);
        assertThat(Files.readString(fixture.work.resolve("local.txt"))).isEqualTo("local edit");
        assertThat(remoteFile(fixture.remote, "remote.txt")).isEqualTo("remote edit");
        assertThat(remoteFile(fixture.remote, "local.txt")).isNull();
    }

    @Test
    void rejectsFilesAboveGitHubLimitBeforeStaging() throws Exception {
        Fixture fixture = fixture();
        Path large = fixture.work.resolve("large.bin");
        try (var channel = Files.newByteChannel(large,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(GitSyncEngine.GITHUB_MAX_FILE_SIZE);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        String before = localHead(fixture.work);

        assertThatThrownBy(() -> fixture.engine.synchronize(
                fixture.config, fixture.state, new char[0]))
                .isInstanceOfSatisfying(SyncException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(SyncFailure.FILE_TOO_LARGE));
        assertThat(localHead(fixture.work)).isEqualTo(before);
        assertThat(remoteFile(fixture.remote, "large.bin")).isNull();
    }

    @Test
    void pushesExistingLocalCommitWhenRemoteIsAncestor() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.work.resolve("manual.txt"), "manual commit");
        try (Git git = Git.open(fixture.work.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("manual")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
        }

        SyncOutcome outcome = fixture.engine.synchronize(fixture.config, fixture.state, new char[0]);

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.PUSHED);
        assertThat(remoteFile(fixture.remote, "manual.txt")).isEqualTo("manual commit");
    }

    @Test
    void commitsLocallyWhileOfflineAndPushesAfterRecovery() throws Exception {
        Fixture fixture = fixture();
        fixture.engine.synchronize(fixture.config, fixture.state, new char[0]);
        String workingRemote = fixture.config.remoteUrl;
        fixture.config.remoteUrl = "http://127.0.0.1:1/unreachable.git";
        Files.writeString(fixture.work.resolve("offline.txt"), "saved locally");

        SyncOutcome offline = fixture.engine.synchronize(
                fixture.config, fixture.state, new char[0]);

        assertThat(offline.status()).isEqualTo(SyncOutcome.Status.COMMITTED_PENDING);
        assertThat(fixture.state.pendingPush).isTrue();
        assertThat(remoteFile(fixture.remote, "offline.txt")).isNull();
        assertThat(Files.readString(fixture.work.resolve("offline.txt"))).isEqualTo("saved locally");

        fixture.config.remoteUrl = workingRemote;
        SyncOutcome recovered = fixture.engine.synchronize(
                fixture.config, fixture.state, new char[0]);

        assertThat(recovered.status()).isEqualTo(SyncOutcome.Status.PUSHED);
        assertThat(fixture.state.pendingPush).isFalse();
        assertThat(remoteFile(fixture.remote, "offline.txt")).isEqualTo("saved locally");
    }

    @Test
    void pausesWhenRemoteBranchDisappears() throws Exception {
        Fixture fixture = fixture();
        fixture.engine.synchronize(fixture.config, fixture.state, new char[0]);
        try (Git git = Git.open(fixture.remote.toFile())) {
            var update = git.getRepository().updateRef("refs/heads/main");
            update.setForceUpdate(true);
            update.delete();
        }
        Files.writeString(fixture.work.resolve("pending.txt"), "pending");

        assertThatThrownBy(() -> fixture.engine.synchronize(
                fixture.config, fixture.state, new char[0]))
                .isInstanceOfSatisfying(SyncException.class,
                        exception -> assertThat(exception.failure())
                                .withFailMessage(exception.getMessage())
                                .isEqualTo(SyncFailure.REMOTE_CONFLICT));
        assertThat(Files.readString(fixture.work.resolve("pending.txt"))).isEqualTo("pending");
    }

    @Test
    void createsFirstCommitForUnbornBranchAndEmptyRemote() throws Exception {
        Path remote = temp.resolve("empty-remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            // Empty GitHub repository.
        }
        Path work = temp.resolve("empty-work");
        try (Git ignored = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call()) {
            // Local repository intentionally has no commits.
        }
        Files.writeString(work.resolve("redme.txt"), "first");
        AppConfig config = new AppConfig();
        config.repositoryPath = work.toString();
        config.remoteUrl = remote.toUri().toString();
        config.branch = "main";
        config.authorName = "Tester";
        config.authorEmail = "tester@example.com";
        config.privateKeyPath = "";
        AppState state = new AppState();
        GitSyncEngine engine = new GitSyncEngine(
                new GitRepositoryInspector(),
                new KnownHostsStore(temp.resolve("known_hosts-empty")));

        SyncOutcome outcome = engine.synchronize(config, state, new char[0]);

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.PUSHED);
        assertThat(remoteFile(remote, "redme.txt")).isEqualTo("first");
    }

    @Test
    void reportsPlainDirectoryAsInvalidGitRepository() throws Exception {
        Path plain = temp.resolve("plain-folder");
        Files.createDirectories(plain);

        assertThatThrownBy(() -> new GitRepositoryInspector().inspect(plain.toString()))
                .isInstanceOfSatisfying(SyncException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(SyncFailure.WORKTREE);
                    assertThat(exception.getMessage()).contains("不是 Git 仓库");
                });
    }

    @Test
    void commitsAndPushesOneFileAtATimeInModificationOrder() throws Exception {
        Fixture fixture = fixture();
        Path later = fixture.work.resolve("z-later.txt");
        Path earlier = fixture.work.resolve("a-earlier.txt");
        Path middle = fixture.work.resolve("m-middle.txt");
        Files.writeString(later, "later");
        Files.writeString(earlier, "earlier");
        Files.writeString(middle, "middle");
        Files.setLastModifiedTime(earlier, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(middle, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(later, FileTime.fromMillis(3_000));

        var pending = fixture.engineInspector().listPendingChanges(fixture.work.toString())
                .stream()
                .filter(change -> change.relativePath().endsWith(".txt"))
                .sorted(com.syncplugins.sync.PendingChange.ORDER)
                .toList();
        for (var change : pending) {
            fixture.engine.synchronizeSingle(fixture.config, fixture.state,
                    new char[0], change);
        }

        try (Git git = Git.open(fixture.work.toFile())) {
            var commits = new java.util.ArrayList<String>();
            git.log().setMaxCount(3).call().forEach(commit -> commits.add(commit.getFullMessage()));
            assertThat(commits.get(0)).contains("z-later.txt");
            assertThat(commits.get(1)).contains("m-middle.txt");
            assertThat(commits.get(2)).contains("a-earlier.txt");
        }
    }

    @Test
    void stagesAndCommitsSingleDeletion() throws Exception {
        Fixture fixture = fixture();
        Files.delete(fixture.work.resolve("delete-me.txt"));
        var deletion = fixture.engineInspector().listPendingChanges(fixture.work.toString())
                .stream()
                .filter(change -> change.relativePath().equals("delete-me.txt"))
                .findFirst()
                .orElseThrow();

        SyncOutcome outcome = fixture.engine.synchronizeSingle(
                fixture.config, fixture.state, new char[0], deletion);

        assertThat(outcome.changedFiles()).isEqualTo(1);
        assertThat(remoteFile(fixture.remote, "delete-me.txt")).isNull();
        assertThat(lastCommitMessage(fixture.work)).contains("delete delete-me.txt");
    }

    private Fixture fixture() throws Exception {
        Path remote = temp.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            // Bare remote is the simulated GitHub repository.
        }

        Path work = temp.resolve("work");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve("base.txt"), "base");
            Files.writeString(work.resolve("old.txt"), "old");
            Files.writeString(work.resolve("delete-me.txt"), "delete");
            Files.writeString(work.resolve(".gitignore"), "*.log\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
            git.push()
                    .setRemote(remote.toUri().toString())
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
        AppState state = new AppState();
        GitRepositoryInspector inspector = new GitRepositoryInspector();
        GitSyncEngine engine = new GitSyncEngine(
                inspector,
                new KnownHostsStore(temp.resolve("known_hosts")));
        return new Fixture(work, remote, config, state, engine, inspector);
    }

    private static String remoteFile(Path remote, String path) throws Exception {
        try (Git git = Git.open(remote.toFile())) {
            ObjectId head = git.getRepository().resolve("refs/heads/main");
            if (head == null) {
                return null;
            }
            try (var walk = new org.eclipse.jgit.treewalk.TreeWalk(git.getRepository())) {
                walk.addTree(recursiveTree(git, head));
                walk.setRecursive(true);
                while (walk.next()) {
                    if (walk.getPathString().equals(path)) {
                        return new String(git.getRepository().open(walk.getObjectId(0)).getBytes());
                    }
                }
                return null;
            }
        }
    }

    private static ObjectId recursiveTree(Git git, ObjectId commit) throws Exception {
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            RevCommit parsed = walk.parseCommit(commit);
            return parsed.getTree().getId();
        }
    }

    private static String localHead(Path work) throws Exception {
        try (Git git = Git.open(work.toFile())) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            return head == null ? null : head.name();
        }
    }

    private static String remoteHead(Path remote) throws Exception {
        try (Git git = Git.open(remote.toFile())) {
            ObjectId head = git.getRepository().resolve("refs/heads/main");
            return head == null ? null : head.name();
        }
    }

    private static String lastCommitMessage(Path work) throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.log().setMaxCount(1).call().iterator().next().getFullMessage();
        }
    }

    private record Fixture(Path work, Path remote, AppConfig config, AppState state,
                           GitSyncEngine engine, GitRepositoryInspector engineInspector) {
    }
}
