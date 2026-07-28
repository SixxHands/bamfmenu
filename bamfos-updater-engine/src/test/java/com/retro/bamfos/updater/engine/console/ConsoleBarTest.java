package com.retro.bamfos.updater.engine.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Bounds tests for the pre-JVM server console bar; an uncaught exception here blocks server startup. */
class ConsoleBarTest {

    @Test
    void everyPercentRendersInsideTheTrackAndFitsAConsole() {
        for (int p = 0; p <= 100; p++) {
            String line = ConsoleBar.render("cobblemon-neoforge-1.6.1.jar", p, 100, p);
            // Only count dots between the brackets; jar names like 1.6.1.jar have dots too.
            String track = line.substring(line.indexOf('[') + 1, line.indexOf(']'));
            assertEquals(28, track.length(), String.valueOf(p) + " / " + String.valueOf(line));
            assertEquals(track.length(), track.chars().filter(c -> c == '#' || c == '.').count(), String.valueOf(line));
            assertTrue(line.length() <= 78, String.valueOf(p) + " / " + String.valueOf(line.length()));
        }
    }

    /** A resumed transfer can report more bytes than expected; unclamped, repeat() gets a negative count
     * and throws during server startup. */
    @Test
    void overshootAndUnderflowAreClamped() {
        assertEquals(100, ConsoleBar.pct(999, 100));
        assertEquals(0, ConsoleBar.pct(-5, 100));
        assertEquals(0, ConsoleBar.pct(50, 0));
        assertDoesNotThrow(() -> ConsoleBar.render("x.jar", 999, 100, ConsoleBar.pct(999, 100)));
        assertDoesNotThrow(() -> ConsoleBar.render("x.jar", -5, 100, ConsoleBar.pct(-5, 100)));
    }

    /** Keep the end of long jar names since the version is what differs between rows. */
    @Test
    void longNamesClipFromTheFrontAndKeepTheVersion() {
        String clipped = ConsoleBar.clip("create_enchantment_industry-1.21.1-2.0.6.jar");
        assertEquals(22, clipped.length());
        assertTrue(clipped.startsWith(".."), clipped);
        assertTrue(clipped.endsWith("2.0.6.jar"), clipped);
        assertEquals("short.jar", ConsoleBar.clip("short.jar"));
    }

    @Test
    void bothHalvesShareTheTotalsUnit() {
        long mb = 1024L * 1024L;
        assertEquals("MB", ConsoleBar.unit(48 * mb));
        assertEquals("40.0", ConsoleBar.num(40 * mb, 48 * mb));
        assertEquals("48.0", ConsoleBar.num(48 * mb, 48 * mb));
        assertEquals("GB", ConsoleBar.unit(3 * 1024 * mb));
    }

    /** Under the test runner stdout is a pipe (redirected), so nothing should print. */
    @Test
    void aRedirectedStdoutDrawsNothing() {
        ConsoleBar bar = ConsoleBar.forThisProcess();
        assertDoesNotThrow(() -> bar.at("x.jar", 5, 10));
        assertDoesNotThrow(bar::finish);
        assertDoesNotThrow(ConsoleBar.silent()::finish, "finish before any paint is safe");
    }
}
