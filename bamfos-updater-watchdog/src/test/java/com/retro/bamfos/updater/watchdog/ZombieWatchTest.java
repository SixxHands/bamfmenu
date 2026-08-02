package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;

import static com.retro.bamfos.updater.watchdog.ZombieWatch.Verdict.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests ZombieWatch with a fake clock across lag spikes, saves, shutdowns, crashes, and actual hangs. */
class ZombieWatchTest {

    private static final long STALL = ZombieWatch.STALL_MS;

    /**
     * The first sample only sets the baseline; without the negative lastTick guard, starting
     * the watch at t=400s would immediately report a 400s hang.
     */
    @Test
    void theFirstLookNeverAccuses() {
        ZombieWatch w = new ZombieWatch(STALL);
        assertEquals(NOTHING, w.sample(0, true, 400_000));
        assertEquals(NOTHING, w.sample(0, true, 400_001));
    }

    /**
     * Once ticks stop past the threshold, fire one hang warning and stay quiet on later
     * 15s samples so we don't spam the log.
     */
    @Test
    void parkedPastTheWindow_reportsOnce_thenStaysQuiet() {
        ZombieWatch w = new ZombieWatch(STALL);
        assertEquals(NOTHING, w.sample(500, true, 0));
        assertEquals(NOTHING, w.sample(500, true, STALL - 1));
        assertEquals(WENT_ZOMBIE, w.sample(500, true, STALL));
        assertEquals(STALL, w.stalledMs());
        assertEquals(NOTHING, w.sample(500, true, STALL + 15_000));
        assertEquals(NOTHING, w.sample(500, true, STALL + 600_000));
    }

    /** If ticks resume after a hang warning, log one recovery notice so operators know it came back. */
    @Test
    void tickingAgain_retractsItOnce() {
        ZombieWatch w = new ZombieWatch(STALL);
        w.sample(500, true, 0);
        assertEquals(WENT_ZOMBIE, w.sample(500, true, STALL));
        assertEquals(RECOVERED, w.sample(501, true, STALL + 25_000));
        assertEquals(STALL + 25_000, w.stalledMs());
        assertEquals(NOTHING, w.sample(502, true, STALL + 25_050));
    }

    /** Verifies shutdowns (isRunning() false) never trigger a zombie alarm even when the shutdown save
     *  outlasts the hang window. */
    @Test
    void aStoppingServerIsNeverAZombie() {
        ZombieWatch stopping = new ZombieWatch(STALL);
        assertEquals(NOTHING, stopping.sample(900, false, 0));
        assertEquals(NOTHING, stopping.sample(900, false, STALL));
        assertEquals(NOTHING, stopping.sample(900, false, 10L * 60 * 1000));

        ZombieWatch hung = new ZombieWatch(STALL);
        assertEquals(NOTHING, hung.sample(900, true, 0));
        assertEquals(WENT_ZOMBIE, hung.sample(900, true, STALL));
    }

    /**
     * Verifies crash teardown suppresses zombie alarms via isStopped(): on a tick exception, isRunning()
     * stays true and ServerStoppingEvent never fires while stopServer() drains chunks and saves worlds.
     */
    @Test
    void aCrashedServerSavingItsWorldIsNeverAZombie() {
        ZombieWatch w = new ZombieWatch(STALL);
        assertEquals(NOTHING, w.sample(1500, ZombieWatch.watchable(true, true, true), 0));
        assertEquals(NOTHING, w.sample(1500, ZombieWatch.watchable(true, true, true), STALL));
        assertEquals(NOTHING, w.sample(1500, ZombieWatch.watchable(true, true, false), 5L * 60 * 1000));
    }

    /**
     * Verifies /save-all flushes reset the stall clock rather than merely muting it, so post-save silence
     * still requires a full unexcused window before firing.
     */
    @Test
    void anOperatorSaveIsNeverAZombie_untilTheSaveEnds() {
        ZombieWatch w = new ZombieWatch(STALL);
        assertEquals(NOTHING, w.sample(2000, ZombieWatch.watchable(true, false, true), 0));
        assertEquals(NOTHING, w.sample(2000, ZombieWatch.watchable(true, false, true), STALL));
        assertEquals(NOTHING, w.sample(2000, ZombieWatch.watchable(true, false, true), STALL * 3));
        assertEquals(NOTHING, w.sample(2000, ZombieWatch.watchable(true, false, false), STALL * 3 + 1_000));
        assertEquals(NOTHING, w.sample(2000, ZombieWatch.watchable(true, false, false), STALL * 4 - 1));
        assertEquals(WENT_ZOMBIE, w.sample(2000, ZombieWatch.watchable(true, false, false), STALL * 4));
    }

    /** Verifies the 120s window (2x vanilla's 60s max-tick-time) treats a 90s spike as lag, not a hang. */
    @Test
    void aNinetySecondSpikeIsLag_notDeath() {
        ZombieWatch w = new ZombieWatch(STALL);
        assertEquals(NOTHING, w.sample(1200, true, 0));
        assertEquals(NOTHING, w.sample(1200, true, 30_000));
        assertEquals(NOTHING, w.sample(1200, true, 60_000));
        assertEquals(NOTHING, w.sample(1200, true, 90_000));
        assertEquals(NOTHING, w.sample(1201, true, 95_000));
    }

    /** Verifies stall time tracks clock time without tick progress, so a slow GitHub poll delaying samples
     *  never causes a false zombie alarm when ticks moved. */
    @Test
    void aDelayedSampleIsALateVerdict_neverAFalseOne() {
        ZombieWatch late = new ZombieWatch(STALL);
        assertEquals(NOTHING, late.sample(70, true, 0));
        assertEquals(WENT_ZOMBIE, late.sample(70, true, 400_000));
        assertEquals(400_000, late.stalledMs());

        ZombieWatch fine = new ZombieWatch(STALL);
        assertEquals(NOTHING, fine.sample(70, true, 0));
        assertEquals(NOTHING, fine.sample(71, true, 400_000));
    }

    /** Verifies ZombieWatch uses the threshold passed to its constructor rather than a hardcoded constant. */
    @Test
    void theWindowIsWhateverItWasBuiltWith() {
        ZombieWatch w = new ZombieWatch(1_000);
        assertEquals(NOTHING, w.sample(4, true, 0));
        assertEquals(NOTHING, w.sample(4, true, 999));
        assertEquals(WENT_ZOMBIE, w.sample(4, true, 1_000));
    }
}
