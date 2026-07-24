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
