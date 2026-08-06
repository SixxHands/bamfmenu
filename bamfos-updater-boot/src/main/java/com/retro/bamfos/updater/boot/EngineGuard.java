package com.retro.bamfos.updater.boot;

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

/**
 * Boot-once-verify for the promoted engine, the way a bootloader keeps a known good image.
 * The gap. An engine that LOADS but does not WORK bricks a box permanently, and the engine ships as a
 * release ASSET, so any release can hand a newer box an older engine. Measured on the real repo: run 1 on a
 * cold server synced perfectly and staged the release's own engine, the next boot swapped it in, and that
 * engine's release() keyed asset ids by tag_name only. Both doors ask for the literal tag "latest", so every
 * downloadAsset("latest", ...) threw `asset 'manifest.json' not on release latest`, on runs 2, 5 and 6, and
 * only restoring the engine by hand fixed it. EngineLoader's baseline re-extract cannot save that: it fires
 * when a jar fails to LOAD, and this one loads fine and then misbehaves.
 * The guard. A promotion keeps the engine it displaces as `.jar.good` and arms a probation flag naming
 * the promoted sha and the pid that armed it. The engine deletes that flag itself the moment it reaches a run
 * it can account for (EngineHealth, engine module). A boot that finds the flag still armed knows the promoted
 * engine never had a working run: restore the known good copy, record a STRIKE against the rejected sha, and
 * say so loudly. An engine too old to have heard of EngineHealth can never clear the flag, which is exactly
 * the case measured above.
 * IMPORTANT: it takes STRIKES_TO_REJECT strikes to burn a sha, never one, because the evidence the verdict reads is
 * not exclusive to a broken engine: an alt-F4 during the manifest fetch, a power cut, an Error out of the door
 * (neither door's catch sees one), a captive portal answering 200 with a login page, or a full disk mid-write
 * all leave the flag armed with nothing wrong with the engine at all. One unlucky boot costs a rollback and a
 * re-download; refusing those bytes on the FIRST one pinned the box to an older engine forever, with no way out
 * a player could be talked through, which is the failure class this guard exists to prevent.
 * IMPORTANT: no version-string compare anywhere, deliberately, because bans ordering compares on version
 * strings and the one ordered compare in the system is the integer updater_abi. The verdict here is
 * behavioral, "did it report a run it could account for", NEVER "is it newer".
 * all the way through: every failure is a printed line and a continue, and a restore that cannot
 * replace the jar leaves the flag armed so the next boot retries instead of losing the verdict.
 */
final class EngineGuard {

    /**
     * The flag the ENGINE deletes to declare itself functioning, duplicated from EngineHealth.PROBATION in
     * the engine module on purpose: the shim reflects into the engine across an isolated classloader, so
     * these two never link and the filesystem is their only bus. Rename it on one side only and
     * no engine ever clears probation again, which rolls every box back one engine on the boot after it
     * updates.
     */
    private static final String PROBATION_FILE = "engine-probation";

    /** The refusal list, `sha TAB rejectedAtMillis`. Also read by the ENGINE (SyncCore's self-entry loop) over
     *  the same filesystem bus, because a sha the shim will not promote must not be re-downloaded and re-staged on
     *  every sync for the life of that release. The timestamp is for the human reading the file months later,
     *  nothing branches on it. */
    private static final String REJECTED_FILE = "engine-rejected";

    /** The strikes not yet worth a refusal, `sha TAB count`. Its own file so REJECTED_FILE keeps exactly one
     *  meaning, "these bytes are refused", and the engine reading it needs no copy of the threshold. */
    private static final String STRIKES_FILE = "engine-strikes";

    /** Two, so the anti-flap rule still bounds a genuinely bad engine to two wasted boots while a single
     *  unlucky one costs nothing permanent. */
    static final int STRIKES_TO_REJECT = 2;

    /** Bounded like every other footprint in here: a manifest can only ever name one engine, so the last
     *  few shas are the only ones that can still be offered to this box. */
    private static final int REJECT_MAX = 5;

