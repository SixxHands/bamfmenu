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

    @Test
    void currentOrSilent_doesNothing() {
        assertEquals(NOTHING, WatchdogBrain.decide("", "v1", true, "", 0, NOW, COOLDOWN));
        assertEquals(NOTHING, WatchdogBrain.decide(null, "v1", true, "", 0, NOW, COOLDOWN));
        assertEquals(NOTHING, WatchdogBrain.decide("v1", "v1", true, "", 0, NOW, COOLDOWN));
    }

    @Test
    void newTag_restarts() {
        assertEquals(RESTART, WatchdogBrain.decide("v2", "v1", true, "", 0, NOW, COOLDOWN));
    }

    @Test
    void operatorVeto_announcesOnly() {
        assertEquals(ANNOUNCE_ONLY, WatchdogBrain.decide("v2", "v1", false, "", 0, NOW, COOLDOWN));
    }

    @Test
    void sameTargetInsideCooldown_holds_theAntiStormRule() {
        // Restarted for v2 an hour ago and still on v1: sync is failing, so don't bounce again yet.
        assertEquals(HOLD_STORM, WatchdogBrain.decide("v2", "v1", true, "v2", NOW - HOUR, NOW, COOLDOWN));
        // cooldown lapsed: one more honest attempt
        assertEquals(RESTART, WatchdogBrain.decide("v2", "v1", true, "v2", NOW - 7 * HOUR, NOW, COOLDOWN));
        // a DIFFERENT new tag resets the guard entirely
        assertEquals(RESTART, WatchdogBrain.decide("v3", "v1", true, "v2", NOW - HOUR, NOW, COOLDOWN));
    }
}
