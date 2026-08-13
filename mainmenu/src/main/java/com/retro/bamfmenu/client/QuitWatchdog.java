package com.retro.bamfmenu.client;

import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/* Kills the JVM if some mod's leftover non-daemon thread keeps it running after quit. We wait until
 * Render thread and Server thread are both gone and the remaining thread set stops changing for the
 * full grace window, so we never interrupt a world save. A stuck Server thread won't trip this on
 * purpose (can't tell it from a slow save, and obliterating a save is worse, god forbid I ruin everything). */
public final class QuitWatchdog {

    static final long GRACE_MS = 10_000L; // 10s of PERFECT stillness with the game threads gone

    /** The game's own lifecycle threads. Alive means teardown (or a save) is still running, and the
     *  clock must not run against it: vanilla names these literally and has for years, and a rename
     *  fails SAFE here, the set merely stops matching and the watchdog waits on stillness alone. */
    static final Set<String> GAME_THREADS = Set.of("Render thread", "Server thread");

    private static final AtomicBoolean armed = new AtomicBoolean();

    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        arm(() -> watch(GRACE_MS));
    }

    /** Returns true if this call armed the watchdog, or false if one is already running. The task body
     *  is injected so tests can pass a no-op instead of letting a live watchdog halt the Gradle worker. */
    static boolean arm(Runnable body) {
        if (!armed.compareAndSet(false, true)) {
            return false; // double-registered or the event re-fired, either way one timer
        }
        Thread t = new Thread(body, "bamfmenu-quit-watchdog");
        // Must be a daemon thread so the watchdog itself never keeps the JVM alive.
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void watch(long graceMs) {
        Stillness quiet = new Stillness(graceMs, System::nanoTime);
        while (true) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                // ignore stray interrupts so they don't trigger or cancel the halt early
            }
            Collection<Thread> all = Thread.getAllStackTraces().keySet();
            if (quiet.wedged(nonDaemonNames(all))) {
                System.err.println(report(graceMs, all));
                Runtime.getRuntime().halt(0); // halt, NOT exit: exit waits on shutdown hooks, and whatever wedged the quit can wedge those too
                return;
            }
        }
    }

    static Set<String> nonDaemonNames(Collection<Thread> threads) {
        Set<String> names = new HashSet<>();
        for (Thread t : threads) {
            if (t.isAlive() && !t.isDaemon()) {
                names.add(t.getName());
            }
        }
        return names;
    }

    // Pure state machine for the stall check. Clock is injected since Runtime.halt kills the Gradle worker.
    static final class Stillness {

        private final long graceNanos;
        private final LongSupplier clock;
        private Set<String> last;
        private long stillSince;

        Stillness(long graceMs, LongSupplier clock) {
            this.graceNanos = graceMs * 1_000_000L;
            this.clock = clock;
            this.stillSince = clock.getAsLong();
        }

        boolean wedged(Set<String> nonDaemonNames) {
            boolean gameAlive = false;
            for (String name : GAME_THREADS) {
                if (nonDaemonNames.contains(name)) {
                    gameAlive = true;
                    break;
                }
            }
            if (gameAlive || !nonDaemonNames.equals(last)) {
                // teardown is still running or threads made progress, so reset the timer
                last = Set.copyOf(nonDaemonNames);
                stillSince = clock.getAsLong();
                return false;
            }
            return clock.getAsLong() - stillSince >= graceNanos;
        }
    }

    /** Log who triggered the forced exit and which non-daemon threads kept the JVM alive. */
    static String report(long graceMs, Collection<Thread> threads) {
        List<String> holders = new ArrayList<>();
        for (Thread t : threads) {
            // daemons cannot hold a JVM open, naming one sends the log reader hunting the wrong thread
            if (t.isAlive() && !t.isDaemon()) {
                holders.add(t.getName());
            }
        }
        Collections.sort(holders);
        return "[bamfmenu-quit-watchdog] JVM still alive with no shutdown progress for " + (graceMs / 1000)
                + "s after quit, halting over non-daemon threads: "
                + (holders.isEmpty() ? "none visible (a native holder?)" : String.join(", ", holders));
    }

    private QuitWatchdog() {
    }
}
