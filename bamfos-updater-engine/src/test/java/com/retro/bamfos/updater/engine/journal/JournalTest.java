package com.retro.bamfos.updater.engine.journal;

import com.retro.bamfos.updater.engine.util.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Journal tests: offline crash replay, upgrade-pair ordering, stale-byte no-ops, and locking. */
class JournalTest {

    @TempDir
    Path dir;

    private static final byte[] NEW = "new bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void crashMidApply_replayConvergesOffline_andIsIdempotent() throws IOException {
        // staged bytes + a journal for an upgrade pair: delete old jar, move new one in
        Files.createDirectories(dir.resolve(".bamfos-updater/staging"));
        Files.write(dir.resolve(".bamfos-updater/staging/part1"), NEW);
        Files.createDirectories(dir.resolve("mods"));
        Files.write(dir.resolve("mods/old-1.0.jar"), "old".getBytes());
        Journal j = new Journal();
        j.targetVersion = "v2";
        j.ops.add(new Journal.Op("delete", "", "mods/old-1.0.jar", ""));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/part1", "mods/new-2.0.jar", HashUtils.sha256(NEW)));
        j.save(Journal.pathIn(dir));

        // Simulate a crash before anything runs; next startup replays from the saved journal.
        Journal loaded = Journal.load(Journal.pathIn(dir));
        assertEquals("v2", loaded.targetVersion);
        loaded.replay(dir);
        assertFalse(Files.exists(dir.resolve("mods/old-1.0.jar")));
        assertArrayEquals(NEW, Files.readAllBytes(dir.resolve("mods/new-2.0.jar")));
        loaded.replay(dir); // half-replayed or repeated replays are the normal case, not an error
        assertArrayEquals(NEW, Files.readAllBytes(dir.resolve("mods/new-2.0.jar")));
    }

    /** Drop ops after three failed boots so permanently locked or read-only targets stop looping forever. */
    @Test
    void aStuckOpIsGivenUpOnAfterThreeBootsInsteadOfForever() {
        Journal.Op op = new Journal.Op("delete", "", "mods/held.jar", "");
        assertEquals(0, op.attempts());

        java.util.List<Journal.Op> carried = java.util.List.of(op);
        for (int boot = 1; boot < Journal.MAX_ATTEMPTS; boot++) {
            Journal.Stuck stuck = Journal.afterAttempt(carried);
            assertTrue(stuck.gaveUp().isEmpty(), String.valueOf(boot));
            assertEquals(boot, stuck.keep().get(0).attempts());
            carried = stuck.keep();
        }

        Journal.Stuck last = Journal.afterAttempt(carried);
        assertTrue(last.keep().isEmpty());
        assertEquals("mods/held.jar", last.gaveUp().get(0).to());
    }

    /** Retry count must survive save/load, otherwise it resets to 0 on every boot. */
    @Test
    void theAttemptCountSurvivesSaveAndLoad() throws IOException {
        Journal j = new Journal();
        j.ops.add(new Journal.Op("delete", "", "mods/held.jar", "", 2));
        j.ops.add(new Journal.Op("delete", "", "mods/fresh.jar", ""));
        j.save(Journal.pathIn(dir));

        String raw = Files.readString(Journal.pathIn(dir), StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"attempts\""), String.valueOf(raw));
        assertEquals(1, raw.split("\"attempts\"", -1).length - 1, String.valueOf(raw));

        Journal back = Journal.load(Journal.pathIn(dir));
        assertEquals(2, back.ops.get(0).attempts());
        assertEquals(0, back.ops.get(1).attempts());
    }

    /** Keep journal attempt counts forward- and backward-compatible across engine versions by defaulting
     * missing values to zero. */
    @Test
    void aJournalFromAnEngineWithNoCounterReadsAsFresh() throws IOException {
        Files.createDirectories(Journal.pathIn(dir).getParent());
        Files.writeString(Journal.pathIn(dir),
                "{\"target_version\":\"v2\",\"ops\":[{\"kind\":\"delete\",\"from\":\"\",\"to\":\"mods/x.jar\","
                        + "\"sha256\":\"\"}]}", StandardCharsets.UTF_8);

        Journal back = Journal.load(Journal.pathIn(dir));

        assertEquals(1, back.ops.size());
        assertEquals(0, back.ops.get(0).attempts());
    }

    /** Operators sometimes hand-edit the journal when an update gets stuck. */
    @Test
    void aNonsenseAttemptValueIsTreatedAsFreshNotAsAThrow() throws IOException {
        Files.createDirectories(Journal.pathIn(dir).getParent());
        Files.writeString(Journal.pathIn(dir),
                "{\"target_version\":\"v2\",\"ops\":[{\"kind\":\"delete\",\"from\":\"\",\"to\":\"mods/x.jar\","
                        + "\"sha256\":\"\",\"attempts\":\"lots\"}]}", StandardCharsets.UTF_8);

        Journal back = assertDoesNotThrow(() -> Journal.load(Journal.pathIn(dir)));

        assertEquals(0, back.ops.get(0).attempts());
    }

    @Test
    void staleStagedBytes_areNeverPlaced() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater/staging"));
        Files.write(dir.resolve(".bamfos-updater/staging/part1"), "tampered".getBytes());
        Journal j = new Journal();
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/part1", "mods/x.jar", HashUtils.sha256(NEW)));
        j.replay(dir); // the rollback race: journal expects NEW, staging keeps something else
        assertFalse(Files.exists(dir.resolve("mods/x.jar")));
    }

    /** Do not overwrite a user's existing .disabled twin during step-0 replay; mark the op abandoned
     * (not retryable) so it neither clobbers the file nor loops forever. */
    @Test
    void quarantineRename_refusesAnOccupiedDisabledName_insteadOfEatingTheirFile() throws IOException {
        byte[] culprit = "the jar on the kill list".getBytes(StandardCharsets.UTF_8);
        byte[] parked = "the player's own parked copy".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("mods"));
        Files.write(dir.resolve("mods/foo.jar"), culprit);
        Files.write(dir.resolve("mods/foo.jar.disabled"), parked); // appeared between the plan and this replay
        Journal j = new Journal();
        j.ops.add(new Journal.Op("rename", "mods/foo.jar", "mods/foo.jar.disabled", HashUtils.sha256(culprit)));

        Journal.Result r = j.replay(dir);
        assertEquals(1, r.abandoned().size());
        assertTrue(r.retryable().isEmpty());
        assertArrayEquals(parked, Files.readAllBytes(dir.resolve("mods/foo.jar.disabled")));
        assertArrayEquals(culprit, Files.readAllBytes(dir.resolve("mods/foo.jar")));
    }

    /** Report missing or stale staged sources as abandoned instead of skipping or retrying them, so
     * we never commit the ledger for files that didn't get written. */
    @Test
    void aSourceThatIsGoneOrWrong_isReportedAbandoned_neverSilentlySkipped() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater/staging"));
        Files.write(dir.resolve(".bamfos-updater/staging/stale"), "tampered".getBytes(StandardCharsets.UTF_8));
        Journal j = new Journal();
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/gone", "mods/a.jar", HashUtils.sha256(NEW)));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/stale", "mods/b.jar", HashUtils.sha256(NEW)));

        Journal.Result r = j.replay(dir);
        assertEquals(2, r.abandoned().size());
        assertTrue(r.retryable().isEmpty());
        assertFalse(Files.exists(dir.resolve("mods/a.jar")));
        assertFalse(Files.exists(dir.resolve("mods/b.jar")));
    }

    /** Managed file placements still overwrite existing files since upgrades replace the old version. */
    @Test
    void managedMove_stillReplacesTheOldFile() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater/staging"));
        Files.write(dir.resolve(".bamfos-updater/staging/part1"), NEW);
        Files.createDirectories(dir.resolve("mods"));
        Files.write(dir.resolve("mods/x.jar"), "the old version".getBytes(StandardCharsets.UTF_8));
        Journal j = new Journal();
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/part1", "mods/x.jar", HashUtils.sha256(NEW)));
        assertTrue(j.replay(dir).allLanded());
        assertArrayEquals(NEW, Files.readAllBytes(dir.resolve("mods/x.jar")));
    }

    /**
     * A power loss during replay can leave journal.json truncated or zeroed. Treat an unreadable journal as
     * abandoned instead of throwing out of step 0, so the ledger gate re-plans the missing work on next boot.
     */
    @Test
    void anUnreadableJournal_isDiscarded_neverBricksEveryFutureBoot() throws IOException {
        Path file = Journal.pathIn(dir);
        Files.createDirectories(file.getParent());

        Files.writeString(file, "{\"target_version\":\"v2\",\"ops\":[{\"kind\":\"mov"); // cut off mid-write
        assertTrue(Journal.load(file).ops.isEmpty());
        assertFalse(Files.exists(file));

        Files.write(file, new byte[64]); // the zero-filled block a power loss leaves behind
        assertTrue(Journal.load(file).ops.isEmpty());
        assertFalse(Files.exists(file));

        Files.write(file, new byte[]{(byte) 0xC3}); // not even UTF-8: readString throws before the parser
        assertTrue(Journal.load(file).ops.isEmpty());
        assertFalse(Files.exists(file));
    }

    /** Progress bar relies on these callbacks. Factory reset journals have thousands of ops, so
     *  missing notifications would make the progress bar look frozen. */
    @Test
    void theWatcherIsToldAboutEveryOpOnceAndInOrder() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater/staging"));
        Files.write(dir.resolve(".bamfos-updater/staging/part1"), NEW);
        Files.createDirectories(dir.resolve("mods"));
        Files.write(dir.resolve("mods/old.jar"), "old".getBytes(StandardCharsets.UTF_8));
        Journal j = new Journal();
        j.ops.add(new Journal.Op("delete", "", "mods/old.jar", ""));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/part1", "mods/new.jar", HashUtils.sha256(NEW)));

        java.util.List<String> seen = new java.util.ArrayList<>();
        j.replay(dir, (op, done, total) -> seen.add(op.to() + " " + done + "/" + total));

        assertEquals(java.util.List.of("mods/old.jar 1/2", "mods/new.jar 2/2"), seen);
    }

    /** Verify check on a normal successful boot should report nothing; a false positive here would
     *  trigger a full re-derive. */
    @Test
    void verifyProvesAnApplyThatActuallyLanded() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater/staging"));
        Files.write(dir.resolve(".bamfos-updater/staging/part1"), NEW);
        Files.createDirectories(dir.resolve("mods"));
        Files.write(dir.resolve("mods/old.jar"), "old".getBytes(StandardCharsets.UTF_8));
        Journal j = new Journal();
        j.ops.add(new Journal.Op("delete", "", "mods/old.jar", ""));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/part1", "mods/new.jar", HashUtils.sha256(NEW)));
        assertTrue(j.replay(dir).allLanded());

        assertTrue(Journal.verify(dir, j.ops, (op, done, total) -> { }).isEmpty());
    }

    /**
     * Tests the three ways a journal op can claim success while wrong on disk, which matters when the boot
     * after a factory reset has no network. Built by hand since replay itself shouldn't produce these states.
     */
    @Test
    void verifyCatchesAnOpThatSaidItLandedAndDidNot() throws IOException {
        Files.createDirectories(dir.resolve("mods"));
        Files.write(dir.resolve("mods/survived.jar"), "still here".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("mods/wrong.jar"), "not the bytes we placed".getBytes(StandardCharsets.UTF_8));
        java.util.List<Journal.Op> ops = java.util.List.of(
                new Journal.Op("delete", "", "mods/survived.jar", ""),
                new Journal.Op("move", ".bamfos-updater/staging/p", "mods/wrong.jar", HashUtils.sha256(NEW)),
                new Journal.Op("move", ".bamfos-updater/staging/q", "mods/never-arrived.jar", HashUtils.sha256(NEW)));

        java.util.List<Journal.Op> wrong = Journal.verify(dir, ops, (op, done, total) -> { });

        assertEquals(3, wrong.size(), String.valueOf(wrong));
    }

    /** Deleting and then placing the same path shouldn't be flagged as a failed delete when the
     *  new file is present at the end. */
    @Test
    void verifyDoesNotFailADeleteThatALaterOpPutsBack() throws IOException {
        Files.createDirectories(dir.resolve("config"));
        Files.write(dir.resolve("config/foo.json"), NEW);
        java.util.List<Journal.Op> ops = java.util.List.of(
                new Journal.Op("delete", "", "config/foo.json", ""),
                new Journal.Op("move", ".bamfos-updater/staging/p", "config/foo.json", HashUtils.sha256(NEW)));

        assertTrue(Journal.verify(dir, ops, (op, done, total) -> { }).isEmpty());
    }

    /**
     * "Later in the same journal" is positional: a placement before a delete must not excuse a failed delete,
     * or step 0 would vouch for a file the journal says to remove.
     */
    @Test
    void verifyOnlyExcusesADeleteForAPlacementThatComesAfterIt() throws IOException {
        java.util.List<Journal.Op> ops = java.util.List.of(
                new Journal.Op("move", ".bamfos-updater/staging/p", "config/bar.json", HashUtils.sha256(NEW)),
                new Journal.Op("delete", "", "config/bar.json", ""));

        // Delete ran last and the file is still there, so report the failed delete
        // even though an earlier placement succeeded.
        Files.createDirectories(dir.resolve("config"));
        Files.write(dir.resolve("config/bar.json"), NEW);
        java.util.List<Journal.Op> wrong = Journal.verify(dir, ops, (op, done, total) -> { });
        assertTrue(wrong.stream().anyMatch(o -> o.kind().equals("delete")), String.valueOf(wrong));

        // With the file gone, the delete succeeded and only the earlier placement can't be verified on disk.
        Files.delete(dir.resolve("config/bar.json"));
        wrong = Journal.verify(dir, ops, (op, done, total) -> { });
        assertEquals(1, wrong.size(), wrong.toString());
        assertEquals("move", wrong.get(0).kind());
    }

    @Test
    void lock_blocksLiveHolder_costsNothingAfterACrash() throws IOException {
        // A leftover lock file with no active OS lock (e.g. after kill -9) should acquire cleanly without extra cleanup.
        Files.createDirectories(InstanceLock.pathIn(dir).getParent());
        Files.writeString(InstanceLock.pathIn(dir), "999999999");
        Optional<InstanceLock> first = InstanceLock.acquire(dir);
        assertTrue(first.isPresent());

        // While locked, a second acquire returns empty. Same-JVM contention matches a BIOS op racing
        // a sync, and cross-process locking behaves the same way at the OS level.
        assertTrue(InstanceLock.acquire(dir).isEmpty());

        first.get().close();
        Optional<InstanceLock> again = InstanceLock.acquire(dir);
        assertTrue(again.isPresent());
        again.get().close();
    }
}
