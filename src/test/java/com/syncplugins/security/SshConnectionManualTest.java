package com.syncplugins.security;

import com.syncplugins.config.AppConfig;
import com.syncplugins.git.GitRepositoryInspector;
import com.syncplugins.git.GitSyncEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SshConnectionManualTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "SYNCPLUGINS_RUN_SSH_TEST", matches = "true")
    void connectsToConfiguredGithubRepository() throws Exception {
        AppConfig config = new AppConfig();
        config.repositoryPath = required("SYNCPLUGINS_TEST_REPOSITORY");
        config.remoteUrl = required("SYNCPLUGINS_TEST_REMOTE");
        config.branch = required("SYNCPLUGINS_TEST_BRANCH");
        config.privateKeyPath = required("SYNCPLUGINS_TEST_KEY");
        config.authorName = "Connection Test";
        config.authorEmail = "connection-test@example.com";
        Path knownHosts = Path.of(required("SYNCPLUGINS_TEST_KNOWN_HOSTS"));

        String remoteCommit = new GitSyncEngine(
                new GitRepositoryInspector(), new KnownHostsStore(knownHosts))
                .testConnection(config, new char[0]);

        assertThat(remoteCommit == null || remoteCommit.matches("[0-9a-f]{40}")).isTrue();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量：" + name);
        }
        return value;
    }
}
