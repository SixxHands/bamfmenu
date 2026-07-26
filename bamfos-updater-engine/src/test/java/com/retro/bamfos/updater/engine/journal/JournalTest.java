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

    @Test
    void lock_blocksLiveHolder_reclaimsDeadHolder() throws IOException {
        // a live holder (this very JVM) blocks a second acquire
        Files.createDirectories(InstanceLock.pathIn(dir).getParent());
        Files.writeString(InstanceLock.pathIn(dir), Long.toString(ProcessHandle.current().pid()));
        // NOTE: acquire treats our own pid as reclaimable (holder == self happens only after a crash
        // that reused the pid, vanishingly rare) -- so simulate a live OTHER process with a system one
        Optional<ProcessHandle> other = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive()).findFirst();
        assertTrue(other.isPresent());
        Files.writeString(InstanceLock.pathIn(dir), Long.toString(other.get().pid()));
        assertTrue(InstanceLock.acquire(dir).isEmpty());

        Files.writeString(InstanceLock.pathIn(dir), "999999999"); // dead pid
        Optional<InstanceLock> lock = InstanceLock.acquire(dir);
        assertTrue(lock.isPresent());
        lock.get().close();
        assertFalse(Files.exists(InstanceLock.pathIn(dir)));
    }
}
