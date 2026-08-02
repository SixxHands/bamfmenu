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
import java.util.Optional;

// OS file lock preventing two updater instances from reconciling the same folder at once (released automatically by the OS on crash).
public final class InstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
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
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock held;
        try {
            held = ch.tryLock();
        } catch (OverlappingFileLockException thisVeryJvm) {
            // the JVM refuses a second lock on a file it already holds: same answer as a live
            // OTHER process, and the realistic shape of it (a BIOS op racing the door's own sync)
            ch.close();
            return Optional.empty();
        }
        if (held == null) {
            ch.close();
            return Optional.empty();
        }
        try {
            ch.truncate(0);
            ch.write(ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid())
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (IOException unwritable) {
            // The file lock is what matters; the PID is just a hint for debugging.
        }
        return Optional.of(new InstanceLock(ch, held));
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
