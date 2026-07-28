package com.retro.bamfmenu.early.core.updater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests progress bar math and edge cases; crashes here break early boot before any error screen exists. */
class ProgressBarTest {

    private static final int INDENT = 19;
    private static final int WIDTH = 38;

    @Test
    void fillTracksThePercentAndStopsInsideTheTrack() {
        for (int pct = 0; pct <= 100; pct++) {
            ProgressBar.Row r = ProgressBar.of(INDENT, WIDTH, pct, 100);
            assertNotNull(r, String.valueOf(pct));
            int hashes = (int) r.text().chars().filter(c -> c == '#').count();
            int dots = (int) r.text().chars().filter(c -> c == '.').count();
            assertEquals(WIDTH, hashes + dots, String.valueOf(pct));
            assertTrue(r.fill() <= r.text().length(), String.valueOf(pct));
            if (hashes > 0) {
                // The green prefix must end on the last '#' and never spill into the dots.
                assertEquals('#', r.text().charAt(r.fill() - 1), String.valueOf(pct));
            }
        }
    }

    @Test
    void emptyBarGreensNothingAndFullBarGreensThroughTheLastCell() {
        assertEquals(0, ProgressBar.of(INDENT, WIDTH, 0, 100).fill());
        ProgressBar.Row full = ProgressBar.of(INDENT, WIDTH, 100, 100);
        assertEquals(INDENT + 1 + WIDTH, full.fill());
        assertTrue(full.text().contains("100%"));
    }

    /**
     * A resumed download can report more bytes than the reported total length.
     * Without clamping, repeat() got a negative count and threw during rendering.
     */
    @Test
    void overshootAndNonsenseInputsDoNotThrow() {
        assertDoesNotThrow(() -> ProgressBar.of(INDENT, WIDTH, 999, 100));
        assertEquals(INDENT + 1 + WIDTH, ProgressBar.of(INDENT, WIDTH, 999, 100).fill());
        assertDoesNotThrow(() -> ProgressBar.of(INDENT, WIDTH, -5, 100));
        assertEquals(0, ProgressBar.of(INDENT, WIDTH, -5, 100).fill());
        assertNull(ProgressBar.of(INDENT, WIDTH, 50, 0));
        assertNull(ProgressBar.of(INDENT, WIDTH, 50, -1));
        assertNull(ProgressBar.of(INDENT, 0, 50, 100));
    }

    /** A multi-gigabyte release overflows int; the bar must still be sane at that size. */
    @Test
    void gigabyteScaleReadsInOneUnit() {
        long total = 3L * 1024 * 1024 * 1024;
        ProgressBar.Row r = ProgressBar.of(INDENT, WIDTH, total / 2, total);
        assertTrue(r.text().endsWith("1.5/3.0 GB"), r.text());
        assertTrue(r.text().contains(" 50%"), r.text());
    }

    /** Both halves share one unit, or "0.9 MB/1.2 GB" makes the reader do the conversion. */
    @Test
    void bothHalvesUseTheTotalsUnit() {
        ProgressBar.Row r = ProgressBar.of(INDENT, WIDTH, 40L * 1024 * 1024, 48L * 1024 * 1024);
        assertTrue(r.text().endsWith("40.0/48.0 MB"), r.text());
    }

    @Test
    void showTheBar() {
        System.out.println();
        for (int pct : new int[]{0, 7, 25, 50, 79, 100}) {
            long total = 48L * 1024 * 1024;
            ProgressBar.Row r = ProgressBar.of(INDENT, WIDTH, total * pct / 100, total);
            System.out.println("Fetching         : cobblemon-neoforge-1.6.1.jar  /");
            System.out.println(r.text() + "        <- green through char " + r.fill());
        }
        System.out.println();
    }
}
