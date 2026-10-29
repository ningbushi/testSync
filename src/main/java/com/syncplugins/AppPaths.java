package com.syncplugins;

import java.nio.file.Path;
import java.util.Map;

public record AppPaths(Path configDirectory, Path localDataDirectory) {
    public static AppPaths system() {
        Map<String, String> env = System.getenv();
        String roaming = env.get("APPDATA");
        String local = env.get("LOCALAPPDATA");
        Path home = Path.of(System.getProperty("user.home"));
        Path config = roaming == null || roaming.isBlank()
                ? home.resolve(".config").resolve("SyncPlugins")
                : Path.of(roaming).resolve("SyncPlugins");
        Path localData = local == null || local.isBlank()
                ? home.resolve(".local").resolve("share").resolve("SyncPluginsData")
                : Path.of(local).resolve("SyncPluginsData");
        return new AppPaths(config, localData);
    }

    public Path configFile() {
        return configDirectory.resolve("config.json");
    }

    public Path stateFile() {
        return localDataDirectory.resolve("state.json");
    }

    public Path knownHostsFile() {
        return configDirectory.resolve("known_hosts");
    }

    public Path secretFile() {
        return configDirectory.resolve("secret.bin");
    }

    public Path logFile() {
        return localDataDirectory.resolve("syncplugins.log");
    }

    public Path uiStateFile() {
        return configDirectory.resolve("ui-state.json");
    }

    public Path backupsDirectory() {
        return localDataDirectory.resolve("backups");
    }

    public Path tasksDirectory() {
        return configDirectory.resolve("tasks");
    }

    public Path statesDirectory() {
        return localDataDirectory.resolve("states");
    }

    public Path legacyLocalDataDirectory() {
        Path localRoot = localDataDirectory.getParent();
        return localRoot == null ? null : localRoot.resolve("SyncPlugins");
    }

    public Path instanceLockFile() {
        return localDataDirectory.resolve("instance.lock");
    }
}

