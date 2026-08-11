package com.retro.bamfmenu.early.bios.overlay;

import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the boot reboot overlay with an injected clock and counted quit callback.
 * Asserts on compose() output directly so tests run without a GL context.
 */
class RebootOverlayTest {

    private static final int KEY_ENTER = 257;
    private static final long SECOND = 1_000_000_000L;

    /** Staged bytes for next boot raise the reboot overlay and pause hand-off until restart. */
    @Test
    void aStagedUpdateArmsTheBoxAndHoldsTheHandoff() {
        Posed p = armed();

        assertTrue(p.overlay.active());
        // rejoined, because RepairFrames wraps the body at MODAL_WIDTH and the approved sentence spans two rows
        String box = String.join(" ", p.overlay.compose()).replaceAll("\\s+", " ").trim();
        assertTrue(box.contains("Restart Required"), box);
        assertTrue(box.contains("A restart is needed to apply the update. Your game will now close. "
                + "Press any key to continue."), String.valueOf(box));
        assertTrue(box.contains("[ Press any key to close the game. ]"), String.valueOf(box));
        assertTrue(p.overlay.hints().startsWith("Closing in 10s"), p.overlay.hints());
        assertEquals(0, p.exits);
    }

    /** When nothing is staged, startup should proceed straight to Minecraft without showing the overlay. */
    @Test
    void aConvergedBootNeverSeesTheBox() {
        Posed p = new Posed();
        p.overlay.pending(false);
        p.overlay.handingOff();

        for (int frame = 0; frame < 50; frame++) {
            p.now += 50_000_000L; // 50ms frame step matching the POST loop.
            p.overlay.step(post());
        }
        assertFalse(p.overlay.active());
        assertEquals(0, p.overlay.compose().length);
        assertEquals("", p.overlay.hints());
        assertEquals(0, p.exits);
    }

    /** With no input, the 10-second countdown expires and closes the game so staged updates can apply. */
    @Test
    void theCountdownRunsOutAndClosesTheGame() {
        Posed p = armed();

        p.overlay.step(post());
        assertEquals(0, p.exits);
        assertTrue(p.overlay.hints().startsWith("Closing in 10s"), p.overlay.hints());

        p.now += 9 * SECOND;
        p.overlay.step(post());
        assertEquals(0, p.exits);
        assertTrue(p.overlay.hints().startsWith("Closing in 1s"), p.overlay.hints());

        p.now += SECOND;
        p.overlay.step(post());
        assertEquals(1, p.exits);
    }

    /** Any key dismisses the overlay once its initial grace period has elapsed. */
    @Test
    void aKeyPressClosesTheGameOnceTheBoxHasBeenSeen() {
        Posed p = armed();

        p.keys.offer(KEY_ENTER);
        p.overlay.step(post());
        assertEquals(0, p.exits);

        p.now += 2 * SECOND; // Past the 1.5s grace period, so this keypress should be accepted.
        p.keys.offer(KEY_ENTER);
        p.overlay.step(post());
        assertEquals(1, p.exits);
    }

    /** On overlay failure, drop the prompt and release hand-off rather than halting without showing it. */
    @Test
    void aThrowInTheSurfaceStillLetsTheBootThrough() {
        Queue<Integer> keys = new ConcurrentLinkedQueue<>();
        int[] exits = {0};
        RebootOverlay overlay = new RebootOverlay(keys, () -> {
            throw new IllegalStateException("posed clock fault");
        }, () -> exits[0]++);
        overlay.pending(true);
        overlay.handingOff();

        overlay.step(post());
        assertFalse(overlay.active());
        assertEquals("", overlay.hints());
        assertEquals(0, exits[0]);

        for (int frame = 0; frame < 20; frame++) {
            overlay.step(post());
        }
        assertEquals(0, exits[0]);
        assertFalse(overlay.active());
    }

    /**
     * Countdown waits until the overlay lines exist, then gives up rather than closing blind,
     * since exiting with nothing drawn looks like a crash.
     */
    @Test
    void aBoxThatNeverDrewNeverClosesTheGame() {
        Posed p = new Posed();
        p.overlay.pending(true);
        p.overlay.handingOff();
        p.overlay.step(post()); // Arms the overlay without ever rendering its lines.

        for (int frame = 0; frame < 10; frame++) {
            p.now += 2 * SECOND;
            p.overlay.step(post());
        }
        assertEquals(0, p.exits);
        assertFalse(p.overlay.active());
    }

