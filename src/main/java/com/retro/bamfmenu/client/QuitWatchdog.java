package com.retro.bamfmenu.client;

import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dev client's JVM has been seen surviving a normal quit (the official pack exits clean, the
 * holder is still under investigation), leaving a windowless java process squatting RAM. Game
 * shutdown arms a 10s timer that names whatever non-daemon threads are still holding the JVM and
 * halts over them. wanted even before the holder hunt lands.
 */
public final class QuitWatchdog {

    static final long GRACE_MS = 10_000L; // 10s, an honest shutdown finishes well inside this

    private static final AtomicBoolean armed = new AtomicBoolean();

    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        arm(GRACE_MS);
    }

    /** True when this call armed the timer; false means it was already armed. */
    static boolean arm(long graceMs) {
        if (!armed.compareAndSet(false, true)) {
            return false; // double-registered or the event re-fired, either way one timer
        }
        Thread t = new Thread(() -> watch(graceMs), "bamfmenu-quit-watchdog");
        // Daemon is the whole trick: the JVM never waits on a daemon thread, so the watchdog can never be the holder it exists to kill.
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void watch(long graceMs) {
        long deadline = System.nanoTime() + graceMs * 1_000_000L;
        long left;
        while ((left = deadline - System.nanoTime()) > 0) {
            try {
                Thread.sleep(left / 1_000_000L + 1);
            } catch (InterruptedException ignored) {
                // a stray interrupt must neither fire the halt early nor disarm it, the deadline alone decides
            }
        }
        System.err.println(report(graceMs, Thread.getAllStackTraces().keySet()));
        Runtime.getRuntime().halt(0); // halt, NOT exit: exit waits on shutdown hooks, and whatever wedged the quit can wedge those too
    }

    /** The one line we leave behind: who we are, and who was still holding the JVM open. */
    static String report(long graceMs, Collection<Thread> threads) {
        List<String> holders = new ArrayList<>();
        for (Thread t : threads) {
            // daemons cannot hold a JVM open, naming one sends the log reader hunting the wrong thread
            if (t.isAlive() && !t.isDaemon()) {
                holders.add(t.getName());
            }
        }
        Collections.sort(holders);
        return "[bamfmenu-quit-watchdog] JVM still alive " + (graceMs / 1000)
                + "s after quit, halting over non-daemon threads: "
                + (holders.isEmpty() ? "none visible (a native holder?)" : String.join(", ", holders));
    }

    private QuitWatchdog() {
    }
}
