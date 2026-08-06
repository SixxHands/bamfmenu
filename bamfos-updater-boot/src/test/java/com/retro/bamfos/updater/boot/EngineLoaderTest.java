package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M0 handoff proof. Uses the REAL embedded baseline resource (the engine jar the build packed
 * into this module's resources), so a green run means: baseline embedding works, atomic extraction
 * works, the isolated classloader finds EngineEntry, and enter() round-trips.
 */
class EngineLoaderTest {

    @TempDir
    Path tmp;

    private Path enginePath() {
        return tmp.resolve(".bamfos-updater").resolve("engine").resolve("bamfos-updater-engine.jar");
    }

    @Test
    void missingEngineExtractsBaselineAndRuns() throws Exception {
        EngineLoader.Engine engine = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
        assertTrue(Files.isRegularFile(enginePath()));
        assertEquals(0, engine.enter(new String[]{"--server", "--dir", tmp.toString()}));
        engine.loader().close();
    }

    @Test
    void corruptEngineFallsBackToBaselineOnce() throws Exception {
        Files.createDirectories(enginePath().getParent());
        Files.write(enginePath(), new byte[]{0x00, 0x01, 0x02, 0x03}); // not a jar at all
        EngineLoader.Engine engine = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
        assertEquals(0, engine.enter(new String[]{}));
        engine.loader().close();
    }

    @Test
    void engineIsIsolatedFromBootClasses() throws Exception {
        EngineLoader.Engine engine = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
        // the engine's loader must NOT see boot classes: platform parent, no delegation to us
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.retro.bamfos.updater.boot.UpdaterAbi", false, engine.loader()));
        engine.loader().close();
    }

    @Test
    void missingBaselineIsAnHonestError() {
        IOException e = assertThrows(IOException.class,
                () -> EngineLoader.ensureAndLoad(enginePath(), () -> null));
        assertTrue(e.getMessage().contains("baseline"), e.getMessage());
    }

    /**
     * The promotion itself, and it is the ONE hinge of the whole hot-updatable-engine design: SyncCore stages
     * bamfos-updater-engine.jar.new and nothing else on the client ever swaps it in. Deleting that call left
     * every other test in here green, because four of them never stage a .new at all and the locked-swap one
     * asserts the staged file SURVIVES, which is also exactly what "never attempted" looks like. Shipped that
     * way the engine is frozen on whatever jar is on disk forever while every sync re-stages the same .new,
     * pendingSelf stays true, and the POST block ends on "BIOS UPDATE COMPLETE. REBOOT TO APPLY." with
     * from == to for as long as the player keeps rebooting.
     * The baseline supplier is the witness that tells a real promotion from the corrupt-jar re-extract: junk
     * sits in the engine slot, so only the swap can produce a loadable engine, and asking for the baseline
     * proves the fallback ran instead.
     */
    @Test
    void stagedNew_isPromotedIntoTheEngineSlot_withoutFallingBackToTheBaseline() throws Exception {
        Files.createDirectories(enginePath().getParent());
        Files.write(enginePath(), new byte[]{0x00, 0x01, 0x02, 0x03}); // not a jar: only the swap saves this
        Path staged = enginePath().resolveSibling(enginePath().getFileName() + ".new");
        try (InputStream real = EngineLoader.embeddedBaseline().get()) {
            Files.copy(real, staged);
        }
        AtomicBoolean askedForBaseline = new AtomicBoolean();

        EngineLoader.Engine engine = EngineLoader.ensureAndLoad(enginePath(), () -> {
            askedForBaseline.set(true);
            return EngineLoader.embeddedBaseline().get();
        });
        try {
            assertFalse(Files.exists(staged));
            assertEquals(0, engine.enter(new String[]{}));
            assertFalse(askedForBaseline.get());
        } finally {
            engine.loader().close();
        }
    }

    /**
     * The double-launch tmp collision: everything ensureAndLoad does to the slot runs before any InstanceLock
     * exists, and extract used to share one literal <target>.tmp with EngineGuard's copyThroughTmp, so process
     * A's move could promote process B's half-written bytes into engine.jar (and from there over .good). The
     * planted file below is the sibling's in-flight tmp under that old shared name: not ours to write through
     * or consume, and a run that ate it is the shared name back.
     */
    @Test
    void extractNeverTouchesASiblingsInFlightTmp() throws Exception {
        Files.createDirectories(enginePath().getParent());
        Path siblingTmp = enginePath().resolveSibling(enginePath().getFileName() + ".tmp");
        byte[] midWrite = "half of some other process's copy".getBytes(StandardCharsets.UTF_8);
        Files.write(siblingTmp, midWrite);

        EngineLoader.extract(enginePath(), EngineLoader.embeddedBaseline());

        try (InputStream in = EngineLoader.embeddedBaseline().get()) {
            assertArrayEquals(in.readAllBytes(), Files.readAllBytes(enginePath()));
        }
        assertTrue(Files.isRegularFile(siblingTmp));
        assertArrayEquals(midWrite, Files.readAllBytes(siblingTmp));
    }

    /**
     * The other half of owning the tmp name: a leftover under the shared name used to get recycled by the
     * next extract's REPLACE_EXISTING, but a pid-named tmp is ours alone and nothing else will ever take it,
     * so the failure path has to delete it or every failed extract squats another half-jar in the engine
     * folder forever.
     */
    @Test
    void aFailedExtractCleansUpItsOwnTmp() throws Exception {
        Files.createDirectories(enginePath().getParent());

        assertThrows(IOException.class, () -> EngineLoader.extract(enginePath(), () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("wire died mid-baseline");
            }
        }));

        try (var siblings = Files.list(enginePath().getParent())) {
            assertEquals(List.of(), siblings.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList());
        }
        assertFalse(Files.exists(enginePath()));
    }

    /**
     * The second-window case, and the reason the swap is not allowed to throw: instance A holds engine.jar
     * open through the URLClassLoader load() deliberately never closes, so instance B's swap of a staged
     * engine.jar.new hits a Windows sharing violation. That has to cost the SWAP and nothing else, because the
     * jar on disk was fine the whole time and letting it escape means no sync, no ops binding and a BIOS
     * reading NOT INSTALLED for the whole session.
     * WINDOWS-ONLY as a declared fact, NOT an assumeTrue on the file's survival: POSIX renames over an open
     * file happily, so there is no refusal to survive there, and reading the staged file's absence as "the
     * platform took it" would swallow a real regression, a promoteStaged that LOSES that file on its failure
     * path aborts the run and JUnit reports the whole thing green.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void lockedStagedNew_stillLoadsTheEngineOnDisk() throws Exception {
        EngineLoader.Engine held = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
        Path staged = enginePath().resolveSibling(enginePath().getFileName() + ".new");
        Files.writeString(staged, "a real file, so the isRegularFile guard lets this reach the move");
        EngineLoader.Engine engine = null;
        try {
            engine = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
            assertTrue(Files.isRegularFile(staged));
            assertEquals(0, engine.enter(new String[]{}));
        } finally {
            if (engine != null) {
                engine.loader().close();
            }
            held.loader().close();
        }
    }
}
