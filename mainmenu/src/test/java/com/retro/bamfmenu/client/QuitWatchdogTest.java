package com.retro.bamfmenu.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Verifies QuitWatchdog runs as a daemon, arms once, and never halts during an active world save (clock injected into Stillness).
class QuitWatchdogTest {

    @Test
    void armsExactlyOnce_andTheTimerThreadIsADaemon() throws Exception {
        // no-op body so a live watchdog doesn't think this Gradle worker is stuck and halt the test suite
        CountDownLatch parked = new CountDownLatch(1);
        Runnable inert = () -> {
            try {
                parked.await();
            } catch (InterruptedException ignored) {
            }
        };
        try {
            assertTrue(QuitWatchdog.arm(inert));
            Thread dog = byNameWithin("bamfmenu-quit-watchdog", 2000);
            assertNotNull(dog);
            assertTrue(dog.isDaemon());
            // a second registration (or the event firing twice) must not stack a second timer
            assertFalse(QuitWatchdog.arm(inert));
            assertEquals(1, countByName("bamfmenu-quit-watchdog"));
        } finally {
            parked.countDown();
        }
    }

    // World save on Server thread keeps resetting the timer no matter how long it takes.
    @Test
    void aLiveGameThreadResetsTheClock_howeverLongTheSaveTakes() {
        long[] now = {0};
        QuitWatchdog.Stillness quiet = new QuitWatchdog.Stillness(10_000L, () -> now[0]);
        Set<String> saving = Set.of("Server thread", "modloader-worker-1");
        for (int minute = 1; minute <= 5; minute++) {
            now[0] = minute * 60_000L * 1_000_000L;
            assertFalse(quiet.wedged(saving), String.valueOf(minute));
        }
        // save done, Server thread gone, and the clock starts NOW, not at the quit request
        now[0] += 1_000L * 1_000_000L;
        assertFalse(quiet.wedged(Set.of("modloader-worker-1")));
        now[0] += 9_999L * 1_000_000L;
        assertFalse(quiet.wedged(Set.of("modloader-worker-1")));
        now[0] += 1L * 1_000_000L;
        assertTrue(quiet.wedged(Set.of("modloader-worker-1")));
    }

    /** Every change to the survivor set is progress, and progress restarts the whole window. */
    @Test
    void aChangingThreadSetIsProgress_notAWedge() {
        long[] now = {0};
        QuitWatchdog.Stillness quiet = new QuitWatchdog.Stillness(10_000L, () -> now[0]);
        assertFalse(quiet.wedged(Set.of("worker-a", "worker-b")));
        now[0] += 9_000L * 1_000_000L;
        assertFalse(quiet.wedged(Set.of("worker-a")));
        now[0] += 9_000L * 1_000_000L;
        assertFalse(quiet.wedged(Set.of("worker-a")));
        now[0] += 1_000L * 1_000_000L;
        assertTrue(quiet.wedged(Set.of("worker-a")));
    }

    // Leave a live server alone! Cant help it if yo thinkpad slow af
    @Test
    void aWedgedServerThreadIsNeverHalted_theLeakIsTheLesserEvil() {
        long[] now = {0};
        QuitWatchdog.Stillness quiet = new QuitWatchdog.Stillness(10_000L, () -> now[0]);
        Set<String> stuck = Set.of("Server thread");
        for (int hour = 1; hour <= 3; hour++) {
            now[0] = hour * 3_600_000L * 1_000_000L;
            assertFalse(quiet.wedged(stuck));
        }
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

            assertTrue(QuitWatchdog.nonDaemonNames(List.of(holder, bystander, neverStarted))
                    .equals(Set.of("test-quit-holder")));
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
