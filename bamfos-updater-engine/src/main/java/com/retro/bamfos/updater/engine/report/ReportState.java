package com.retro.bamfos.updater.engine.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Deduplicates trouble reports by fingerprint and cooldown so one recurring fault doesn't spam crash-reports/.
final class ReportState {

    private static final String FILE = ".bamfos-updater/report-state";

    /** Reserved state keys prefixed with '@' so they can't collide with sha256 fingerprints; unknown keys are ignored. */
    private static final String STREAK = "@streak";
    private static final String QUIET = "@quiet";

    /** Max history entries to keep. Reserved counter rows (like @streak for T6) aren't counted
     *  against this cap so they don't get evicted. */
    private static final int HISTORY_MAX = 8;

    /** Non-failing, non-converged boots before an old failure streak decays (so a single offline boot doesn't reset it). */
    private static final int QUIET_TO_FORGET = 5;

    record Row(String fingerprint, long firstAt, long lastAt, int boots, String file) {
    }

    static Optional<Row> find(Path dir, String fingerprint) {
        for (Row r : history(dir)) {
            if (r.fingerprint().equals(fingerprint)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Inserts or increments the fault entry and returns the updated row with the new boot count so the
     *  caller can print the header without re-reading the file. */
    static Row record(Path dir, String fingerprint, long now, String file) {
        List<Row> rows = history(dir);
        Row updated = null;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.fingerprint().equals(fingerprint)) {
                updated = new Row(fingerprint, r.firstAt(), now, r.boots() + 1, file);
                rows.set(i, updated);
                break;
            }
        }
        if (updated == null) {
            updated = new Row(fingerprint, now, now, 1, file);
            rows.add(updated);
        }
        write(dir, rows, streak(dir), quiet(dir));
        return updated;
    }

    /** The newest lastAt across the history, or 0. What "gained a row SINCE THE LAST REPORT" is measured
     *  against, because core-rejected and engine-rejected both stamp their rows with a millis. */
    static long lastReportAt(Path dir) {
        long newest = 0L;
        for (Row r : history(dir)) {
            newest = Math.max(newest, r.lastAt());
        }
        return newest;
    }

    static int failedStreak(Path dir) {
        return streak(dir);
    }

    /**
     * Updates the failure streak once per boot from last-sync.json. ok/staged/pending clear it;
     * external errors (offline, limited, no-release, token-dead) don't change the streak but bump @quiet
     * so old streaks still decay after QUIET_TO_FORGET boots.
     */
    static void noteSyncResult(Path dir, String result) {
        int streak = streak(dir);
        if ("failed".equals(result)) {
            write(dir, history(dir), streak + 1, 0);
            return;
        }
        if ("ok".equals(result) || "staged".equals(result) || "pending".equals(result)) {
            if (streak != 0 || quiet(dir) != 0) {
                write(dir, history(dir), 0, 0);
            }
            return;
        }
        if (streak == 0) {
            return; // Nothing changed, so skip rewriting the file.
        }
        int quiet = quiet(dir) + 1;
        write(dir, history(dir), quiet >= QUIET_TO_FORGET ? 0 : streak,
                quiet >= QUIET_TO_FORGET ? 0 : quiet);
    }

    private static int streak(Path dir) {
        return counter(dir, STREAK);
    }

    private static int quiet(Path dir) {
        return counter(dir, QUIET);
    }

    private static int counter(Path dir, String key) {
        for (String[] f : lines(dir)) {
            if (f.length >= 3 && key.equals(f[1])) {
                try {
                    return Math.max(0, Integer.parseInt(f[2].trim()));
                } catch (NumberFormatException handEdited) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static List<Row> history(Path dir) {
        List<Row> out = new ArrayList<>();
        for (String[] f : lines(dir)) {
            if (f.length < 6 || f[1].startsWith("@")) {
                continue;
            }
            try {
                out.add(new Row(f[1], Long.parseLong(f[2].trim()), Long.parseLong(f[3].trim()),
                        Integer.parseInt(f[4].trim()), f[5]));
            } catch (NumberFormatException handEdited) {
                // Ignore unparseable rows; worst case we emit one extra report.
            }
        }
        return out;
    }

    private static List<String[]> lines(Path dir) {
        List<String[]> out = new ArrayList<>();
        try {
            Path f = dir.resolve(FILE);
            if (!Files.isRegularFile(f)) {
                return out;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length >= 3 && "v1".equals(parts[0].trim())) {
                    out.add(parts);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            out.clear();
        }
        return out;
    }

    private static void write(Path dir, List<Row> rows, int streak, int quiet) {
        try {
            List<Row> kept = new ArrayList<>(rows);
            while (kept.size() > HISTORY_MAX) {
                kept.remove(0);
            }
            List<String> out = new ArrayList<>();
            for (Row r : kept) {
                out.add("v1\t" + r.fingerprint() + "\t" + r.firstAt() + "\t" + r.lastAt() + "\t"
                        + r.boots() + "\t" + r.file());
            }
            out.add("v1\t" + STREAK + "\t" + streak);
            if (quiet > 0) {
                out.add("v1\t" + QUIET + "\t" + quiet);
            }
            Path f = dir.resolve(FILE);
            Files.createDirectories(f.getParent());
            Files.write(f, out, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException held) {
            // Don't fail startup if state can't be saved; worst case we write a duplicate report next boot.
        }
    }

    private ReportState() {
    }
}
