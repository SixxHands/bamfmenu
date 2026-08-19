package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Injects a Runnable exit hook so tests can observe it without killing the test runner JVM. */
class ShutdownBackstopTest {

    @TempDir
    Path dir;

    /** A hung shutdown must still exit once the deadline expires. */
    @Test
    void aShutdownThatNeverFinishesGetsTheExitTaken() throws InterruptedException {
        AtomicBoolean exited = new AtomicBoolean();
        CountDownLatch fired = new CountDownLatch(1);
        Thread t = ShutdownBackstop.arm(dir, 1, () -> {
            exited.set(true);
            fired.countDown();
        }, msg -> { });
        assertTrue(fired.await(20, TimeUnit.SECONDS));
        assertTrue(exited.get());
        t.join(5000);
    }

    /** A clean shutdown must cancel the backstop so normal restarts aren't force-killed. */
    @Test
    void aCleanShutdownCancelsItBeforeItCanFire() throws InterruptedException {
        AtomicBoolean exited = new AtomicBoolean();
        Thread t = ShutdownBackstop.arm(dir, 3600, () -> exited.set(true), msg -> { });
        t.interrupt(); // stands in for the JVM ending, which kills a daemon mid-sleep the same way
        t.join(5000);
        assertFalse(exited.get());
    }

    /** The backstop thread must be a daemon so it doesn't keep the JVM alive by itself. */
    @Test
    void itIsADaemonSoItCannotBeTheThingKeepingUsAlive() {
        Thread t = ShutdownBackstop.arm(dir, 3600, () -> { }, msg -> { });
        assertTrue(t.isDaemon());
        t.interrupt();
    }

    /** Timeout 0 disables the backstop completely without spawning a sleeping thread. */
    @Test
    void zeroGraceArmsNothing() {
        assertNull(ShutdownBackstop.arm(dir, 0, () -> { }, msg -> { }));
        assertNull(ShutdownBackstop.arm(dir, -5, () -> { }, msg -> { }));
    }

    /** Thread dump must be written to disk with thread names before forcing exit. */
    @Test
    void theDumpNamesEveryThreadAndItsState() {
        Map<Thread, StackTraceElement[]> fake = new HashMap<>();
        fake.put(Thread.currentThread(), new StackTraceElement[]{
                new StackTraceElement("net.minecraft.server.MinecraftServer", "stopServer", "MinecraftServer.java", 1)
        });
        String out = ShutdownBackstop.report(fake, 1L);
        assertTrue(out.contains("stopServer"));
        assertTrue(out.contains(Thread.currentThread().getName()));

        String where = ShutdownBackstop.write(dir, out);
        assertTrue(where.contains(ShutdownBackstop.PREFIX));
        assertTrue(Files.isRegularFile(Path.of(where)));
    }

    /** Failing to write the thread dump must not throw, or the forced exit won't run. */
    @Test
    void anUnwritableDumpIsAnAnswerNotAThrow() throws Exception {
        // a plain FILE where the folder has to go, because that is unwritable on every os
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");
        String where = ShutdownBackstop.write(blocker, "y");
        assertTrue(where.startsWith("nowhere"), where);
    }

    /** 0 stays disabled; low positive values clamp to the minimum so normal saves don't trip it. */
    @Test
    void theGraceClampKeepsTheOffSwitchButRefusesASuicidalFloor() {
        java.util.Properties p = new java.util.Properties();
        assertEquals(180, WatchdogState.exitGraceSeconds(p));
        p.setProperty("watchdog_exit_grace_seconds", "0");
        assertEquals(0, WatchdogState.exitGraceSeconds(p));
        p.setProperty("watchdog_exit_grace_seconds", "5");
        assertEquals(30, WatchdogState.exitGraceSeconds(p));
        p.setProperty("watchdog_exit_grace_seconds", "99999");
        assertEquals(3600, WatchdogState.exitGraceSeconds(p));
        p.setProperty("watchdog_exit_grace_seconds", "not a number");
        assertEquals(180, WatchdogState.exitGraceSeconds(p));
    }
}
