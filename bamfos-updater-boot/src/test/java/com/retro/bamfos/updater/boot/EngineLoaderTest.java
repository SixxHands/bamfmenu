package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
