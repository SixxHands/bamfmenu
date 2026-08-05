package com.retro.bamfos.updater.engine.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies the OS lock rules: never delete the lock file, never treat the PID text as lock
 * state, and always overwrite the full PID text. */
class InstanceLockTest {

    @TempDir
    Path dir;

    /** Never unlink the lock file on close; deleting it lets two processes lock different inodes under
     * the same path at once. */
    @Test
    void releasingTheLockNeverUnlinksTheFile() throws IOException {
        Path file = InstanceLock.pathIn(dir);

        InstanceLock first = InstanceLock.acquire(dir).orElseThrow();
        first.close();

        assertTrue(Files.exists(file));

        // Reusing the same lock file path means later acquirers lock the same file instead of a new inode.
        InstanceLock second = InstanceLock.acquire(dir).orElseThrow();
        assertTrue(InstanceLock.acquire(dir).isEmpty());
        second.close();
        assertTrue(Files.exists(file));
    }

    /** Ignore any live PID written in the lock file; only the OS lock counts, so recycled PIDs or copied
     * folders cannot block sync. */
    @Test
    void aLivePidWrittenInTheFileIsNotAHolder() throws IOException {
        Path file = InstanceLock.pathIn(dir);
        Files.createDirectories(file.getParent());

        Optional<ProcessHandle> other = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive()).findFirst();
        // Use assume instead of assert: an empty process table is an environment issue, not a bug in InstanceLock.
        assumeTrue(other.isPresent(), "this test needs one live process that is not us to be honest at all");
        Files.writeString(file, Long.toString(other.get().pid()), StandardCharsets.UTF_8);

        Optional<InstanceLock> lock = InstanceLock.acquire(dir);
        assertTrue(lock.isPresent(), String.valueOf(other.get().pid()));
        lock.get().close();

        // Lock file contents don't matter either, so junk or partial writes inside it won't block acquiring the lock.
        for (String note : java.util.List.of("", "   \n", "not a pid at all", "0", "-1")) {
            Files.writeString(file, note, StandardCharsets.UTF_8);
            Optional<InstanceLock> junk = InstanceLock.acquire(dir);
            assertTrue(junk.isPresent(), String.valueOf(note));
            junk.get().close();
        }
    }

    /** Truncate before writing the diagnostic PID note so bytes from a longer previous PID do not linger.
     * Read after release because Windows enforces the lock against our own handles. */
    @Test
    void thePidNoteIsRewrittenWholeAndNotPastedOverALongerLeftover() throws IOException {
        Path file = InstanceLock.pathIn(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "9".repeat(40), StandardCharsets.UTF_8); // longer than any PID on any OS

        InstanceLock lock = InstanceLock.acquire(dir).orElseThrow();
        lock.close();

        assertEquals(Long.toString(ProcessHandle.current().pid()), Files.readString(file, StandardCharsets.UTF_8));
    }
}
