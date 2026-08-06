package com.retro.bamfos.updater.engine.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// OS file lock preventing two updater instances from reconciling the same folder at once (released automatically by the OS on crash).
public final class InstanceLock implements AutoCloseable {

    /**
     * One canonical channel per lock file for the whole JVM. Closing any channel on a file can drop
     * all POSIX fcntl locks the process keeps on it, so losers must never open and close a second one.
     */
    private static final Map<Path, FileChannel> CANON = new HashMap<>();

    private final Path key;
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(Path key, FileChannel channel, FileLock lock) {
        this.key = key;
        this.channel = channel;
        this.lock = lock;
    }

    public static Path pathIn(Path instanceDir) {
        return instanceDir.resolve(".bamfos-updater").resolve("lock");
    }

    /** empty = a live process (or another thread of this one) keeps the lock. */
    public static Optional<InstanceLock> acquire(Path instanceDir) throws IOException {
        Path file = pathIn(instanceDir);
        Files.createDirectories(file.getParent());
        synchronized (CANON) {
            Path key = file.toAbsolutePath().normalize();
            FileChannel cached = CANON.get(key);
            FileChannel ch = cached != null && cached.isOpen()
                    ? cached
                    : FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock held;
            try {
                held = ch.tryLock();
            } catch (OverlappingFileLockException thisVeryJvm) {
                // JVM refuses a second lock on a file we already locked (same as another process).
                // Never close ch here: it is the shared canonical channel, and closing it drops the winner's OS lock.
                return Optional.empty(); // the holder keeps channel and lock, untouched
            }
            if (held == null) {
                // a live other process keeps it. A lock of our own JVM answers as the throw above,
                // not as null, so there is nothing of ours this close could drop
                ch.close();
                return Optional.empty();
            }
            CANON.put(key, ch);
            try {
                ch.truncate(0);
                ch.write(ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid())
                        .getBytes(StandardCharsets.UTF_8)));
            } catch (IOException unwritable) {
                // The file lock is what matters; the PID is just a hint for debugging.
            }
            return Optional.of(new InstanceLock(key, ch, held));
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (CANON) {
            try {
                lock.release();
            } finally {
                CANON.remove(key, channel); // Remove only our own entry in case another acquire already replaced it.
                channel.close();
            }
        }
    }
}
