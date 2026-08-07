package com.retro.bamfos.updater.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/* Boot-once verification for promoted engines. Arms a probation file after swapping in a new engine;
 * if the engine doesn't clear it on a working run, the next boot rolls back to .good (or baseline) and
 * strikes that sha. */
final class EngineGuard {

    /**
     * Probation flag deleted by the engine once healthy, duplicated from EngineHealth.PROBATION.
     * Both sides communicate only via disk; renaming one side rolls back every updated engine.
     */
    private static final String PROBATION_FILE = "engine-probation";

    /**
     * Refusal list (sha TAB rejectedAtMillis), also read by SyncCore so rejected engines
     * are not re-downloaded every sync. The timestamp is informational only.
     */
    private static final String REJECTED_FILE = "engine-rejected";

    /** Tracks strike counts (`sha TAB count`) before rejection. Kept separate from REJECTED_FILE
     *  so the engine doesn't need to know the strike threshold. */
    private static final String STRIKES_FILE = "engine-strikes";

    /** Two strikes bound a broken engine to two bad boots while tolerating a single unlucky crash. */
    static final int STRIKES_TO_REJECT = 2;

    /** Cap strike/rejection history to a few entries since the manifest only names one engine at a time. */
    private static final int REJECT_MAX = 5;

    /**
     * Rolls back an unproven engine if the previous boot left probation armed.
     * Must run first in ensureAndLoad so a promotion during this boot is never judged immediately.
     */
    static void rollbackUnproven(Path enginePath, Supplier<InputStream> baseline) {
        Path flag = probation(enginePath);
        if (!Files.isRegularFile(flag)) {
            return; // Common case: nothing was promoted, or the engine already cleared the flag.
        }
        String[] armed;
        try {
            armed = Files.readString(flag, StandardCharsets.UTF_8).trim().split("\t");
        } catch (IOException | RuntimeException unreadable) {
            armed = new String[]{""}; // Treat an unreadable flag file as armed so we still roll back.
        }
        String rejected = armed[0].trim();
        if (writerStillRunning(armed)) {
            // sibling instance is still booting; on POSIX replacing an open jar would succeed and
            // wrongly roll back, strike, and disarm a healthy engine mid-sync
            return;
        }
        if (!restore(enginePath, baseline)) {
            return; // Leave the flag armed so the next boot retries the restore.
        }
        boolean refused = strike(enginePath, rejected);
        try {
            Files.deleteIfExists(flag);
        } catch (IOException stuck) {
            System.out.println("[updater] engine probation flag could not be cleared: " + stuck);
        }
        System.out.println("[updater] the engine promoted last boot never reported a working run, "
                + "ROLLED BACK to the last known good engine (" + (refused
                        ? shortSha(rejected) + " failed twice, it is now refused: delete .bamfos-updater/engine/"
                                + REJECTED_FILE + " (or run the BIOS repair pass) to give it another chance"
                        : "strike against " + shortSha(rejected) + ", one bad boot doesn't reject it yet") + ")");
    }

    /**
     * True if this engine sha already hit the strike limit, stopping repeated promote/reject
     * flapping until a new release replaces the asset.
     */
    static boolean rejectedBefore(Path enginePath, String sha) {
        if (sha == null || !firstFields(rejects(enginePath)).contains(sha)) {
            return false;
        }
        System.out.println("[updater] staged engine " + shortSha(sha) + " failed its probation "
                + STRIKES_TO_REJECT + " times, not promoting it again (delete .bamfos-updater/engine/"
                + REJECTED_FILE + " to retry it, or publish a new engine)");
        return true;
    }

    /** The rollback image: the jar being displaced, kept before the swap, because after it there is nothing left
     *  to copy. Exactly one copy, overwritten every promotion, so this can never grow. */
    static void keepKnownGood(Path enginePath) {
        try {
            if (!Files.isRegularFile(enginePath)) {
                return; // nothing on disk to displace yet, and the embedded baseline is the fallback anyway
            }
            if (Files.isRegularFile(probation(enginePath))) {
                // Don't back up the current jar if it's still unproven, or we'd overwrite the last known-good
                // engine with one that's failing rollback.
                return;
            }
            copyThroughTmp(enginePath, good(enginePath));
        } catch (IOException disk) {
            // promote anyway: an older .good or the embedded baseline covers rollback, whereas aborting freezes the engine
            System.out.println("[updater] could not keep a rollback copy of the engine, promoting anyway: " + disk);
        }
    }

