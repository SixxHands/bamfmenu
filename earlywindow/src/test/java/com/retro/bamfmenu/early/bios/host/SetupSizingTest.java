package com.retro.bamfmenu.early.bios.host;

import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the canvas widen rule against real framebuffer geometry rather than legendBodyY.
 * Guards against 2560x1080 clipping and 5120x1440 bar overlap from mixed canvas heights.
 */
class SetupSizingTest {

    private static final int BASE_W = 1400;   // Matches TerminalWindowProvider.LOGICAL_W and the control bar canvas.
    /* SETUP_OPTIONS.length today. The provider cannot be loaded here (it implements an FML interface that is
       compileOnly), so this one number is a copy, and the sweep below covers ROW_LOW..ROW_HIGH instead so a
       menu that grows past 12 is caught by the build rather than by a player's screenshot. */
    private static final int ROWS = 12;
    private static final int ROW_LOW = 8;
    private static final int ROW_HIGH = 16;

    /* Measured from ui/bamfos.txt (8x69), HEADER_FW 0.42, and gohufont@24 (28px line, 13px advance):
       banner canvas is 69*13/0.42 px wide, 10 rows tall, scaled into body pixels by headerBottomBodyY. */
    private static final int LIVE_LINE_SPACING = 28;
    private static final double LIVE_BANNER_CANVAS = 69 * 13 / 0.42;
    private static final double LIVE_BANNER_BOTTOM = 10 * LIVE_LINE_SPACING;
    private static final IntUnaryOperator LIVE_HEADER =
            w -> (int) (LIVE_BANNER_BOTTOM * w / LIVE_BANNER_CANVAS);

    /** Regression test: widening must clear the control bar at 2560x1080 without being off by one. */
    @Test
    void ultrawide2560x1080_boxClearsTheControlBar() {
        int w = SetupSizing.bodyWidth(BASE_W, 2560, 1080, ROWS, LIVE_LINE_SPACING, LIVE_HEADER);
        assertTrue(w > BASE_W);
        assertTrue(SetupSizing.boxFits(w, BASE_W, 2560, 1080, ROWS, LIVE_LINE_SPACING, LIVE_HEADER), String.valueOf((LIVE_HEADER.applyAsInt(w) + (ROWS + 3) * LIVE_LINE_SPACING)) + " / " + String.valueOf(SetupSizing.legendBodyY(w, BASE_W, 2560, 1080, LIVE_LINE_SPACING)) + " / " + String.valueOf(w));
    }

    /**
     * Verifies clearance in framebuffer pixels without calling legendBodyY or boxFits.
     * The panel uses the widened canvas while the control bar stays in BASE_W, scaling taller by bodyW/BASE_W.
     */
    @Test
    void wideAndShort_theBoxClearsTheBarWhereTheBarIsReallyDrawn() {
        int[][] windows = {{5120, 1440}, {2560, 800}, {3840, 900}, {3840, 1080}, {2560, 1080},
                {1920, 720}, {3440, 1440}, {2560, 900}, {1920, 800}};
        for (int[] win : windows) {
            int w = SetupSizing.bodyWidth(BASE_W, win[0], win[1], ROWS, LIVE_LINE_SPACING, LIVE_HEADER);
            int boxH = SetupSizing.bodyHeight(w, win[0], win[1]);
            int barH = SetupSizing.bodyHeight(BASE_W, win[0], win[1]); // Matches the logical canvas used by drawControlBar.
            double boxBottomFb = (LIVE_HEADER.applyAsInt(w) + (ROWS + 3) * LIVE_LINE_SPACING)
                    * (double) win[1] / boxH;
            double nextBoxRowFb = boxBottomFb + LIVE_LINE_SPACING * (double) win[1] / boxH;
            double barRowFb = (barH - LIVE_LINE_SPACING) * (double) win[1] / barH;
            assertTrue(nextBoxRowFb <= barRowFb,
                    win[0] + "x" + win[1] + " at width " + w + ": the panel's next row starts at fb y "
                            + Math.round(nextBoxRowFb) + " but the control bar draws at fb y "
                            + Math.round(barRowFb));
        }
    }

