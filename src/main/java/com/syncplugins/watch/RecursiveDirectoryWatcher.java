package com.syncplugins.watch;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecursiveDirectoryWatcher implements AutoCloseable {
    private final Path root;
    private final Runnable onChange;
    private final WatchService watchService;
    private final Map<WatchKey, Path> directories = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public RecursiveDirectoryWatcher(Path root, Runnable onChange) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.onChange = onChange;
        this.watchService = FileSystems.getDefault().newWatchService();
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        registerTree(root);
        thread = Thread.ofPlatform()
                .name("syncplugins-file-watcher")
                .daemon(true)
                .start(this::watchLoop);
    }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(root) && directory.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = directory.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                directories.put(key, directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException exception) {
                return;
            }
            Path directory = directories.get(key);
            if (directory == null) {
                key.reset();
                continue;
            }
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    relevant = true;
                    continue;
                }
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path child = directory.resolve(pathEvent.context());
                if (child.getFileName().toString().equals(".git")) {
                    continue;
                }
                relevant = true;
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    try {
                        registerTree(child);
                    } catch (IOException ignored) {
                        relevant = true;
                    }
                }
            }
            if (!key.reset()) {
                directories.remove(key);
            }
            if (relevant) {
                onChange.run();
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            watchService.close();
        } catch (IOException ignored) {
            // The watcher is already stopping.
        }
        if (thread != null) {
            try {
                thread.join(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        directories.clear();
    }
}

