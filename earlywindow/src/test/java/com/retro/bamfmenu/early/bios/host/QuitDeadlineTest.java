package com.retro.bamfmenu.early.bios.host;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the quit deadline via an injected callback because real halt() kills the test JVM.
 * Pins single-deadline behavior, expiry, and daemon status so the timer never keeps the JVM alive.
 */
class QuitDeadlineTest {

    @Test
    void armsOnce_isADaemon_andTheDeadlineReallyExpires() throws Exception {
        // Park after firing so the thread stays alive for the isDaemon check.
        // Without parking, a 50ms deadline can exit before enumeration and miss a missing setDaemon(true).
        CountDownLatch fired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(QuitDeadline.arm(50, () -> {
                fired.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Test cleanup; nothing to do.
                }
            }));
            assertFalse(QuitDeadline.arm(50, () -> { }));

            assertTrue(fired.await(2, TimeUnit.SECONDS));
            Thread t = byNameWithin("bamfmenu-quit-deadline", 2000);
            assertNotNull(t);
            assertTrue(t.isDaemon());
        } finally {
            release.countDown(); // unpark it either way, so a failed assert cannot strand the thread
        }
    }

    private static Thread byNameWithin(String name, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Thread found = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> name.equals(t.getName())).findFirst().orElse(null);
            if (found != null) {
                return found;
            }
            Thread.sleep(5);
        }
        return null;
    }
}