    /**
     * Row capacity must count against the same legend row as the fit gate.
     * Counting from the panel canvas bottom lets 5120x1440 draw an extra row over the control bar.
     */
    @Test
    void theGrowingBox_neverEarnsARowTheControlBarIsUsing() {
        int[][] windows = {{5120, 1440}, {3840, 900}, {2560, 800}, {2560, 1080}, {1920, 720}, {1920, 1080}};
        int wrongWouldHaveTakenMore = 0;
        for (int[] win : windows) {
            int w = SetupSizing.bodyWidth(BASE_W, win[0], win[1], ROWS, LIVE_LINE_SPACING, LIVE_HEADER);
            int boxTop = LIVE_HEADER.applyAsInt(w) + 2 * LIVE_LINE_SPACING;
            int legendY = SetupSizing.legendBodyY(w, BASE_W, win[0], win[1], LIVE_LINE_SPACING);
            int rows = SetupSizing.boxCapacity(ROWS, boxTop, legendY, LIVE_LINE_SPACING);
            int boxBottom = boxTop + (rows + 1) * LIVE_LINE_SPACING;
            assertTrue(boxBottom + LIVE_LINE_SPACING <= legendY,
                    win[0] + "x" + win[1] + ": " + rows + " interior rows put the bottom border at " + boxBottom
                            + ", and the legend is at " + legendY);
            int offTheBoxCanvas = SetupSizing.boxCapacity(ROWS, boxTop,
                    SetupSizing.bodyHeight(w, win[0], win[1]) - LIVE_LINE_SPACING, LIVE_LINE_SPACING);
            assertTrue(offTheBoxCanvas >= rows);
            if (offTheBoxCanvas > rows) {
                wrongWouldHaveTakenMore++;
            }
        }
        assertTrue(wrongWouldHaveTakenMore > 0);
    }

