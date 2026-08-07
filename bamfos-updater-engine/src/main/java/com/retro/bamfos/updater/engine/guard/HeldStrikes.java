package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// Tracks per-file retry strikes across boots so a file that can't be replaced gets held after MAX_ATTEMPTS instead of retrying forever.
public final class HeldStrikes {

    /** One line per row: path, sha, kind, count, lastMillis, tab-separated. */
    private static final String FILE = ".bamfos-updater/held-strikes";

    /** The sha stored for a Q1 quarantine row, which has no manifest entry to take one from. */
    public static final String QUARANTINE_SHA = "quarantine";

    public static final long PROBE_DEFAULT = 24L * 60 * 60 * 1000; // 24h

    /** Cooldown before a given-up row gets one retry, so transient blockers (second launcher, AV lock,
     *  read-only bit) clear on their own within a day. Non-final so tests can override it. */
    public static volatile long PROBE_MILLIS = PROBE_DEFAULT;

    private record Row(String path, String sha, String kind, int count, long lastMillis) {
    }

    /** Records one failed attempt for a row (resets to 1 if sha/kind changed; only refreshes timestamp if
     *  already exhausted). Returns true when this bump hits the limit so the caller scrubs its journal op. */
    public static boolean bump(Path dir, String path, String sha, String kind, long nowMillis) {
        List<Row> rows = read(dir);
        boolean crossed = false;
        boolean found = false;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.path().equals(path)) {
                continue;
            }
            found = true;
            if (!r.sha().equals(sha) || !r.kind().equals(kind)) {
                rows.set(i, new Row(path, sha, kind, 1, nowMillis));
            } else if (r.count() >= Journal.MAX_ATTEMPTS) {
                rows.set(i, new Row(path, sha, kind, r.count(), nowMillis));
            } else {
                int n = r.count() + 1;
                crossed = n >= Journal.MAX_ATTEMPTS;
                rows.set(i, new Row(path, sha, kind, n, nowMillis));
            }
            break;
        }
        if (!found) {
            rows.add(new Row(path, sha, kind, 1, nowMillis));
            crossed = 1 >= Journal.MAX_ATTEMPTS;
        }
        write(dir, rows);
        return crossed;
    }

    /** True when this path/sha/kind is out of retries and not yet due for a cooldown retry. */
    public static boolean exhausted(Path dir, String path, String sha, String kind, long nowMillis) {
        for (Row r : read(dir)) {
            if (r.path().equals(path) && r.sha().equals(sha) && r.kind().equals(kind)
                    && r.count() >= Journal.MAX_ATTEMPTS && nowMillis - r.lastMillis() < PROBE_MILLIS) {
                return true;
            }
        }
        return false;
    }

    /** Checks if this path is out of retries for the given kind (no sha check since the client doesn't
     *  have a manifest yet; just used to avoid promising a restore the sync will skip). */
    public static boolean exhaustedUnderKind(Path dir, String path, String kind, long nowMillis) {
        for (Row r : read(dir)) {
            if (r.path().equals(path) && r.kind().equals(kind)
                    && r.count() >= Journal.MAX_ATTEMPTS && nowMillis - r.lastMillis() < PROBE_MILLIS) {
                return true;
            }
        }
        return false;
    }

    /** Forgets the rows that COMMITTED to the ledger this run, and only those: clearing on absence-from-plan
     *  or on a staged run laundered the counter (this rule hides its row from a non-repair plan, a staged run
     *  proves nothing), and the silent ok this whole file exists to kill came straight back. */
    public static void clear(Path dir, Collection<String> committedPaths) {
        List<Row> rows = read(dir);
        if (rows.isEmpty()) {
            return;
        }
        List<Row> keep = new ArrayList<>();
        for (Row r : rows) {
            if (!committedPaths.contains(r.path())) {
                keep.add(r);
            }
        }
        if (keep.size() != rows.size()) {
            write(dir, keep);
        }
    }

    /** Counts active strike rows still matching the manifest (or enabled quarantine jars) and prunes stale
     *  ones so obsolete entries don't permanently block the client's up-to-date shortcut. */
    public static int currentCount(Path dir, Manifest manifest) {
        List<Row> rows = read(dir);
        if (rows.isEmpty()) {
            return 0;
        }
        List<Row> keep = new ArrayList<>();
        for (Row r : rows) {
            boolean current;
            if (QUARANTINE_SHA.equals(r.sha())) {
                current = Files.isRegularFile(dir.resolve(r.path()));
            } else {
                current = false;
                for (FileEntry e : manifest.files()) {
                    if (e.path().equals(r.path()) && e.sha256().equals(r.sha())) {
                        current = true;
                        break;
                    }
                }
            }
            if (current) {
                keep.add(r);
            }
        }
        if (keep.size() != rows.size()) {
            write(dir, keep);
        }
        return keep.size();
    }

    /** Returns paths that are currently out of retries (used for the given-up log line and server
     *  self-heal). Reads the file as-is; stale entries are pruned during sync. */
    public static List<String> exhaustedNow(Path dir, long nowMillis) {
        List<String> out = new ArrayList<>();
        for (Row r : read(dir)) {
            if (r.count() >= Journal.MAX_ATTEMPTS && nowMillis - r.lastMillis() < PROBE_MILLIS) {
                out.add(r.path());
            }
        }
        return out;
    }

    /** True when every tracked row is out of retries and none is due for a cooldown retry. Only then can
     *  server selfHeal skip re-running. */
    public static boolean allExhausted(Path dir, long nowMillis) {
        List<Row> rows = read(dir);
        if (rows.isEmpty()) {
            return false;
        }
        for (Row r : rows) {
            if (r.count() < Journal.MAX_ATTEMPTS || nowMillis - r.lastMillis() >= PROBE_MILLIS) {
                return false;
            }
        }
        return true;
    }

    /** True if any tracked row still needs a retry (below MAX or past cooldown), which blocks the
     *  fast-boot shortcut. Reads the strikes file directly because last-sync.json overwrites `held`
     *  on offline/limited runs. */
    public static boolean anyBelowMaxOrProbeDue(Path dir, long nowMillis) {
        for (Row r : read(dir)) {
            if (r.count() < Journal.MAX_ATTEMPTS || nowMillis - r.lastMillis() >= PROBE_MILLIS) {
                return true;
            }
        }
        return false;
    }

    /** Formats a capped list of tracked paths for console logging. */
    public static String names(Path dir) {
        List<String> paths = new ArrayList<>();
        for (Row r : read(dir)) {
            paths.add(r.path());
        }
        List<String> shown = paths.stream().limit(6).toList();
        return String.join(", ", shown) + (paths.size() > shown.size() ? ", ..." : "");
    }

    /** Clears all strike records on explicit repair (BIOS repair, /bamfos repair, or server repair flag).
     *  Automatic selfHeal never calls this so failed rows don't loop forever. Never throws. */
    public static void forgive(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(FILE));
        } catch (IOException | RuntimeException held) {
            // Ignore delete errors; worst case it delays a retry on later boots.
        }
    }

    private static List<Row> read(Path dir) {
        List<Row> rows = new ArrayList<>();
        try {
            Path f = dir.resolve(FILE);
            if (!Files.isRegularFile(f)) {
                return rows;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length >= 5 && !p[0].isEmpty()) {
                    try {
                        rows.add(new Row(p[0], p[1], p[2],
                                Math.max(0, Integer.parseInt(p[3])), Long.parseLong(p[4])));
                    } catch (NumberFormatException handEdited) {
                        // Drop malformed rows; retrying is safer than getting stuck.
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            rows.clear(); // If the file can't be read, treat it as empty rather than using partial data.
        }
        return rows;
    }

    private static void write(Path dir, List<Row> rows) {
        try {
            Path f = dir.resolve(FILE);
            if (rows.isEmpty()) {
                Files.deleteIfExists(f); // Delete the file when empty so future boots don't have to read it.
                return;
            }
            Files.createDirectories(f.getParent());
            StringBuilder b = new StringBuilder();
            for (Row r : rows) {
                b.append(r.path()).append('\t').append(r.sha()).append('\t').append(r.kind())
                        .append('\t').append(r.count()).append('\t').append(r.lastMillis()).append('\n');
            }
            Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException held) {
            // best effort: a failed write costs the counter one boot of memory, never the sync
        }
    }

    private HeldStrikes() {
    }
}
