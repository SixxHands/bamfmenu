package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Panel mode must clear any leftover wrapper mark, not just skip writing one.
 * Otherwise switching from run-server.bat to a panel leaves a fresh mark that makes the watchdog
 * halt the server with no wrapper loop to bring it back.
 */
class WrapperMarkClearTest {

    @TempDir
    Path dir;

    /**
     * Uses markPreJvmRun to prove the clear targets the same path; after panel mode runs,
     * no mark should remain so the watchdog stays announce-only.
     */
    @Test
    void aMarkFromTheMorningWrapperStartIsGoneAfterThePanelStart() {
        ServerMain.markPreJvmRun(dir);
        Path mark = dir.resolve(".bamfos-updater").resolve(ServerMain.WRAPPER_MARK);
        assertTrue(Files.isRegularFile(mark));

        ServerMain.clearWrapperMark(dir);

        assertFalse(Files.exists(mark));
    }

    /** Clearing the mark must leave sibling files in .bamfos-updater/ (such as the engine jar) untouched. */
    @Test
    void theStopSentinelAndTheEngineFolderSurviveTheClear() throws IOException {
        Path updater = dir.resolve(".bamfos-updater");
        Path engineJar = updater.resolve("engine").resolve("bamfos-updater-engine.jar");
        Files.createDirectories(engineJar.getParent());
        Files.writeString(engineJar, "the engine this start is about to load");
        Files.writeString(updater.resolve(ServerMain.STOP_FILE), "clearStaleStop owns this one");
        ServerMain.markPreJvmRun(dir);

        ServerMain.clearWrapperMark(dir);

        assertFalse(Files.exists(updater.resolve(ServerMain.WRAPPER_MARK)));
        assertTrue(Files.isRegularFile(engineJar));
        assertTrue(Files.isRegularFile(updater.resolve(ServerMain.STOP_FILE)));
    }

    /** Never fatal when the mark is missing or cannot be deleted (simulated with a non-empty directory). */
    @Test
    void anUndeletableMarkCostsTheSafetyNotTheBoot() throws IOException {
        assertDoesNotThrow(() -> ServerMain.clearWrapperMark(dir),
                "a panel-only box has no mark to clear and that is the common case, not an error");

        Files.createDirectories(dir.resolve(".bamfos-updater").resolve(ServerMain.WRAPPER_MARK).resolve("busy"));
        assertDoesNotThrow(() -> ServerMain.clearWrapperMark(dir),
                "throwing here would put the updater between the operator and a server that boots, which is "
                        + "the one thing the shim is never allowed to do");
    }
}
