package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the stop-sentinel path used by the shim and wrapper scripts, plus write-failure reporting. */
class StopFileTest {

    @TempDir
    Path dir;

    /** Wrapper scripts check for .bamfos-updater\stop by exact path, so pin the filename here. */
    @Test
    void itIsWrittenAtTheExactPathTheWrapperScriptsRead() {
        assertTrue(StopFile.write(dir));
        assertTrue(Files.isRegularFile(dir.resolve(".bamfos-updater").resolve("stop")));
        assertEquals("stop", StopFile.NAME);
    }

    /** Creates .bamfos-updater/ if missing, since a new server may write the stop file before its first sync. */
    @Test
    void aMissingUpdaterFolderIsMadeNotAFailure() {
        assertFalse(Files.exists(dir.resolve(".bamfos-updater")));
        assertTrue(StopFile.write(dir));
    }

    /**
     * Verifies write returns false when the sentinel cannot be created (simulated cross-platform by
     * placing a directory at the target path), so wrapped stops abort instead of relaunching.
     */
    @Test
    void anUnwritableSentinelAnswersFalseRatherThanThrowing() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater").resolve("stop"));
        assertFalse(StopFile.write(dir));
    }
}
