package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Tests MC-free watchdog helpers (storm-guard save/load, property clamps, token-death rules) that
 *  cannot be tested through the @Mod class directly. */
class WatchdogStateTest {

    @TempDir
    Path dir;

    @Test
    void stormGuardStatePersistsAndFeedsTheGuard() {
        long saved = 1_000_000_000L;
        WatchdogState.save(dir, "v0.2.6", saved, 1);
        Properties st = WatchdogState.load(dir);
        assertEquals("v0.2.6", st.getProperty("last_target"));
        assertEquals(saved, WatchdogState.longProp(st, "last_restart_at"));
        // using actual persisted state: same target inside cooldown should HOLD
        assertEquals(WatchdogBrain.Decision.HOLD_COOLDOWN,
                WatchdogBrain.decide("v0.2.6", "v0.2.4", true, st.getProperty("last_target", ""),
                        WatchdogState.longProp(st, "last_restart_at"), 1,
                        WatchdogBrain.MAX_RESTART_ATTEMPTS, saved + 60_000, 6L * 60 * 60 * 1000));
    }

    /** Exercises attempt-count persistence and tag reset through the exact methods BamfosWatchdog calls. */
    @Test
    void attemptCountPersists_andTurnsTheLapsedCooldownIntoAPermanentHold() {
        long saved = 1_000_000_000L;
        long wayLater = saved + 7L * 60 * 60 * 1000;
        WatchdogState.recordRestart(dir, "v0.2.6", saved, WatchdogBrain.MAX_RESTART_ATTEMPTS - 1);
        Properties st = WatchdogState.load(dir);
        assertEquals(WatchdogBrain.MAX_RESTART_ATTEMPTS, WatchdogState.attemptsFor(st, "v0.2.6"));
        assertEquals(WatchdogBrain.Decision.HOLD_GIVEUP,
                WatchdogBrain.decide("v0.2.6", "v0.2.4", true, st.getProperty("last_target", ""),
                        WatchdogState.longProp(st, "last_restart_at"),
                        WatchdogState.attemptsFor(st, "v0.2.6"),
                        WatchdogBrain.MAX_RESTART_ATTEMPTS, wayLater, 6L * 60 * 60 * 1000));
    }

    /** A new release tag resets the attempt count so servers aren't stuck ignoring a hotfix. */
    @Test
    void attemptsFor_resetOnANewTag_soAFreshReleaseAlwaysGetsItsRestart() {
        WatchdogState.save(dir, "v0.2.6", 1_000_000_000L, WatchdogBrain.MAX_RESTART_ATTEMPTS);
        Properties st = WatchdogState.load(dir);
        assertEquals(WatchdogBrain.MAX_RESTART_ATTEMPTS, WatchdogState.attemptsFor(st, "v0.2.6"));
        assertEquals(0, WatchdogState.attemptsFor(st, "v0.2.7"));
        assertEquals(WatchdogBrain.Decision.RESTART,
                WatchdogBrain.decide("v0.2.7", "v0.2.4", true, st.getProperty("last_target", ""),
                        WatchdogState.longProp(st, "last_restart_at"),
                        WatchdogState.attemptsFor(st, "v0.2.7"),
                        WatchdogBrain.MAX_RESTART_ATTEMPTS, 1_000_000_001L, 6L * 60 * 60 * 1000));
    }

    @Test
    void absentStateReadsBlank() {
        Properties st = WatchdogState.load(dir);
        assertEquals("", st.getProperty("last_target", ""));
        assertEquals(0L, WatchdogState.longProp(st, "last_restart_at"));
        assertEquals(0, WatchdogState.intProp(st, "last_target_attempts", 0));
    }

    /** Verifies pollSeconds defaults when absent and clamps out-of-range edits so scheduleAtFixedRate
     *  never throws on a period <= 0. */
    @Test
    void pollSeconds_carriesItsOwnBounds_soTheSchedulerNeverSeesANonPositivePeriod() {
        Properties p = new Properties();
        assertEquals(300, WatchdogState.pollSeconds(p));
        p.setProperty("watchdog_poll_seconds", "0");
        assertEquals(30, WatchdogState.pollSeconds(p));
        p.setProperty("watchdog_poll_seconds", "-5");
        assertEquals(30, WatchdogState.pollSeconds(p));
        p.setProperty("watchdog_poll_seconds", "999999999");
        assertEquals(86400, WatchdogState.pollSeconds(p));
        p.setProperty("watchdog_poll_seconds", "nonsense");
        assertEquals(300, WatchdogState.pollSeconds(p));
        p.setProperty("watchdog_poll_seconds", "600");
        assertEquals(600, WatchdogState.pollSeconds(p));
    }

    /** Verifies countdown clamping so negatives don't halt immediately and huge values don't stall polling forever. */
    @Test
    void countdownSeconds_carriesItsOwnBounds_bothWays() {
        Properties p = new Properties();
        assertEquals(60, WatchdogState.countdownSeconds(p));
        p.setProperty("watchdog_countdown_seconds", "-30");
        assertEquals(0, WatchdogState.countdownSeconds(p));
        p.setProperty("watchdog_countdown_seconds", "999999");
        assertEquals(3600, WatchdogState.countdownSeconds(p));
        p.setProperty("watchdog_countdown_seconds", "0");
        assertEquals(0, WatchdogState.countdownSeconds(p));
    }

    /**
     * Only treat auth errors as a bad token when a token was actually sent, so anonymous
     * polls don't warn about a token that isn't configured.
     */
    @Test
    void anonymousRefusalIsNeverTokenDeath() {
        assertFalse(ReleasePoll.tokenDeadFrom(403, false, false));
        assertFalse(ReleasePoll.tokenDeadFrom(401, false, false));
        assertTrue(ReleasePoll.tokenDeadFrom(401, true, false));
        assertTrue(ReleasePoll.tokenDeadFrom(403, true, false));
        assertFalse(ReleasePoll.tokenDeadFrom(403, true, true));
    }

    @Test
    void preBootSummary_echoesFresh_rejectsStale_and_absent() throws IOException {
        long now = 2_000_000_000_000L;
        Path bam = dir.resolve(".bamfos-updater");
        Files.createDirectories(bam);
        Files.writeString(bam.resolve("last-sync.json"), "{\"at\":" + (now - 5_000)
                + ",\"tag\":\"vT\",\"result\":\"ok\",\"summary\":\"updated ? -> vT (3 files)\"}");
        assertEquals("updated ? -> vT (3 files)",
                WatchdogState.preBootSummary(dir, now, 60L * 60 * 1000).orElse(""));
        // a summary older than the freshness window means the shim was skipped this boot -> don't echo
        assertTrue(WatchdogState.preBootSummary(dir, now + 2L * 60 * 60 * 1000, 60L * 60 * 1000).isEmpty());
        // no file at all -> empty, never an exception
        assertTrue(WatchdogState.preBootSummary(dir.resolve("nope"), now, 60L * 60 * 1000).isEmpty());
    }
}
