package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the restart warning schedule without needing a running server. */
class RestartWarningsTest {

    private static final long RESTART = 100_000L;

    /** For a 5m countdown, warnings start at 4m and end at 10s (since 5m is announced on start). */
    @Test
    void fiveMinutesSpeaksEveryMinuteThenAtTenSeconds() {
        long[] marks = WatchdogBrain.warnSchedule(300, RESTART);
        assertArrayEquals(new long[]{
                RESTART - 240 * 20L, RESTART - 180 * 20L, RESTART - 120 * 20L, RESTART - 60 * 20L,
                RESTART - 200L}, marks);
        assertEquals(5, marks.length);
    }

    /** Warnings must be ordered soonest-first so the tick handler steps through them in order. */
    @Test
    void marksAreInTheOrderTheyFire() {
        long[] marks = WatchdogBrain.warnSchedule(600, RESTART);
        for (int i = 1; i < marks.length; i++) {
            assertTrue(marks[i] > marks[i - 1], String.valueOf(i));
        }
        assertTrue(marks[marks.length - 1] < RESTART);
    }

    /** Keep the old 60s behavior for servers that explicitly set countdown=60. */
    @Test
    void sixtySecondsIsStillJustTheTenSecondWarning() {
        assertArrayEquals(new long[]{RESTART - 200L}, WatchdogBrain.warnSchedule(60, RESTART));
    }

    /** Short or zero countdowns produce no intermediate warnings and never schedule past ticks. */
    @Test
    void shortCountdownsStaySilent() {
        assertEquals(0, WatchdogBrain.warnSchedule(10, RESTART).length);
        assertEquals(0, WatchdogBrain.warnSchedule(0, RESTART).length);
    }

    /** Non-whole-minute countdowns still emit minute warnings without duplicating the initial message. */
    @Test
    void anAwkwardCountdownNeverRepeatsTheNumberItOpenedWith() {
        long[] marks = WatchdogBrain.warnSchedule(90, RESTART);
        assertArrayEquals(new long[]{RESTART - 60 * 20L, RESTART - 200L}, marks);

        for (long mark : WatchdogBrain.warnSchedule(120, RESTART)) {
            assertNotEquals(RESTART - 120 * 20L, mark);
        }
    }

    @Test
    void minutesReadAsMinutes() {
        assertEquals("5m", WatchdogBrain.human(300));
        assertEquals("1m", WatchdogBrain.human(60));
        assertEquals("1m 30s", WatchdogBrain.human(90));
        assertEquals("10s", WatchdogBrain.human(10));
        assertEquals("0s", WatchdogBrain.human(0));
    }
}
