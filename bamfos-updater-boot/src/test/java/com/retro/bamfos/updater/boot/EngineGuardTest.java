package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot-once-verify, the P0 guard: an engine that LOADS but does not WORK used to brick a box forever, and the
 * measured way in was the release's own engine asset being an OLD build. Every engine in here is a REAL
 * loadable jar built off the embedded baseline, because that is the whole difficulty of the bug, the corrupt-jar
 * fallback never sees it.
 */
class EngineGuardTest {

    @TempDir
    Path tmp;

    private Path enginePath() {
        return tmp.resolve(".bamfos-updater").resolve("engine").resolve("bamfos-updater-engine.jar");
    }

    private Path staged() {
        return enginePath().resolveSibling(enginePath().getFileName() + ".new");
    }

    /** One boot of the shim: judge, promote, load. The loader is closed right away because Windows will not let
     *  the NEXT boot replace a jar this test still holds open, which is the very refusal the last test here
     *  exists to prove. */
    private void boot() throws IOException {
        EngineLoader.Engine engine = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
        engine.loader().close();
    }

    @Test
    void unprovenEngine_isRolledBackToTheJarItDisplaced() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(good);
        Files.copy(brick, staged());

        boot(); // the release's engine goes in
        assertArrayEquals(bytes(brick), bytes(enginePath()));
        assertTrue(Files.isRegularFile(EngineGuard.probation(enginePath())));
        assertTrue(Files.isRegularFile(EngineGuard.good(enginePath())));
        assertArrayEquals(bytes(good), bytes(EngineGuard.good(enginePath())));

