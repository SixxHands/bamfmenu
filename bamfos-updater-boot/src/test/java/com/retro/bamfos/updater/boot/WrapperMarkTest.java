package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the writer side of the wrapper-presence mark read by WatchdogState.wrapperRan.
 * The modules don't link, so filename and format are the contract; missing or invalid marks disable auto_restart.
 */
class WrapperMarkTest {

    @TempDir
    Path dir;

    @Test
    void theMarkIsAParseableEpochUnderTheUpdaterFolder() throws IOException {
        long before = System.currentTimeMillis();
        ServerMain.markPreJvmRun(dir);
        long after = System.currentTimeMillis();

        Path f = dir.resolve(".bamfos-updater").resolve(ServerMain.WRAPPER_MARK);
        assertTrue(Files.isRegularFile(f));
        long at = Long.parseLong(Files.readString(f).trim());
        assertTrue(at >= before && at <= after);
    }

    /** Creates .bamfos-updater/ if missing so the first pre-JVM run on a fresh server still writes the mark. */
    @Test
    void aFreshServerRootGetsTheFolderMade() {
        assertFalse(Files.exists(dir.resolve(".bamfos-updater")));
        ServerMain.markPreJvmRun(dir);
        assertTrue(Files.isRegularFile(dir.resolve(".bamfos-updater").resolve(ServerMain.WRAPPER_MARK)));
    }

    /** Never fatal on write failure (simulated by placing a file where the updater directory belongs). */
    @Test
    void anUnwritableRootThrowsNothing() throws IOException {
        Files.writeString(dir.resolve(".bamfos-updater"), "not a folder");
        assertDoesNotThrow(() -> ServerMain.markPreJvmRun(dir));
    }
}
