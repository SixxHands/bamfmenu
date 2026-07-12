package com.retro.bamfmenu.early.core.rhythm.editor;

/* The editor viewport: maps song time (ms) <-> screen x, with pan + zoom. Pure math, headless (no bios/GL).
   Time increases to the RIGHT. pxPerMs is the zoom (logical px per ms); viewStartMs is the leftmost visible
   time and MAY be negative so the lead-in pad (before t=0) can be centered. originX is the logical x at which
   viewStartMs sits (the box's inner-left margin, set per-frame by the renderer). The lane<->y mapping lives in
   the renderer because the band height depends on the live framebuffer. */
public final class TimeMap {

    public static final double MIN_PX_PER_MS = 0.01;  // ~zoomed all the way out
    public static final double MAX_PX_PER_MS = 2.0;   // ~zoomed all the way in

    public double pxPerMs = 0.12;       // ~120 logical px per second to start
    public double viewStartMs = -1000;  // a second of lead-in pad on screen at the left
    public int originX = 16;            // logical x of viewStartMs

    public int xForMs(double ms) {
        return originX + (int) Math.round((ms - viewStartMs) * pxPerMs);
    }

    public double msForX(int x) {
        return viewStartMs + (x - originX) / pxPerMs;
    }

    /* Zoom by a factor, keeping the time currently under anchorX pinned to that same screen x. */
    public void zoomAt(double factor, int anchorX) {
        double anchorMs = msForX(anchorX);
        pxPerMs = Math.max(MIN_PX_PER_MS, Math.min(MAX_PX_PER_MS, pxPerMs * factor));
        viewStartMs = anchorMs - (anchorX - originX) / pxPerMs;
    }

    /* Pan the view by a number of pixels (positive = scroll right / later in the song). */
    public void panPixels(double px) {
        viewStartMs += px / pxPerMs;
    }
}