        boot(); // it never cleared the flag, so THIS boot is the verdict
        assertArrayEquals(bytes(good), bytes(enginePath()));
        assertFalse(Files.exists(EngineGuard.probation(enginePath())));
        assertEquals(List.of(EngineGuard.sha256(brick) + "\t1"),
                Files.readAllLines(EngineGuard.strikes(enginePath()), StandardCharsets.UTF_8));
        assertFalse(Files.exists(EngineGuard.rejects(enginePath())));
    }

    /**
     * The strike rule's other side, because forgiving forever would be the flap this guard was built to bound: the
     * release keeps naming that engine asset until the pack host publishes a new one, so every sync re-stages the
     * same bad jar. Two chances, then those bytes are done, which is two wasted boots instead of infinite.
     */
    @Test
    void aSecondUnprovenBootOfTheSameBytes_burnsThemForGood() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(good);

        Files.copy(brick, staged());
        boot(); // promoted, armed
        boot(); // rolled back, strike 1
        Files.copy(brick, staged()); // the release still names it, so the next sync stages it again
        boot(); // promoted again, armed again
        boot(); // rolled back, strike 2

        assertArrayEquals(bytes(good), bytes(enginePath()));
        // asserted present BEFORE it is read, because a guard that forgives forever is the flap this list bounds and
        // that has to read as a failed assertion, never as a NoSuchFileException nobody can interpret
        assertTrue(Files.isRegularFile(EngineGuard.rejects(enginePath())));
        assertTrue(Files.readAllLines(EngineGuard.rejects(enginePath()), StandardCharsets.UTF_8).get(0)
                        .startsWith(EngineGuard.sha256(brick) + "\t"));
        Files.copy(brick, staged());
        boot();
        assertArrayEquals(bytes(good), bytes(enginePath()));
        assertTrue(Files.isRegularFile(staged()));
    }

    /**
     * The anti-flap half, and without it the guard makes things WORSE instead of better: the release keeps
     * naming that engine asset until the pack host publishes a new one, so every sync re-stages the same bad jar.
     * Promote, reject, promote, reject means a box that only syncs every other boot, forever.
     */
    @Test
    void aRejectedEngine_isNeverPromotedAgain() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(good);
        Files.writeString(EngineGuard.rejects(enginePath()), EngineGuard.sha256(brick) + System.lineSeparator());
        Files.copy(brick, staged());

        boot();
        assertArrayEquals(bytes(good), bytes(enginePath()));
        assertTrue(Files.isRegularFile(staged()));
    }

    /** The false-positive direction, which matters more than the catch: an engine that reported a working run
     *  must be untouchable, or the guard downgrades a healthy box every time it updates. */
    @Test
    void provenEngine_isLeftAloneOnTheNextBoot() throws Exception {
        Path good = engineJar("known-good");
        Path fresh = engineJar("fresh");
        install(good);
        Files.copy(fresh, staged());
        boot();

        // exactly what EngineHealth.proven does from inside the engine, one deleteIfExists
        Files.delete(EngineGuard.probation(enginePath()));

        boot();
        assertArrayEquals(bytes(fresh), bytes(enginePath()));
        assertFalse(Files.exists(EngineGuard.rejects(enginePath())));
    }

    /** The cross-module contract, and this is the ONLY place it can be checked: the shim and the engine never
     *  link, so the flag's name is duplicated in EngineGuard and EngineHealth. Rename it on one side and every
     *  box quietly rolls its engine back one build on the boot after it updates. Loaded the way the shim loads
     *  the engine, out of the embedded baseline, so the assertion is on real engine bytes. */
    @Test
    void theEnginesOwnClearCall_landsOnTheFlagTheGuardWatches() throws Exception {
        boot(); // materializes the baseline engine into the slot
        Files.writeString(EngineGuard.probation(enginePath()), "deadbeef");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{enginePath().toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> health = Class.forName("com.retro.bamfos.updater.engine.guard.EngineHealth", false, loader);
            health.getMethod("proven", Path.class).invoke(null, tmp);
        }
        assertFalse(Files.exists(EngineGuard.probation(enginePath())));
    }

    /** A box that has never promoted an engine has no rollback copy, and the shim is still carrying a known
     *  good image: its own twin. Better than giving up, because giving up here is the brick. */
    @Test
    void withNoRollbackCopy_theShimsBaselineIsTheLastResort() throws Exception {
        Path brick = engineJar("brick");
        install(brick);
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(brick));

        boot();
        assertArrayEquals(baseline(), bytes(enginePath()));
        assertFalse(Files.exists(EngineGuard.probation(enginePath())));
        assertEquals(List.of(EngineGuard.sha256(brick) + "\t1"),
                Files.readAllLines(EngineGuard.strikes(enginePath()), StandardCharsets.UTF_8));
    }

    /**
     * The .good copy is not always READABLE, and giving up there kept booting the unproven engine while a known
     * good image sat inside the shim jar untouched. An AV holding or quarantining a jar copy in a hidden
     * dot-folder is exactly the shape heuristic scanners grab, so the last resort has to be reachable from a
     * .good that exists as well as from one that never did.
     * WINDOWS-ONLY as a declared fact: the lock below is what makes the read fail, and POSIX advisory locks do
     * not stop another handle reading the file, so there the assertion would be measuring the platform.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRollbackCopyItCannotREAD_fallsThroughToTheShimsBaseline() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(brick);
        Files.copy(good, EngineGuard.good(enginePath()));
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(brick));

        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                EngineGuard.good(enginePath()),
                java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignored = ch.lock()) {
            boot();
            assertArrayEquals(baseline(), bytes(enginePath()));
        }
        assertFalse(Files.exists(EngineGuard.probation(enginePath())));
    }

    /** The baseline ships inside the shim, so it is known good by definition and must never inherit the
     *  verdict that was hanging over the jar it replaced, or the next boot rolls the baseline back. */
    @Test
    void aReExtractedBaseline_isNeverLeftOnProbation() throws Exception {
        Files.createDirectories(enginePath().getParent());
        Files.writeString(EngineGuard.probation(enginePath()), "whatever was here before");
        EngineLoader.extract(enginePath(), EngineLoader.embeddedBaseline());
        assertFalse(Files.exists(EngineGuard.probation(enginePath())));
    }

    /**
     * at the rollback: a second instance of this profile holds engine.jar open for its whole session and
     * Windows refuses to replace an open file. That has to cost the RESTORE and nothing else, and the verdict
     * has to survive for the next boot instead of being spent on a rollback that never landed.
     * WINDOWS-ONLY as a declared fact, not an assumeTrue: POSIX renames over an open file happily, so there is
     * no refusal to survive there and the assertions below would be measuring the platform.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRollbackThatCannotLand_keepsTheVerdictForTheNextBoot() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(brick);
        Files.copy(good, EngineGuard.good(enginePath()));
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(brick));
        EngineLoader.Engine held = EngineLoader.load(enginePath()); // the other instance, jar open all session
        try {
            EngineLoader.Engine engine = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
            engine.loader().close();
            assertNotNull(engine);
            assertArrayEquals(bytes(brick), bytes(enginePath()));
            assertTrue(Files.isRegularFile(EngineGuard.probation(enginePath())));
            assertFalse(Files.exists(EngineGuard.rejects(enginePath())));
        } finally {
            held.loader().close();
        }
    }

    /**
     * The double-launch, which is the case InstanceLock exists for and the one the flag's content
     * decides: instance 1 promoted an engine seconds ago and is mid-sync behind it, instance 2 finds the flag
     * armed. Without the pid stamp instance 2 cannot tell that from "a previous boot failed", so on POSIX (where
     * a replace over an open jar SUCCEEDS) it rolled the healthy engine back, struck its sha, and deleted the
     * flag out from under the process that was about to clear it honestly.
     * A foreign LIVE pid is the only way to make this real, the same pick ServerDoorTest uses for the lock file.
     */
    @Test
    void aFlagArmedByAProcessThatIsStillRunning_isNotJudgedYet() throws Exception {
        Path good = engineJar("known-good");
        Path running = engineJar("running");
        install(running);
        Files.copy(good, EngineGuard.good(enginePath()));
        ProcessHandle other = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive()
                        && p.info().startInstant().isPresent())
                .findFirst().orElseThrow();
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(running) + "\t" + other.pid()
                + "\t" + other.info().startInstant().orElseThrow().toEpochMilli());

        boot();

        assertArrayEquals(bytes(running), bytes(enginePath()));
        assertTrue(Files.isRegularFile(EngineGuard.probation(enginePath())));
        assertFalse(Files.exists(EngineGuard.strikes(enginePath())));

        // the control: the same stamp naming a pid nobody owns IS judged, or a stale stamp freezes the guard
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(running) + "\t999999999\t1");
        boot();
        assertArrayEquals(bytes(good), bytes(enginePath()));
    }

    /**
     * The one branch that stops a FAILED rollback from destroying the last known-good engine, and it is reachable:
     * probation armed, the restore refused (a second instance holds engine.jar), and a staged engine.jar.new
     * waiting. Delete keepKnownGood's probation check and promoteStaged copies the UNPROVEN jar over .good, so
     * every later rollback restores the brick and the guard permanently installs the failure it exists to undo.
     * WINDOWS-ONLY for the same reason the refused-rollback test is: POSIX renames over an open file happily, so
     * there the restore lands, the flag clears, and the branch is never reached at all.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aPromotionOverAnUnprovenJar_neverOverwritesTheKnownGoodImage() throws Exception {
        Path good = engineJar("known-good");
        Path unproven = engineJar("unproven");
        Path next = engineJar("next");
        install(unproven);
        Files.copy(good, EngineGuard.good(enginePath()));
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(unproven));
        Files.copy(next, staged());
        EngineLoader.Engine held = EngineLoader.load(enginePath()); // the other instance, jar open all session
        try {
            boot();
            assertArrayEquals(bytes(good), bytes(EngineGuard.good(enginePath())));
        } finally {
            held.loader().close();
        }
    }

    /**
     * A promoted engine that cannot even LOAD never reached a verdict: the baseline re-extract that saves the
     * boot clears the flag on its way past (the baseline is not on trial), so nothing was ever held against those
     * bytes and the sync re-downloaded, re-promoted and re-failed them on EVERY boot for the life of that
     * release. A release naming an engine built for a newer Java is exactly that, LinkageError every time.
     */
    @Test
    void anEngineThatCannotEvenLOAD_earnsItsStrikesToo() throws Exception {
        install(engineJar("known-good"));
        byte[] garbage = "not a jar at all".getBytes(StandardCharsets.UTF_8);

        Files.write(staged(), garbage);
        boot(); // promoted, fails to load, the baseline saves the boot
        assertArrayEquals(baseline(), bytes(enginePath()));
        Files.write(staged(), garbage); // the release still names it, so the next sync stages it again
        boot(); // strike 2 burns it

        Files.write(staged(), garbage);
        boot();
        assertArrayEquals(baseline(), bytes(enginePath()));
        assertTrue(Files.isRegularFile(staged()));
    }

    // ---- fixtures: real loadable engine jars that are not each other ----

    /** The embedded baseline plus one marker entry: loads exactly like the engine does (that is the point of
     *  this whole guard) while hashing differently, so a restore is provable by bytes. */
    private Path engineJar(String marker) throws IOException {
        Path out = tmp.resolve(marker + ".jar");
        try (ZipInputStream zin = new ZipInputStream(EngineLoader.embeddedBaseline().get());
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                zout.putNextEntry(new ZipEntry(e.getName()));
                zin.transferTo(zout);
                zout.closeEntry();
            }
            zout.putNextEntry(new ZipEntry("guard-marker-" + marker + ".txt"));
            zout.write(marker.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        return out;
    }

    private void install(Path engine) throws IOException {
        Files.createDirectories(enginePath().getParent());
        Files.copy(engine, enginePath());
    }

    private byte[] baseline() throws IOException {
        try (InputStream in = EngineLoader.embeddedBaseline().get()) {
            return in.readAllBytes();
        }
    }

    private static byte[] bytes(Path p) throws IOException {
        return Files.readAllBytes(p);
    }
}
