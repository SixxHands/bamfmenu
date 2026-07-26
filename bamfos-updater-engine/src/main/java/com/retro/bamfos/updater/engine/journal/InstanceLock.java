package com.retro.bamfos.updater.engine.journal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Single-instance lock: two engines must never reconcile one instance simultaneously.
 * Holds the JVM PID; a lock whose PID is dead is reclaimed (without this, one kill -9 would
 * disable sync forever). Acquire fails ONLY when a LIVE process holds it -- callers skip sync
 * and boot as-is.
 */
public final class InstanceLock implements AutoCloseable {

    private final Path file;

    private InstanceLock(Path file) {
        this.file = file;
    }

    public static Path pathIn(Path instanceDir) {
        return instanceDir.resolve(".bamfos-updater").resolve("lock");
    }

    /** empty = a live process holds the lock. */
    public static Optional<InstanceLock> acquire(Path instanceDir) throws IOException {
        Path file = pathIn(instanceDir);
        Files.createDirectories(file.getParent());
        long self = ProcessHandle.current().pid();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Files.writeString(file, Long.toString(self), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return Optional.of(new InstanceLock(file));
            } catch (IOException exists) {
                String content;
                try {
                    content = Files.readString(file, StandardCharsets.UTF_8).trim();
                } catch (Exception unreadable) {
                    content = ""; // garbage/empty lock = stale
                }
                long holder;
                try {
                    holder = Long.parseLong(content);
                } catch (NumberFormatException nan) {
                    holder = -1;
                }
                boolean alive = holder > 0 && holder != self
                        && ProcessHandle.of(holder).map(ProcessHandle::isAlive).orElse(false);
                if (alive) {
                    return Optional.empty();
                }
                // reclaim ONLY if the file still names the exact dead/garbage content we observed:
                // if another process legitimately took the lock between our read and now, its live
                // PID differs, and a blind delete would clobber a real lock (two holders at once)
                try {
                    if (!Files.readString(file, StandardCharsets.UTF_8).trim().equals(content)) {
                        return Optional.empty();
                    }
                } catch (IOException gone) {
                    continue; // already deleted by whoever won -- retry the CREATE_NEW
                }
                Files.deleteIfExists(file); // stale: dead PID or unreadable -- reclaim and retry once
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(file);
    }
}
