package com.syncplugins.config;

import java.io.IOException;
import java.nio.file.Path;

public final class StateStore {
    private final Path path;

    public StateStore(Path path) {
        this.path = path;
    }

    public synchronized AppState load() {
        try {
            AppState state = JsonFileStore.read(path, AppState.class, new AppState());
            if (state.lastError == null) {
                state.lastError = "";
            }
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取运行状态：" + path + "；" + exception.getMessage(), exception);
        }
    }

    public synchronized void save(AppState state) throws IOException {
        JsonFileStore.write(path, state);
    }

    public Path path() {
        return path;
    }
}

