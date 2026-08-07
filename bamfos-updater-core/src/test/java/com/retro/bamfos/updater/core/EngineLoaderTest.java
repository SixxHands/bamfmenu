package com.retro.bamfos.updater.core;

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
 * End-to-end EngineLoader check against the real embedded baseline jar: verifies atomic
 * extraction, isolated classloading of EngineEntry, and enter() round-trip.
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
        // pass a temp dir to all enter() calls; without it, the real engine defaults to "."
        // and CoreCargo.place writes .bamfos-updater/core/core-0001.jar into the repo
        assertEquals(0, engine.enter(new String[]{"--dir", tmp.toString()}));
        engine.loader().close();
    }

    /**
     * Verifies the engine classloader cannot see core classes. Uses CoreAbi (present on this test
     * classpath) so ClassNotFoundException proves isolation rather than an absent class.
     */
    @Test
    void engineIsIsolatedFromCoreClasses() throws Exception {
        EngineLoader.Engine engine = EngineLoader.ensureAndLoad(enginePath(), EngineLoader.embeddedBaseline());
        // engine classloader must NOT see core classes (platform parent, no delegation to core)
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.retro.bamfos.updater.core.CoreAbi", false, engine.loader()));
        engine.loader().close();
    }

    @Test
    void missingBaselineIsAnHonestError() {
        IOException e = assertThrows(IOException.class,
                () -> EngineLoader.ensureAndLoad(enginePath(), () -> null));
        assertTrue(e.getMessage().contains("baseline"), e.getMessage());
    }

    /**
     * Proves ensureAndLoad promotes a staged .new jar rather than leaving it untouched.
     * Puts junk in the engine slot and fails if the baseline supplier runs, proving the swap won.
     */
    @Test
    void stagedNew_isPromotedIntoTheEngineSlot_withoutFallingBackToTheBaseline() throws Exception {
        Files.createDirectories(enginePath().getParent());
        Files.write(enginePath(), new byte[]{0x00, 0x01, 0x02, 0x03}); // invalid jar; only the fallback swap can recover from this
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
            assertEquals(0, engine.enter(new String[]{"--dir", tmp.toString()}));
            assertFalse(askedForBaseline.get());
        } finally {
            engine.loader().close();
        }
    }

    /**
     * Verifies extract uses a pid-scoped temp file instead of <target>.tmp so siblings running
     * before InstanceLock cannot consume each other's half-written bytes.
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

    /** Failed extracts must delete their pid-scoped temp file rather than leaking half-written jars. */
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
     * When another instance locks engine.jar on Windows, a refused swap must not throw: keep
     * loading the current engine and leave .new intact for next boot. Windows-only (not assumeTrue
     * on .new survival) because POSIX renames over open files and would hide regressions.
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
            assertEquals(0, engine.enter(new String[]{"--dir", tmp.toString()}));
        } finally {
            if (engine != null) {
                engine.loader().close();
            }
            held.loader().close();
        }
    }
}
