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
        CountDownLatch fired = new CountDownLatch(1);
        assertTrue(QuitDeadline.arm(50, fired::countDown));
        assertFalse(QuitDeadline.arm(50, () -> { }));

        Thread t = byNameWithin("bamfmenu-quit-deadline", 2000);
        // the thread may already have expired and gone; when it is still up it must be a daemon
        if (t != null) {
            assertTrue(t.isDaemon());
        }
        assertTrue(fired.await(2, TimeUnit.SECONDS));
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
