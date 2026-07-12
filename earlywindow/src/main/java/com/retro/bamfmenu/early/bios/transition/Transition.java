package com.retro.bamfmenu.early.bios.transition;

/* A wall-clock sequence of named BIOS phases (the opening cutscene, the song-start lead-in). Deliberately
   NO freeze/pause: the F2 overlay skips a phase's step but never advances its clock, so a freezable clock
   would shift the timing. The transition only tells time; the owning screen draws and owns any audio. */
public interface Transition {

    /* nowNanos is System.nanoTime(), the provider passes the clock in. */
    void start(long nowNanos);

    long elapsedMs(long nowNanos);

    /* 0-based; equals the phase count once every phase has elapsed. */
    int phase(long nowNanos);

    boolean done(long nowNanos);
}
