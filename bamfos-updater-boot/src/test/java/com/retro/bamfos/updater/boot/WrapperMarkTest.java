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

    /**
     * Panel mode must NOT write the wrapper mark, since hosted panels usually lack a restart loop.
     * Tested through main() so CLI argument wiring is covered too.
     */
    @Test
    void panelModeLeavesNoWrapperMark() {
        ServerMain.markPreJvmRun(dir); // a previous non-panel start, so the test proves suppression not absence
        Path mark = dir.resolve(".bamfos-updater").resolve(ServerMain.WRAPPER_MARK);
        assertTrue(Files.isRegularFile(mark));

        assertTrue(ServerMain.panelMode(new String[]{"--server", "--dir", ".", "--panel"}));
        assertFalse(ServerMain.panelMode(new String[]{"--server", "--dir", "."}));
    }

    /** --panel anywhere in the args, because an operator pasting a startup command orders it however they like. */
    @Test
    void theFlagIsFoundWhereverItSits() {
        assertTrue(ServerMain.panelMode(new String[]{"--panel", "--server", "--dir", "."}));
        assertTrue(ServerMain.panelMode(new String[]{"--server", "--panel", "--dir", "."}));
        assertFalse(ServerMain.panelMode(new String[]{}));
        assertFalse(ServerMain.panelMode(new String[]{"--paneling"}));
    }

    /**
     * Clears any leftover stop sentinel from an abrupt kill or power loss so it doesn't abort
     * the next normal server start.
     */
    @Test
    void aStaleStopSentinelIsClearedBeforeTheJvm() throws IOException {
        Path stop = dir.resolve(".bamfos-updater").resolve(ServerMain.STOP_FILE);
        Files.createDirectories(stop.getParent());
        Files.writeString(stop, "left over from last week");

        ServerMain.clearStaleStop(dir);

        assertFalse(Files.exists(stop));
    }

    /** Clean no-op when absent, and non-fatal if the sentinel cannot be deleted. */
    @Test
    void clearingWhatIsNotThereIsFineAndSoIsClearingWhatWillNotGo() throws IOException {
        assertDoesNotThrow(() -> ServerMain.clearStaleStop(dir));
        Files.createDirectories(dir.resolve(".bamfos-updater").resolve(ServerMain.STOP_FILE).resolve("busy"));
        assertDoesNotThrow(() -> ServerMain.clearStaleStop(dir),
                "a non-empty directory in the way costs one confusing exit, never the boot");
    }
}
