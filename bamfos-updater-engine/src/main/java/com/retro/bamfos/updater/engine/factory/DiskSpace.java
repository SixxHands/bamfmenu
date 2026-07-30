package com.retro.bamfos.updater.engine.factory;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Is there room to stage a whole pack before deleting the one that is already there?
 *
 * A factory reset is the only op that holds two copies at once: every file is fetched and verified into
 * staging BEFORE the first delete, which is what makes it crash-safe, and the cost of that safety is peak
 * disk. Nothing anywhere else in the engine has ever asked this question (a grep for getUsableSpace across
 * all four modules returns nothing), because an ordinary sync only ever stages a handful of changed files.
 *
 * Running out mid-fetch is SAFE, since it happens before the journal and the install is untouched, but it
 * is not free: staging is only swept on the non-staged exit, so a reset that dies at 90% leaves hundreds of
 * megabytes of .part behind and the player is told nothing useful. Asking first turns that into one sentence.
 *
 * A hosted box makes this matter more, not less. A Pterodactyl container has a disk quota, and the quota
 * is what the filesystem reports, so this check sees it.
 */
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
