package com.retro.bamfrestore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for rollback validation rules that don't require launching the game. */
class RestoreBrainTest {

    /** entries at the zip root, level.dat right there */
    @Test
    void contentRootedZipIsFound() {
        assertEquals(Optional.of(""), RestoreBrain.findWorldRoot(
                List.of("level.dat", "region/r.0.0.mca", "backup.log")));
    }

    /** Zip entries nested under the world folder name. */
    @Test
    void folderRootedZipIsFound() {
        assertEquals(Optional.of("world/"), RestoreBrain.findWorldRoot(
                List.of("world/level.dat", "world/region/r.0.0.mca")));
    }

    /** Reject zips with no level.dat instead of guessing. */
    @Test
    void aZipWithoutAWorldIsRefused() {
        assertTrue(RestoreBrain.findWorldRoot(List.of("region/r.0.0.mca", "backup.log")).isEmpty());
    }

    /** Reject zips that contain more than one world. */
    @Test
    void twoWorldsAreRefused() {
        assertTrue(RestoreBrain.findWorldRoot(
                List.of("world/level.dat", "world_nether/level.dat")).isEmpty());
    }

    /** a nested level.dat deeper than one folder must not count as a root */
    @Test
    void deepLevelDatDoesNotCount() {
        assertTrue(RestoreBrain.findWorldRoot(List.of("a/b/level.dat")).isEmpty());
    }

    /** Both marker files and command input go through this check to block path traversal. */
    @Test
    void dirtyNamesAreRefused() {
        assertTrue(RestoreBrain.isCleanRelativeZip("2026-08-22_17-00-00.zip"));
        // standard Simple Backups layout: one subdirectory per world
        assertTrue(RestoreBrain.isCleanRelativeZip("world/world_2026-07-18_18-45-04.zip"));
        assertFalse(RestoreBrain.isCleanRelativeZip("../world.zip"));
        assertFalse(RestoreBrain.isCleanRelativeZip("world/../../secrets.zip"));
        assertFalse(RestoreBrain.isCleanRelativeZip("a/b/c.zip"));
        assertFalse(RestoreBrain.isCleanRelativeZip("a\\b.zip"));
        assertFalse(RestoreBrain.isCleanRelativeZip("/abs.zip"));
        assertFalse(RestoreBrain.isCleanRelativeZip("backup.tar.gz"));
        assertFalse(RestoreBrain.isCleanRelativeZip(""));
    }

    /** Ignore backup.log at the zip root when captureLatestLog is enabled. */
    @Test
    void backupLogIsJunk() {
        assertTrue(RestoreBrain.isJunkEntry("backup.log"));
        assertFalse(RestoreBrain.isJunkEntry("region/backup.log"));
    }
}