    /** Wait for POST when the player is in BIOS SETUP, a game, or the volume menu so their session stays open. */
    @Test
    void itWaitsRatherThanEndingASessionSomebodyIsStillIn() {
        Posed setup = new Posed();
        setup.overlay.pending(true);
        setup.overlay.handingOff();
        setup.overlay.step(quiet(Mode.SETUP, false));
        assertFalse(setup.overlay.active());

        Posed volume = new Posed();
        volume.overlay.pending(true);
        volume.overlay.handingOff();
        volume.overlay.step(quiet(Mode.POST, true));
        assertFalse(volume.overlay.active());

        volume.overlay.step(post()); // Menu closes; the next frame arms the overlay.
        assertTrue(volume.overlay.active());
    }

    /**
     * The wait loop runs on the main thread during Minecraft startup, so it tracks a local timeout
     * instead of relying on the render thread. If rendering hangs, startup continues rather than deadlocking.
     */
    @Test
    void theHoldReleasesItselfIfTheCountdownNeverFinishes() {
        Posed p = armed();
        assertTrue(p.overlay.active());

        p.now += 16 * SECOND; // Simulate a stuck render thread: no frames drawn, no input, and no close.
        assertFalse(p.overlay.active());
        p.overlay.step(post());
        assertEquals(0, p.exits);
    }

    /**
     * System.nanoTime() can be negative, and RepairSession used to check closeAt > 0 to detect an active
     * dialog. Verify the countdown still works and renders properly when nanoTime starts negative.
     */
    @Test
    void aClockThatStartsNegativeStillGetsTheWholeFarewell() {
        Posed p = new Posed();
        p.now = -600 * SECOND; // Start 10 minutes below zero to test negative nanoTime values.
        p.overlay.pending(true);
        p.overlay.handingOff();
        p.overlay.step(post()); // arms
        p.overlay.compose();
        p.overlay.step(post()); // First frame that can draw the overlay starts the 10-second countdown.

        assertTrue(p.overlay.hints().startsWith("Closing in 10s"), p.overlay.hints());
        p.now += 10 * SECOND;
        p.overlay.step(post());
        assertEquals(1, p.exits);
    }

    /**
     * Tests main-thread ordering where handingOff is set and polled before the render thread calls arm().
     * Skips step() so active is still false when the wait check runs.
     */
    @Test
    void theHoldEngagesBeforeAnyFrameHasRun() {
        Posed p = new Posed();
        p.overlay.pending(true);
        p.overlay.handingOff();

        assertFalse(p.overlay.active());
        assertTrue(p.overlay.holds());
    }

    /** Bounds hand-off wait with a grace period from handingOff if the render thread never arms the overlay. */
    @Test
    void aBootThatNeverArmsIsReleasedByTheGraceNotTheCountdown() {
        Posed p = new Posed();
        p.overlay.pending(true);
        p.overlay.handingOff();
        assertTrue(p.overlay.holds());

        p.now += 3 * SECOND; // past the 2s arm grace, nowhere near the 15s give-up

        assertFalse(p.overlay.holds());
    }

    /* One overlay with its clock and its exit posed, so a test can spend ten seconds in a line and count the
       close the player would have got. Everything else runs the shipped path: the countdown, the key grace
       and every string live in RepairSession. */
    private static final class Posed {
        final Queue<Integer> keys = new ConcurrentLinkedQueue<>();
        final RebootOverlay overlay;
        long now;
        int exits;

        Posed() {
            overlay = new RebootOverlay(keys, () -> now, () -> exits++);
        }
    }

    /* Overlay one frame into farewell: pending mirrored, hand-off waiting, lines composed as renderFrame does. */
    private static Posed armed() {
        Posed p = new Posed();
        p.overlay.pending(true);
        p.overlay.handingOff();
        p.overlay.step(post());
        p.overlay.compose();
        return p;
    }

    private static BiosContext post() {
        return quiet(Mode.POST, false);
    }

    /* Use a Proxy returning default values except for the mode and overlay flags
       so we don't have to write dozens of unused BiosContext stubs. */
    private static BiosContext quiet(Mode mode, boolean menuOpen) {
        return (BiosContext) Proxy.newProxyInstance(BiosContext.class.getClassLoader(),
                new Class<?>[]{BiosContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("mode")) {
                        return mode;
                    }
                    if (method.getName().equals("volumeMenuOpen") || method.getName().equals("pauseMenuOpen")) {
                        return menuOpen;
                    }
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) {
                        return false;
                    }
                    if (r == int.class) {
                        return 0;
                    }
                    if (r == long.class) {
                        return 0L;
                    }
                    if (r == double.class) {
                        return 0d;
                    }
                    if (r == String.class) {
                        return "";
                    }
                    return null;
                });
    }
}
