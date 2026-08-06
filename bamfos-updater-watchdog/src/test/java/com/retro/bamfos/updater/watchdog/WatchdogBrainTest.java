package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;

import static com.retro.bamfos.updater.watchdog.WatchdogBrain.Decision.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the restart decision table, including the restart-storm guard that stops a server
 * from rebooting in a loop if updates fail to apply.
 */
class WatchdogBrainTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long COOLDOWN = 6 * HOUR;
    private static final long NOW = 1_000_000_000L;
    private static final int MAX = WatchdogBrain.MAX_RESTART_ATTEMPTS;

    @Test
    void currentOrSilent_doesNothing() {
        assertEquals(NOTHING, WatchdogBrain.decide("", "v1", "", true, "", 0, 0, MAX, NOW, COOLDOWN));
        assertEquals(NOTHING, WatchdogBrain.decide(null, "v1", "", true, "", 0, 0, MAX, NOW, COOLDOWN));
        assertEquals(NOTHING, WatchdogBrain.decide("v1", "v1", "", true, "", 0, 0, MAX, NOW, COOLDOWN));
    }

    @Test
    void newTag_restarts() {
        assertEquals(RESTART, WatchdogBrain.decide("v2", "v1", "", true, "", 0, 0, MAX, NOW, COOLDOWN));
    }

    @Test
    void operatorVeto_announcesOnly() {
        assertEquals(ANNOUNCE_ONLY, WatchdogBrain.decide("v2", "v1", "", false, "", 0, 0, MAX, NOW, COOLDOWN));
    }

    @Test
    void sameTargetInsideCooldown_holds_theAntiStormRule() {
        // Restarted for v2 an hour ago and still on v1: sync is failing, so don't bounce again yet.
        assertEquals(HOLD_COOLDOWN, WatchdogBrain.decide("v2", "v1", "", true, "v2", NOW - HOUR, 1, MAX, NOW, COOLDOWN));
        // cooldown expired and attempts remaining: allow another restart
        assertEquals(RESTART, WatchdogBrain.decide("v2", "v1", "", true, "v2", NOW - 7 * HOUR, 1, MAX, NOW, COOLDOWN));
        // a DIFFERENT new tag resets the guard entirely
        assertEquals(RESTART, WatchdogBrain.decide("v3", "v1", "", true, "v2", NOW - HOUR, 0, MAX, NOW, COOLDOWN));
    }

    /** The two keeps print different warnings, so which one comes back IS the contract: a spent count is
     *  terminal even with the cooldown still running, and answering COOLDOWN there would have the log
     *  promise a retry that never comes. One HOLD constant let exactly that text ship. */
    @Test
    void aSpentCountIsTerminalEvenInsideTheCooldown() {
        assertEquals(HOLD_COOLDOWN, WatchdogBrain.decide("v2", "v1", "", true, "v2", NOW - HOUR, 1, MAX, NOW, COOLDOWN));
        assertEquals(HOLD_GIVEUP, WatchdogBrain.decide("v2", "v1", "", true, "v2", NOW - HOUR, MAX, MAX, NOW, COOLDOWN));
    }

    /**
     * Once max restart attempts are used up for a tag, stop restarting permanently
     * until a newer release tag is published.
     */
    @Test
    void stormGuard_stopsAfterTheRetry_neverRestartsForever() {
        // cooldown is already expired in these cases, so only the attempt count matters
        assertEquals(RESTART, WatchdogBrain.decide("v2", "v1", "", true, "v2", NOW - 7 * HOUR, 1, MAX, NOW, COOLDOWN));
        assertEquals(HOLD_GIVEUP, WatchdogBrain.decide("v2", "v1", "", true, "v2", NOW - 7 * HOUR, 2, MAX, NOW, COOLDOWN));
        assertEquals(HOLD_GIVEUP, WatchdogBrain.decide("v2", "v1", "", true, "v2", NOW - 700 * HOUR, 9, MAX, NOW, COOLDOWN));
        // A new tag starts with a clean attempt count.
        assertEquals(RESTART, WatchdogBrain.decide("v3", "v1", "", true, "v2", NOW - 7 * HOUR, 9, MAX, NOW, COOLDOWN));
    }

    /** Verifies tag= pins suppress restarts so a pinned rollback server does not bounce and trip the storm guard. */
    @Test
    void aPinnedBoxNeverRestartsForAReleaseThePinHoldsBack() {
        assertEquals(HOLD_PINNED, WatchdogBrain.decide("v3", "v2", "v2", true, "", 0, 0, MAX, NOW, COOLDOWN));
        assertEquals(HOLD_PINNED, WatchdogBrain.decide("v3", "v2", "v2", false, "", 0, 0, MAX, NOW, COOLDOWN));
        // pinning to latest doesn't block restarts
        assertEquals(RESTART, WatchdogBrain.decide("v3", "v2", "v3", true, "", 0, 0, MAX, NOW, COOLDOWN));
        // already on latest stays NOTHING; rolling back to a pin happens on the next sync, not via forced restart
        assertEquals(NOTHING, WatchdogBrain.decide("v3", "v3", "v2", true, "", 0, 0, MAX, NOW, COOLDOWN));
        // no pin (or null) behaves the same as normal unpinned updates
        assertEquals(RESTART, WatchdogBrain.decide("v3", "v2", null, true, "", 0, 0, MAX, NOW, COOLDOWN));
    }
}