    /**
     * Judges the LAST boot, and it runs before anything else touches the engine slot: an armed flag means the
     * engine promoted last boot never reported a working run, so it goes back where it came from. Called
     * first in ensureAndLoad so a promotion this same boot cannot be judged by a verdict it did not earn.
     */
    static void rollbackUnproven(Path enginePath, Supplier<InputStream> baseline) {
        Path flag = probation(enginePath);
        if (!Files.isRegularFile(flag)) {
            return; // the common path by a mile: nothing was promoted, or the engine cleared itself
        }
        String[] armed;
        try {
            armed = Files.readString(flag, StandardCharsets.UTF_8).trim().split("\t");
        } catch (IOException | RuntimeException unreadable) {
            armed = new String[]{""}; // an unreadable flag is still an ARMED flag, the verdict survives it
        }
        String rejected = armed[0].trim();
        if (writerStillRunning(armed)) {
            // a SIBLING instance armed this seconds ago and is still syncing behind it (, the
            // double-launch case). On POSIX the replace over an open jar succeeds, so judging it here would
            // roll back a healthy engine mid-run, burn a strike against it, and delete the flag out from
            // under the process that is about to clear it honestly
            return;
        }
        if (!restore(enginePath, baseline)) {
            return; // flag stays armed on purpose, so the next boot retries the restore
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
                        : "strike against " + shortSha(rejected) + ", one bad boot is not a verdict yet") + ")");
    }

    /** Has this exact engine already had its chances and failed them? Answered by sha, because the manifest
     *  keeps naming the same asset until the pack host publishes a new release, and re-promoting it every boot
     *  turns a hard brick into a box that only syncs every other boot. */
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
                // the jar on disk is ITSELF unproven (a rollback that could not land), and copying it here
                // would overwrite the last engine that actually worked with the one being rolled back
                return;
            }
            copyThroughTmp(enginePath, good(enginePath));
        } catch (IOException disk) {
            // promote anyway: an earlier promotion almost certainly left a .good, and the shim's baseline
            // covers the box that has none. Refusing the swap over a failed copy would freeze the engine
            System.out.println("[updater] could not keep a rollback copy of the engine, promoting anyway: " + disk);
        }
    }

    /** Arms probation on the jar now in the slot, stamped with the pid that armed it. Call AFTER the swap: a
     *  flag armed over a jar that never moved rolls back an engine that was fine the whole time. */
    static void arm(Path enginePath, String sha) {
        try {
            ProcessHandle self = ProcessHandle.current();
            // the pid AND its start instant, because a pid alone is reusable and a recycled one would make the
            // next boot decline a verdict it should have delivered
            long started = self.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            Files.writeString(probation(enginePath),
                    (sha == null ? "" : sha) + "\t" + self.pid() + "\t" + started, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException disk) {
            // unarmed means unguarded, and that is the pre-guard behavior, so it is survivable and loud
            System.out.println("[updater] could not put the new engine on probation, "
                    + "it runs unguarded this boot: " + disk);
        }
    }

    /** Clears probation from the shim side, for the one jar that is known good by shipping: the embedded
     *  baseline. Without this a re-extract inherits the verdict on the jar it replaced. */
    static void disarm(Path enginePath) {
        try {
            Files.deleteIfExists(probation(enginePath));
        } catch (IOException stuck) {
            System.out.println("[updater] engine probation flag could not be cleared: " + stuck);
        }
    }

    /** sha256 as lowercase hex, null when the file cannot be read. The engine's HashUtils does this too, but
     *  the shim deliberately links nothing from the engine, so it carries its own 10 lines. */
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
     * One strike against these bytes, and true when that was the one that burned them. Also called from
     * EngineLoader for a promoted jar that could not even LOAD, because those bytes are just as unpromotable and
     * the baseline re-extract that saves the boot clears the flag before any verdict is reached.
     * A sha we could not compute records nothing: it self-limits (the same verdict fires again next boot,
     * which is one wasted engine download per boot and never a brick) and there is no key to count against.
     */
    static boolean strike(Path enginePath, String sha) {
        if (sha == null || sha.isEmpty()) {
            return false;
        }
        if (firstFields(rejects(enginePath)).contains(sha)) {
            return true; // already burned, nothing left to count
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
     * The displaced copy first, then the shim's own baseline: a box that has never promoted an engine has no
     * .good, and the baseline is still a known good image. False = the slot was NOT restored.
     * A .good that EXISTS and cannot be read falls through to the baseline rather than giving up, because an AV
     * quarantining a jar copy in a hidden dot-folder is exactly the shape scanners grab, and giving up there
     * kept booting the unproven engine while a known good image sat inside the shim jar untouched.
     */
    private static boolean restore(Path enginePath, Supplier<InputStream> baseline) {
        Path good = good(enginePath);
        if (Files.isRegularFile(good)) {
            try {
                copyThroughTmp(good, enginePath);
                return true;
            } catch (IOException held) {
                // a second instance of this profile holds engine.jar open for its whole session and Windows
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

    /** Is the process that armed this flag still alive, so the verdict belongs to nobody yet? Same liveness
     *  test InstanceLock runs, duplicated for the same reason the flag name is (, the two modules
     *  never link). An unstamped flag answers false, which is the pre-stamp behavior: judge it. */
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

    /** The first tab-separated field of every line, which is the sha in both bookkeeping files. Unreadable
     *  reads as empty, because a missing memory must never be the reason a boot stops. */
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

    /** sha -> count off the strikes file, insertion-ordered so the cap below drops the oldest. */
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

    /** Temp file beside the target, then move, same atomic shape as the baseline extract: a crash or a full
     *  disk never leaves half a jar in the engine slot. The pid is in the tmp name for the same reason it is
     *  in EngineLoader.extract's: all of this runs before any InstanceLock exists, so a double-launch has two
     *  processes maintaining the slot at once, and one shared name let A's move promote B's half-written
     *  bytes into engine.jar (or over the .good rollback image through keepKnownGood). */
    private static void copyThroughTmp(Path from, Path to) throws IOException {
        Path tmp = to.resolveSibling(to.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException disk) {
            try {
                Files.deleteIfExists(tmp); // ours alone, nothing else ever recycles a pid-named leftover
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
