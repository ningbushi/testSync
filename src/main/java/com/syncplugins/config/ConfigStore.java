package com.syncplugins.config;

import java.io.IOException;
import java.nio.file.Path;

public final class ConfigStore {
    private final Path path;

    public ConfigStore(Path path) {
        this.path = path;
    }

    public AppConfig load() {
        try {
            AppConfig config = JsonFileStore.read(path, AppConfig.class, new AppConfig());
            if (config.schemaVersion < 2) {
                config.schemaVersion = 2;
                JsonFileStore.write(path, config);
            }
            config.validate();
            return config;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法读取配置：" + path + "；" + exception.getMessage(), exception);
        }
    }

    public void save(AppConfig config) throws IOException {
        config.validate();
        JsonFileStore.write(path, config);
    }

    public Path path() {
        return path;
    }
}

