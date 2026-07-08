package com.retro.bamfmenu.early.core.rhythm;

/* Pure conversion from a queued input event's capture time back to song time, so hit accuracy survives the
   input queue: eventMs = songMs - (now - eventNanos)/1e6. Factored out of RhythmScreen.step (.75 step
   7.4) as a static helper the editor's "test my chart" loop reuses to feed RhythmEngine.press() at the right
   song time -- no AudioPlayer / BiosContext / GL needed, so the timing-fidelity path is callable headless. */
public final class RhythmClock {

    private RhythmClock() {
    }

    /* The song-time (ms) an input event actually happened, given the current song clock (songMs sampled at
       nowNanos) and the event's captured System.nanoTime(). */
    public static int eventMs(int songMs, long nowNanos, long eventNanos) {
        return songMs - (int) ((nowNanos - eventNanos) / 1_000_000L);
    }
}
