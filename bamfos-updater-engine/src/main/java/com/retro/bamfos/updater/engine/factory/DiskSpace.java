package com.retro.bamfos.updater.engine.factory;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

// Checks free disk space before staging files (a factory reset needs room for both the old install and the staged copy at once).
public final class DiskSpace {

    /** Required free-space margin above the download payload (max of 10% or 256 MB) to leave room for
     *  the journal, ledger, caches, and filesystem overhead. */
    static final double MARGIN = 0.10;
    static final long FLOOR_BYTES = 256L * 1024 * 1024;

    private DiskSpace() {
    }

    /** What the check decided, in the two numbers a player would want in the sentence they are shown. */
    public record Room(long needBytes, long freeBytes, boolean enough, boolean measured) {

        /** Don't block the reset if disk space can't be measured; proceed anyway rather than refusing a repair. */
        public boolean blocks() {
            return measured && !enough;
        }
    }

    public static Room check(Path dir, long payloadBytes) {
        try {
            FileStore store = Files.getFileStore(dir.toRealPath());
            long free = store.getUsableSpace(); // use usable space instead of free space (free ignores quotas and reserved blocks)
            return room(payloadBytes, free);
        } catch (IOException | RuntimeException unmeasurable) {
            return new Room(need(payloadBytes), 0L, true, false);
        }
    }

    /** Pure free-space check separated from filesystem measurement so margin math can be unit tested. */
    static Room room(long payloadBytes, long freeBytes) {
        long need = need(payloadBytes);
        return new Room(need, freeBytes, freeBytes >= need, true);
    }

    static long need(long payloadBytes) {
        return payloadBytes + Math.max(FLOOR_BYTES, (long) (payloadBytes * MARGIN));
    }

    /** Formats a byte count as a human-readable size (KB/MB/GB) for UI dialogs. */
    public static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }
}
