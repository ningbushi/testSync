package com.syncplugins.system;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class SingleInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleInstanceLock tryAcquire(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new AlreadyRunningException();
            }
            return new SingleInstanceLock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw new AlreadyRunningException();
        }
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
    }

    public static final class AlreadyRunningException extends IOException {
        public AlreadyRunningException() {
            super("SyncPlugins 已经在运行");
        }
    }
}
