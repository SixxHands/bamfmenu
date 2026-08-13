package com.retro.bamfmenu.early.bios.host;

import java.util.concurrent.atomic.AtomicBoolean;

/* Fallback deadline for BIOS-requested quits. Normally we let boot finish so Minecraft.stop() saves and
 * exits cleanly, but if mod loading freezes after a factory reset we can't wait forever. */
final class QuitDeadline {

    /** Five minutes: past every honest mod load anyone has measured on this pack, nowhere near forever. */
    static final long DEADLINE_MS = 5L * 60L * 1000L;

    private static final AtomicBoolean armed = new AtomicBoolean();

    /** Arms once; every later call is a no-op (both BIOS quit surfaces funnel through one request). */
    static void arm() {
        arm(DEADLINE_MS, () -> {
            System.err.println("[bamfmenu] the game was asked to close " + (DEADLINE_MS / 1000)
                    + "s ago and the loader never finished, halting a boot that is going nowhere");
            Runtime.getRuntime().halt(0);
        });
    }

    /** True when this call armed the timer. The body rides in so a test never arms a real halt. */
    static boolean arm(long deadlineMs, Runnable expire) {
        if (!armed.compareAndSet(false, true)) {
            return false;
        }
        Thread t = new Thread(() -> {
            long wake = System.nanoTime() + deadlineMs * 1_000_000L;
            long left;
            while ((left = wake - System.nanoTime()) > 0) {
                try {
                    Thread.sleep(left / 1_000_000L + 1);
                } catch (InterruptedException ignored) {
                    // a stray interrupt neither fires the deadline early nor disarms it
                }
            }
            expire.run();
        }, "bamfmenu-quit-deadline");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private QuitDeadline() {
    }
}