    /** Common window sizes players use, plus short and tall extremes. */
    @Test
    void everyRealisticWindow_endsUpFitting() {
        int[][] windows = {
                {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {1920, 1200},
                {2560, 1080}, {2560, 1440}, {3440, 1440}, {3840, 2160}, {5120, 1440},
                {1920, 720}, {2560, 800}, {3840, 900}, {1080, 1920}, {854, 480},
        };
        for (int[] win : windows) {
            for (int rows = ROW_LOW; rows <= ROW_HIGH; rows++) {
                int w = SetupSizing.bodyWidth(BASE_W, win[0], win[1], rows, LIVE_LINE_SPACING, LIVE_HEADER);
                assertTrue(SetupSizing.boxFits(w, BASE_W, win[0], win[1], rows, LIVE_LINE_SPACING, LIVE_HEADER),
                        win[0] + "x" + win[1] + " with " + rows + " rows still collides at width " + w);
            }
        }
    }

    /**
     * Walks the actual draw order by hand (header, rule, top, rows+1 border, mapped legend row)
     * so retuning boxFits without moving the draw calls fails this test.
     */
    @Test
    void theGate_agreesWithTheDrawOrder() {
        int[][] windows = {{1600, 900}, {1920, 1080}, {2560, 1080}, {3440, 1440}, {2560, 800}};
        for (int[] win : windows) {
            for (int bodyW = BASE_W; bodyW <= 2200; bodyW += 7) {
                int boxH = SetupSizing.bodyHeight(bodyW, win[0], win[1]);
                int barH = SetupSizing.bodyHeight(BASE_W, win[0], win[1]);
                int y = LIVE_HEADER.applyAsInt(bodyW);      // drawBiosHeader's return
                y += LIVE_LINE_SPACING;                     // the rule, then the title row
                int boxTop = y + LIVE_LINE_SPACING;
                int boxBottom = boxTop + (ROWS + 1) * LIVE_LINE_SPACING;
                int legendY = (int) ((long) (barH - LIVE_LINE_SPACING) * boxH / barH);
                boolean drawnClear = boxBottom + LIVE_LINE_SPACING <= legendY;
                assertEquals(drawnClear,
                        SetupSizing.boxFits(bodyW, BASE_W, win[0], win[1], ROWS, LIVE_LINE_SPACING, LIVE_HEADER), String.valueOf(bodyW) + " / " + String.valueOf(win[0]) + " / " + String.valueOf(win[1]));
            }
        }
    }

    /* Keep the unified width when the content already fits, so we don't widen the window and shrink the font
       unnecessarily across the early-window screens. */
    @Test
    void aWindowThatAlreadyFits_keepsTheLockedWidth() {
        assertEquals(BASE_W, SetupSizing.bodyWidth(BASE_W, 1920, 1080, ROWS, LIVE_LINE_SPACING, LIVE_HEADER));
        assertEquals(BASE_W, SetupSizing.bodyWidth(BASE_W, 1600, 900, ROWS, LIVE_LINE_SPACING, LIVE_HEADER));
        assertEquals(BASE_W, SetupSizing.bodyWidth(BASE_W, 2560, 1440, ROWS, LIVE_LINE_SPACING, LIVE_HEADER));
    }

    /* A framebuffer size we have not been told yet cannot be a reason to draw no frame, so the
       degenerate inputs fall back to the locked width instead of dividing by zero. */
    @Test
    void unknownFramebuffer_fallsBackToTheLockedWidth() {
        assertEquals(BASE_W, SetupSizing.bodyWidth(BASE_W, 0, 0, ROWS, LIVE_LINE_SPACING, LIVE_HEADER));
        assertEquals(BASE_W, SetupSizing.bodyWidth(BASE_W, -1, 1080, ROWS, LIVE_LINE_SPACING, LIVE_HEADER));
        assertEquals(BASE_W, SetupSizing.bodyWidth(BASE_W, 2560, -1, ROWS, LIVE_LINE_SPACING, LIVE_HEADER));
    }

    /* When a tall header grows faster than the extra height gained by widening,
       stop widening instead of growing the canvas forever. */
    @Test
    void headerDeeperThanTheWindowIsTall_stopsAtTheLockedWidth() {
        IntUnaryOperator hungryHeader = w -> w; // header bottom == the full body width
        assertEquals(BASE_W, SetupSizing.bodyWidth(BASE_W, 2560, 1080, ROWS, LIVE_LINE_SPACING, hungryHeader));
    }

    /**
     * Guards extreme aspect ratios where closed-form width math used to overflow int to negative.
     * Result must never drop below the locked width, and must either fit or fall back to it.
     */
    @Test
    void theEdgeOfSolvable_neverShipsAWidthBelowTheLockedOne() {
        double c = (double) LIVE_HEADER.applyAsInt(BASE_W) / BASE_W;
        double threshold = c + (double) LIVE_LINE_SPACING / BASE_W; // Below this point, widening doesn't recover any vertical space.
        for (int fbW : new int[] {2560, 3440, 5120, 7680}) {
            for (int step = -20; step <= 20; step++) {
                int fbH = (int) Math.round((threshold + step * 1e-4) * fbW);
                int w = SetupSizing.bodyWidth(BASE_W, fbW, fbH, ROWS, LIVE_LINE_SPACING, LIVE_HEADER);
                assertTrue(w >= BASE_W, fbW + "x" + fbH + " handed back " + w + ", under the locked width");
                assertTrue(w == BASE_W
                                || SetupSizing.boxFits(w, BASE_W, fbW, fbH, ROWS, LIVE_LINE_SPACING, LIVE_HEADER),
                        fbW + "x" + fbH + " shipped " + w + ", which neither fits nor is the locked width");
            }
        }
    }

    /**
     * Sweeps lineSpacing, banner depths, and row counts across wide/short windows so metric changes stay safe.
     * Fallback to locked width is allowed only when no wider canvas can fit (checked by the probe scan).
     */
    @Test
    void acrossEveryPlausibleFontMetric_theRuleFitsOrProvesItCannot() {
        int[][] windows = {{2560, 1080}, {3440, 1440}, {3840, 1080}, {2560, 900}, {1920, 800}};
        int checked = 0;
        int surrendered = 0;
        for (int ls = 18; ls <= 36; ls++) {
            for (int bannerRows = 6; bannerRows <= 12; bannerRows++) {
                for (int cw = 9; cw <= 19; cw++) {
                    final double canvas = 69 * cw / 0.42;
                    final double bottom = (bannerRows + 2) * (double) ls;
                    IntUnaryOperator header = w -> (int) (bottom * w / canvas);
                    for (int rows = ROW_LOW; rows <= ROW_HIGH; rows++) {
                        for (int[] win : windows) {
                            int w = SetupSizing.bodyWidth(BASE_W, win[0], win[1], rows, ls, header);
                            checked++;
                            if (SetupSizing.boxFits(w, BASE_W, win[0], win[1], rows, ls, header)) {
                                continue;
                            }
                            surrendered++;
                            for (int probe = BASE_W; probe <= 40 * BASE_W; probe += Math.max(1, probe / 8)) {
                                assertFalse(SetupSizing.boxFits(probe, BASE_W, win[0], win[1], rows, ls, header), String.valueOf(w) + " / " + String.valueOf(probe) + " / " + String.valueOf(ls) + " / " + String.valueOf(cw) + " / " + String.valueOf(bannerRows) + " / " + String.valueOf(rows) + " / " + String.valueOf(win[0]) + " / " + String.valueOf(win[1]));
                            }
                        }
                    }
                }
            }
        }
        assertEquals(19 * 7 * 11 * (ROW_HIGH - ROW_LOW + 1) * 5, checked);
        assertTrue(surrendered < checked / 4, String.valueOf(surrendered) + " / " + String.valueOf(checked));
    }
}
