package com.retro.bamfos.updater.core;

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
 * Tests boot-once probation rollback using real loadable engine jars from the embedded baseline,
 * covering engines that load fine but fail at runtime (where corrupt-jar fallback never runs).
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

    /**
     * Simulates one shim boot (check, promote, load) and closes the loader right away so Windows
     * lets the next boot replace the jar.
     */
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

        boot(); // stage the new engine jar from the release
        assertArrayEquals(bytes(brick), bytes(enginePath()));
        assertTrue(Files.isRegularFile(EngineGuard.probation(enginePath())));
        assertTrue(Files.isRegularFile(EngineGuard.good(enginePath())));
        assertArrayEquals(bytes(good), bytes(EngineGuard.good(enginePath())));

        boot(); // previous boot never cleared the flag, so this boot rolls it back
        assertArrayEquals(bytes(good), bytes(enginePath()));
        assertFalse(Files.exists(EngineGuard.probation(enginePath())));
        assertEquals(List.of(EngineGuard.sha256(brick) + "\t1"),
                Files.readAllLines(EngineGuard.strikes(enginePath()), StandardCharsets.UTF_8));
        assertFalse(Files.exists(EngineGuard.rejects(enginePath())));
    }

    /**
     * Two strikes permanently reject that engine sha so syncs stop re-staging the same broken
     * release every boot.
     */
    @Test
    void aSecondUnprovenBootOfTheSameBytes_burnsThemForGood() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(good);

        Files.copy(brick, staged());
        boot(); // promoted, armed
        boot(); // rolled back, strike 1
        Files.copy(brick, staged()); // release still lists this version, so the next sync stages it again
        boot(); // promoted again, armed again
        boot(); // rolled back, strike 2

        assertArrayEquals(bytes(good), bytes(enginePath()));
        // Assert the file exists before reading so a missing guard file fails cleanly instead of throwing NoSuchFileException.
        assertTrue(Files.isRegularFile(EngineGuard.rejects(enginePath())));
        assertTrue(Files.readAllLines(EngineGuard.rejects(enginePath()), StandardCharsets.UTF_8).get(0)
                        .startsWith(EngineGuard.sha256(brick) + "\t"));
        Files.copy(brick, staged());
        boot();
        assertArrayEquals(bytes(good), bytes(enginePath()));
        assertTrue(Files.isRegularFile(staged()));
    }

    /**
     * Once a sha is rejected, promoteStaged must refuse it instead of alternating promote and
     * rollback every other boot until a new release ships.
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

    /**
     * An engine that already reported a healthy run must not be rolled back,
     * or the guard would downgrade working installs on every update.
     */
    @Test
    void provenEngine_isLeftAloneOnTheNextBoot() throws Exception {
        Path good = engineJar("known-good");
        Path fresh = engineJar("fresh");
        install(good);
        Files.copy(fresh, staged());
        boot();

        // mirrors what EngineHealth.proven() does from inside the engine
        Files.delete(EngineGuard.probation(enginePath()));

        boot();
        assertArrayEquals(bytes(fresh), bytes(enginePath()));
        assertFalse(Files.exists(EngineGuard.rejects(enginePath())));
    }

    /**
     * Checks that EngineGuard and the embedded engine use the same probation flag name.
     * The modules aren't linked at compile time, so renaming one side would roll back every engine update.
     */
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

    /**
     * A fresh install that has never promoted an engine has no rollback copy, so fall back to
     * the bundled baseline jar in the shim instead of failing the boot.
     */
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
     * An unreadable .good (e.g. AV lock) must fall through to the embedded baseline instead of
     * keeping the unproven engine. Windows-only because POSIX advisory locks don't block reads.
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

    /**
     * The embedded baseline jar is known-good, so restoring it must clear any pending
     * failure marker or the next boot will roll the baseline back too.
     */
    @Test
    void aReExtractedBaseline_isNeverLeftOnProbation() throws Exception {
        Files.createDirectories(enginePath().getParent());
        Files.writeString(EngineGuard.probation(enginePath()), "whatever was here before");
        EngineLoader.extract(enginePath(), EngineLoader.embeddedBaseline());
        assertFalse(Files.exists(EngineGuard.probation(enginePath())));
    }

    /**
     * If another instance locks engine.jar during rollback, fail cleanly and keep probation armed
     * for the next boot. Windows-only because POSIX renames over open files.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRollbackThatCannotLand_keepsTheVerdictForTheNextBoot() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(brick);
        Files.copy(good, EngineGuard.good(enginePath()));
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(brick));
        EngineLoader.Engine held = EngineLoader.load(enginePath()); // simulate another instance keeping the jar open
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
     * Double-launch guard: a live foreign pid in the probation flag means a sibling is mid-sync,
     * so skip rollback and leave the flag intact.
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

        // a stamp with a dead PID must still be evaluated so stale stamps don't freeze the guard
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(running) + "\t999999999\t1");
        boot();
        assertArrayEquals(bytes(good), bytes(enginePath()));
    }

    /**
     * Verifies copyThroughTmp uses a pid-scoped temp file instead of <target>.tmp so siblings
     * running before InstanceLock cannot consume each other's half-written jar.
     */
    @Test
    void aRollbackNeverTouchesASiblingsInFlightTmp() throws Exception {
        Path good = engineJar("known-good");
        Path brick = engineJar("brick");
        install(brick);
        Files.copy(good, EngineGuard.good(enginePath()));
        Files.writeString(EngineGuard.probation(enginePath()), EngineGuard.sha256(brick));
        Path siblingTmp = enginePath().resolveSibling(enginePath().getFileName() + ".tmp");
        byte[] midWrite = "half of some other process's copy".getBytes(StandardCharsets.UTF_8);
        Files.write(siblingTmp, midWrite);

        boot();

        assertArrayEquals(bytes(good), bytes(enginePath()));
        assertTrue(Files.isRegularFile(siblingTmp));
        assertArrayEquals(midWrite, bytes(siblingTmp));
    }

    /**
     * Failed keepKnownGood moves must clean up their pid-scoped temp file instead of leaking a
     * full jar copy. Windows-only because POSIX renames over open files.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRefusedKnownGoodCopyLeavesNoTmpBehind() throws Exception {
        Path current = engineJar("current");
        Path next = engineJar("next");
        install(current);
        Files.copy(engineJar("old-good"), EngineGuard.good(enginePath()));
        Files.copy(next, staged());
        // keep .good open like another instance locking engine.jar so replacing it fails
        EngineLoader.Engine holdsGood = EngineLoader.load(EngineGuard.good(enginePath()));
        try {
            boot();
        } finally {
            holdsGood.loader().close();
        }
        try (var siblings = Files.list(enginePath().getParent())) {
            assertEquals(List.of(), siblings.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList());
        }
        assertArrayEquals(bytes(next), bytes(enginePath()));
    }

    /**
     * If rollback fails (probation still armed), keepKnownGood must NOT overwrite .good with the
     * unproven jar during a later promote. Windows-only because POSIX lets rollback succeed.
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
        EngineLoader.Engine held = EngineLoader.load(enginePath()); // simulate another instance keeping the jar open
        try {
            boot();
            assertArrayEquals(bytes(good), bytes(EngineGuard.good(enginePath())));
        } finally {
            held.loader().close();
        }
    }

    /**
     * A promoted jar that fails to load (e.g. LinkageError) must still take a strike before
     * baseline re-extract clears probation, preventing endless re-download loops.
     */
    @Test
    void anEngineThatCannotEvenLOAD_earnsItsStrikesToo() throws Exception {
        install(engineJar("known-good"));
        byte[] garbage = "not a jar at all".getBytes(StandardCharsets.UTF_8);

        Files.write(staged(), garbage);
        boot(); // promoted jar fails to load, so we fall back to the baseline
        assertArrayEquals(baseline(), bytes(enginePath()));
        Files.write(staged(), garbage); // release still lists this version, so the next sync stages it again
        boot(); // strike 2 burns it

        Files.write(staged(), garbage);
        boot();
        assertArrayEquals(baseline(), bytes(enginePath()));
        assertTrue(Files.isRegularFile(staged()));
    }

    // test fixtures: distinct loadable engine jars

    /** Builds a valid engine jar from the baseline plus an extra marker entry so it has a different hash. */
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
