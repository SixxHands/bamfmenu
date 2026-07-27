package com.retro.bamfmenu.early.bios.host;

import java.util.function.IntUnaryOperator;

/**
 * The BIOS SETUP / UPDATE AND REPAIR canvas size, the ONE owner of the widen rule.
 * Why it lives out here. drawSetup and the UPDATE AND REPAIR window hand off to each other, so a
 * second copy of this formula means walking into the window and back visibly changes the font size the
 * first time somebody tunes one of the terms. Out here it is also GL-free and FML-free, which is what lets
 * the rule that decides whether the box clears the control bar be tested at all; the provider that used to
 * hold it needs a real GL context to exist.
 * IMPORTANT: two things this class knows that no caller should have to re-derive: the closed form is not
 * the answer on its own (see bodyWidth), and the control bar does NOT live in the box's canvas (see
 * legendBodyY).
 */
public final class SetupSizing {

    /* How many correction passes bodyWidth will take before it ships whatever width it has. */
    private static final int CORRECTION_PASSES = 8;

    /* Widest canvas bodyWidth will ever hand back, as a multiple of baseW. Past here the font renders
       sub-pixel, so a wider canvas has stopped being a fix, and a bounded ceiling is also what keeps the
       correction loop's arithmetic sane: the step is a shortfall divided by a gain that can approach zero,
       which used to saturate an int cast and wrap the width NEGATIVE. */
    private static final int MAX_WIDEN_FACTOR = 40;

    private SetupSizing() {
    }

    /** The logical height that goes with a body width: the canvas keeps the window's aspect. */
    public static int bodyHeight(int bodyW, int fbW, int fbH) {
        return (int) Math.max(1L, (long) bodyW * fbH / Math.max(1, fbW)); // long: a widened bodyW * fbH overflows int
    }

    /**
     * Where drawControlBar's legend row lands inside a `bodyW` canvas. The bar is drawn in its OWN baseW
     * canvas on purpose (one global element at one size, whatever screen is up), and both canvases cover the
     * whole framebuffer, so on a widened body the legend is not at bodyH - lineSpacing.
     * IMPORTANT: at baseW this is exactly bodyH - lineSpacing and the two spaces only diverge as the canvas
     * widens, which is how a gate written in the box's own space read green while the bar's glyphs actually
     * overlapped the box's bottom border by 22px on a 5120x1440 window.
     */
    public static int legendBodyY(int bodyW, int baseW, int fbW, int fbH, int lineSpacing) {
        int barH = bodyHeight(Math.max(1, baseW), fbW, fbH);
        return (int) ((long) (barH - lineSpacing) * bodyHeight(bodyW, fbW, fbH) / Math.max(1, barH));
    }

    /**
     * How many interior rows a box whose top sits at `boxTop` may draw before it reaches the legend, never
     * fewer than the `rows` the widen rule budgeted. Both screens grow their box with this (the jukebox list
     * and the repair rows use the whole height), and both had their own copy fed logicalH - lineSpacing, so a
     * widened body earned itself one extra row and drew it straight onto the control bar.
     */
    public static int boxCapacity(int rows, int boxTop, int legendY, int lineSpacing) {
        return Math.max(rows, (legendY - lineSpacing - boxTop) / Math.max(1, lineSpacing) - 1);
    }

    /**
     * True when a `rows`-row option box on a `bodyW` canvas still leaves a clear row between its bottom
     * border and the control legend. This is THE gate: bodyWidth solves for it, and both screens draw to it.
     */
    public static boolean boxFits(int bodyW, int baseW, int fbW, int fbH, int rows, int lineSpacing,
            IntUnaryOperator headerBottom) {
        int boxBottom = headerBottom.applyAsInt(bodyW) + (rows + 3) * lineSpacing;
        return boxBottom + lineSpacing <= legendBodyY(bodyW, baseW, fbW, fbH, lineSpacing);
    }

    /**
     * The canvas width for a header + an option box of `rows` rows + the bottom legend. Locked to baseW like
     * every other screen, except at a wide and short window (maximized, or any ultrawide) where the shorter
     * logical height crowds the box into the legend: there the canvas widens a little, which shrinks the
     * font, just enough to restore the one-row gap. A window that already fits keeps the unified width.
     * The answer is either a width that PASSES boxFits or baseW itself: a layout nothing can solve gets the
     * readable locked width, never a 56,000px canvas that is both unreadable and still colliding.
     */
    public static int bodyWidth(int baseW, int fbW, int fbH, int rows, int lineSpacing,
            IntUnaryOperator headerBottom) {
        if (fbW <= 0 || fbH <= 0 || baseW <= 0) {
            return baseW;
        }
        if (boxFits(baseW, baseW, fbW, fbH, rows, lineSpacing, headerBottom)) {
            return baseW;
        }
        double a = (double) fbH / fbW;                              // body height bought per pixel of width
        double c = (double) headerBottom.applyAsInt(baseW) / baseW; // header's share of the body width
        /* What a widened pixel is actually worth: the height it buys, minus the header that grows with it,
           minus the legend row sliding DOWN the box canvas as the bar's canvas shrinks relative to it. */
        double gain = a - c - (double) lineSpacing / baseW;
        if (gain <= 0) {
            return baseW; // widening buys nothing here, the header and the bar eat it as fast as it arrives
        }
        long cap = Math.min(Integer.MAX_VALUE, (long) MAX_WIDEN_FACTOR * baseW);
        long w = Math.min(cap, Math.max(baseW, (long) Math.ceil((rows + 4) * (double) lineSpacing / gain)));
        /* The closed form solves the layout in REALS, and every pixel it turns into is a floor: c reads the
           banner a hair shorter than headerBottom will actually draw it, and both canvas heights are integer
           divides. So it lands a pixel or three short, and at 2560x1080, a plain ultrawide, short meant the
           box bottom sat exactly 1px inside the control bar. It is only the opening guess now; the real
           integer geometry gets the last word. The step is the measured shortfall converted back into width,
           so this normally lands in one pass, and the loop is BOUNDED because a layout that will not converge
           still has to hand back a width and draw a frame, never spin. */
        for (int i = 0; i < CORRECTION_PASSES
                && !boxFits((int) w, baseW, fbW, fbH, rows, lineSpacing, headerBottom); i++) {
            long shortBy = headerBottom.applyAsInt((int) w) + (long) (rows + 4) * lineSpacing
                    - legendBodyY((int) w, baseW, fbW, fbH, lineSpacing);
            long next = Math.min(cap, w + Math.max(1L, (long) Math.ceil(shortBy / gain)));
            if (next == w) {
                break; // pinned at the ceiling, another pass cannot move it
            }
            w = next;
        }
        return boxFits((int) w, baseW, fbW, fbH, rows, lineSpacing, headerBottom) ? (int) w : baseW;
    }
}