    /** Arms probation for the jar currently in the slot, stamped with our pid. Call after the swap
     *  so a failed move doesn't roll back a working engine. */
    static void arm(Path enginePath, String sha) {
        try {
            ProcessHandle self = ProcessHandle.current();
            // Record both pid and start time since pids get recycled; otherwise a reused pid could prevent
            // the next boot from rolling back a failed engine from a dead process.
            long started = self.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            Files.writeString(probation(enginePath),
                    (sha == null ? "" : sha) + "\t" + self.pid() + "\t" + started, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException disk) {
            // If we can't arm the flag, log it and continue unguarded rather than failing the boot.
            System.out.println("[updater] could not put the new engine on probation, "
                    + "it runs unguarded this boot: " + disk);
        }
    }

    /** Clears probation when re-extracting the embedded baseline jar so it doesn't inherit a failed
     *  probation flag from the jar it replaced. */
    static void disarm(Path enginePath) {
        try {
            Files.deleteIfExists(probation(enginePath));
        } catch (IOException stuck) {
            System.out.println("[updater] engine probation flag could not be cleared: " + stuck);
        }
    }

    /** Returns lowercase SHA-256 hex or null if unreadable. Duplicated from HashUtils because the
     *  core doesn't link against the engine. */
    static String sha256(Path f) {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Records a strike for these bytes and returns true when rejected; also called by EngineLoader
     * on load failure before baseline re-extract clears probation. Unreadable shas are skipped safely.
     */
    static boolean strike(Path enginePath, String sha) {
        if (sha == null || sha.isEmpty()) {
            return false;
        }
        if (firstFields(rejects(enginePath)).contains(sha)) {
            return true; // Already rejected, no need to count more strikes.
        }
        try {
            Map<String, Integer> strikes = counted(strikes(enginePath));
            int count = strikes.getOrDefault(sha, 0) + 1;
            if (count < STRIKES_TO_REJECT) {
                strikes.put(sha, count);
                writeCapped(strikes(enginePath), strikes.entrySet().stream()
                        .map(e -> e.getKey() + "\t" + e.getValue()).toList());
                return false;
            }
            strikes.remove(sha); // its counter is spent, the refusal list owns it now
            writeCapped(strikes(enginePath), strikes.entrySet().stream()
                    .map(e -> e.getKey() + "\t" + e.getValue()).toList());
            List<String> refused = new ArrayList<>(Files.isRegularFile(rejects(enginePath))
                    ? Files.readAllLines(rejects(enginePath), StandardCharsets.UTF_8) : List.of());
            refused.add(sha + "\t" + System.currentTimeMillis());
            writeCapped(rejects(enginePath), refused);
            return true;
        } catch (IOException | RuntimeException disk) {
            System.out.println("[updater] could not record the rejected engine, "
                    + "it may be promoted again next sync: " + disk);
            return false;
        }
    }

    /**
     * Restores .good first, falling back to the embedded baseline if .good is missing or unreadable
     * (e.g. quarantined by AV). Returns false if the slot could not be restored.
     */
    private static boolean restore(Path enginePath, Supplier<InputStream> baseline) {
        Path good = good(enginePath);
        if (Files.isRegularFile(good)) {
            try {
                copyThroughTmp(good, enginePath);
                return true;
            } catch (IOException held) {
                // a second instance of this profile keeps engine.jar open for its whole session and Windows
                // refuses to replace an open file, the same refusal promoteStaged survives. That costs the
                // ROLLBACK and nothing else: the jar on disk still loads and the boot still happens
                System.out.println("[updater] engine rollback could not use the .good copy, "
                        + "trying the shim's baseline: " + held);
            }
        }
        try {
            EngineLoader.extract(enginePath, baseline);
            System.out.println("[updater] restored the shim's baseline engine");
            return true;
        } catch (IOException nothing) {
            System.out.println("[updater] no engine to roll back to, running the one on disk and keeping the "
                    + "probation flag for the next boot: " + nothing);
            return false;
        }
    }

    /** Returns true if the process that armed this flag is still running (same check as InstanceLock,
     *  duplicated because these modules don't link). Unstamped flags return false so we roll them back. */
    private static boolean writerStillRunning(String[] armed) {
        if (armed.length < 3) {
            return false;
        }
        try {
            long pid = Long.parseLong(armed[1].trim());
            long started = Long.parseLong(armed[2].trim());
            if (pid <= 0 || started <= 0 || pid == ProcessHandle.current().pid()) {
                return false;
            }
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .flatMap(h -> h.info().startInstant())
                    .map(when -> when.toEpochMilli() == started)
                    .orElse(false);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Reads the first tab-separated field (the sha) from each line, returning empty if unreadable. */
    private static List<String> firstFields(Path f) {
        List<String> out = new ArrayList<>();
        try {
            if (Files.isRegularFile(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String sha = line.split("\t")[0].trim();
                    if (!sha.isEmpty()) {
                        out.add(sha);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
        return out;
    }

    /** Parses sha -> strike count from the strikes file in insertion order so trimming drops the oldest. */
    private static Map<String, Integer> counted(Path f) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length >= 2 && !parts[0].trim().isEmpty()) {
                        try {
                            out.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                        } catch (NumberFormatException junk) {
                            out.put(parts[0].trim(), 1); // a mangled count still means it failed once
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return out;
        }
        return out;
    }

    private static void writeCapped(Path f, List<String> lines) throws IOException {
        List<String> kept = new ArrayList<>(lines);
        while (kept.size() > REJECT_MAX) {
            kept.remove(0);
        }
        Files.write(f, kept, StandardCharsets.UTF_8);
    }

    /**
     * Copies via a pid-scoped sibling temp file and atomic move. Runs before InstanceLock,
     * so including the pid stops double-launch siblings from overwriting each other's temp jar.
     */
    private static void copyThroughTmp(Path from, Path to) throws IOException {
        Path tmp = to.resolveSibling(to.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
            EngineLoader.replaceAtomic(tmp, to);
        } catch (IOException disk) {
            try {
                Files.deleteIfExists(tmp); // Clean up our pid-named temp file on failure.
            } catch (IOException ignored) {
            }
            throw disk;
        }
    }

    /** 12 hex is plenty to match against the manifest by eye, and a full sha in a boot line is noise. */
    private static String shortSha(String sha) {
        return sha.isEmpty() ? "unknown sha" : sha.substring(0, Math.min(12, sha.length()));
    }

    static Path good(Path enginePath) {
        return enginePath.resolveSibling(enginePath.getFileName() + ".good");
    }

    static Path probation(Path enginePath) {
        return enginePath.resolveSibling(PROBATION_FILE);
    }

    static Path rejects(Path enginePath) {
        return enginePath.resolveSibling(REJECTED_FILE);
    }

    static Path strikes(Path enginePath) {
        return enginePath.resolveSibling(STRIKES_FILE);
    }

    private EngineGuard() {
    }
}
