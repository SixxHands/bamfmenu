package com.retro.bamfos.updater.engine.report;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.door.SyncCore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

// Writes a sanitized bamfos-updater-* report into crash-reports/ when the updater hits a state it can't recover from automatically.
public final class TroubleReport {

    /**
     * Uses vanilla CrashReport timestamp punctuation so files sort chronologically in crash-reports,
     * with a bamfos-updater- prefix (never crash-) so launchers don't treat it as a game crash.
     */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").withZone(ZoneId.systemDefault());

    private static final String HIT_IT = "the updater hit something it cannot fix on its own";

    /**
     * Runs in both doors' finally blocks; never throws. Reads last-sync.json directly so callers don't
     * need to plumb end-state into finally.
     */
    public static void consider(Path dir, String side) {
        try {
            long now = System.currentTimeMillis();
            // Update T6's failure-streak counter once per boot before evaluating triggers.
            ReportState.noteSyncResult(dir, SyncCore.lastSyncResult(dir));
            Optional<Triggers.Fired> picked = Triggers.pick(dir, now);
            if (picked.isEmpty()) {
                return;
            }
            write(dir, BoxFacts.Side.of(side), picked.get(), now);
        } catch (Throwable ignored) {
            // Never let report generation fail a boot.
        }
    }

    /** Returns the path (relative to the game dir) of the newest report, or "" if none exists. */
    public static String newestReport(Path dir) {
        try {
            List<Path> mine = ReportSlot.ours(dir);
            return mine.isEmpty() ? "" : ReportSlot.display(dir, mine.get(0));
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private static void write(Path dir, BoxFacts.Side side, Triggers.Fired fired, long now) {
        Triggers.Fault fault = fired.fault();
        String fingerprint = fault.fingerprint().toString();
        Optional<ReportState.Row> seen = ReportState.find(dir, fingerprint);
        int boots = seen.map(r -> r.boots() + 1).orElse(1);
        long firstAt = seen.map(ReportState.Row::firstAt).orElse(now);
        // Validate with isOursInASlot (not just isRegularFile) since the path comes from report-state on disk
        // and could have been edited or truncated. If it doesn't match, just write a fresh report instead.
        Path standing = seen.map(r -> resolveOrForget(dir, r.file()))
                .filter(p -> p != null && ReportSlot.isOursInASlot(dir, p))
                .orElse(null);
        String body = ReportBody.full(fault, BoxFacts.read(dir, side), boots, firstAt, now);

        // Same fault fingerprint with an existing file: rewrite the full body in place so moving fields
        // like the failure count stay current.
        Optional<ReportSlot.Written> done = standing != null
                ? ReportSlot.rewrite(dir, standing, body)
                : ReportSlot.put(dir, "bamfos-updater-" + FILE_STAMP.format(Instant.ofEpochMilli(now))
                        + ReportSlot.SUFFIX, body);
        if (done.isEmpty()) {
            // If neither crash-reports nor our own folder is writable, give up instead of guessing
            // another directory.
            tell(dir, side, List.of(HIT_IT + ", and it could not write the report down anywhere on this "
                    + "machine, so tell the pack owner what you saw instead"));
            return;
        }
        ReportState.record(dir, fingerprint, now, done.get().display());
        if (standing == null) {
            ReportSlot.prune(dir);
        }
        // Delete the failure note only after the report lands: deleting first risks losing the only
        // evidence on write failure, and fingerprint dedupe already absorbs an undeletable note.
        if (fired.notePath() != null) {
            delete(fired.notePath());
        }
        tell(dir, side, standing != null
                ? List.of(HIT_IT + " again, and " + done.get().display() + " now says it has happened "
                        + boots + " times")
                : List.of(HIT_IT,
                        "what happened is written down in " + done.get().display(),
                        "send that whole file to the pack owner, it is the only thing that says why"));
    }

    /**
     * Resolves a remembered filename from report-state, or returns null on bad syntax so a corrupt entry
     * doesn't throw InvalidPathException and silence future reports for that fault.
     */
    private static Path resolveOrForget(Path dir, String file) {
        try {
            return dir.resolve(file);
        } catch (RuntimeException notAPath) {
            return null;
        }
    }

    private static void delete(Path note) {
        try {
            Files.deleteIfExists(note);
        } catch (IOException | RuntimeException held) {
            // Best effort: if AV locks the note, dedupe absorbs the retry next boot.
        }
    }

    /**
     * Logs to the boot bus on client or SyncCore on server. Leave messages unprefixed since each
     * channel adds a prefix.
     */
    private static void tell(Path dir, BoxFacts.Side side, List<String> lines) {
        if (side == BoxFacts.Side.SERVER) {
            for (String line : lines) {
                SyncCore.loud(dir, line);
            }
            return;
        }
        // Use append() instead of at() because the boot terminal is already tailing this file
        // and truncating it would break the live offset (see BootBus.append).
        BootBus bus = BootBus.append(dir);
        for (String line : lines) {
            bus.line("updater: " + line);
        }
    }

    private TroubleReport() {
    }
}
