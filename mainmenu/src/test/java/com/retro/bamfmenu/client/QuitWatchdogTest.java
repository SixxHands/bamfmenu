package com.retro.bamfmenu.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watchdog's whole safety story is what it CANNOT do: it must be a daemon (a non-daemon watchdog
 * IS the leak it hunts) and it must arm exactly once however many times shutdown fires. The halt
 * itself is untestable in-process (halting the test JVM reads as a crashed worker), so these pin the
 * arming rules and the abandonment line instead.
 */
class QuitWatchdogTest {

    private static final long NEVER_FIRES_MS = 5L * 60L * 1000L; // far past the suite's lifetime, the timer must never fire inside a test JVM

    @Test
    void armsExactlyOnce_andTheTimerThreadIsADaemon() throws Exception {
        assertTrue(QuitWatchdog.arm(NEVER_FIRES_MS));
        Thread dog = byNameWithin("bamfmenu-quit-watchdog", 2000);
        assertNotNull(dog);
        assertTrue(dog.isDaemon());
        // a second registration (or the event firing twice) must not stack a second timer
        assertFalse(QuitWatchdog.arm(NEVER_FIRES_MS));
        assertEquals(1, countByName("bamfmenu-quit-watchdog"));
    }

    @Test
    void theAbandonmentLine_namesItselfAndOnlyTheLiveNonDaemonThreads() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        Thread holder = parked(hold, "test-quit-holder", false);
        Thread bystander = parked(hold, "test-quit-bystander", true);
        Thread neverStarted = new Thread(() -> { }, "test-quit-dead");
        holder.start();
        bystander.start();
        try {
            String line = QuitWatchdog.report(10_000L, List.of(holder, bystander, neverStarted));
            assertTrue(line.contains("bamfmenu-quit-watchdog"));
            assertTrue(line.contains("test-quit-holder"));
            assertFalse(line.contains("test-quit-bystander"));
            assertFalse(line.contains("test-quit-dead"));
            assertFalse(line.contains("\n"));
        } finally {
            hold.countDown();
            holder.join(2000);
            bystander.join(2000);
        }
    }

    @Test
    void noVisibleHolder_stillProducesOneSelfNamedLine() {
        String line = QuitWatchdog.report(10_000L, List.of());
        assertTrue(line.contains("bamfmenu-quit-watchdog"));
        assertFalse(line.contains("\n"));
    }

    private static Thread parked(CountDownLatch hold, String name, boolean daemon) {
        Thread t = new Thread(() -> {
            try {
                hold.await();
            } catch (InterruptedException ignored) {
            }
        }, name);
        t.setDaemon(daemon);
        return t;
    }

    /** New threads can take a moment to appear in thread enumeration, so poll instead of checking immediately. */
    private static Thread byNameWithin(String name, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Thread found = byName(name);
            if (found != null) {
                return found;
            }
            Thread.sleep(10);
        }
        return byName(name);
    }

    private static Thread byName(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static int countByName(String name) {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(t -> name.equals(t.getName())).count();
    }
}
