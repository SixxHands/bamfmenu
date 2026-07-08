package com.retro.bamfmenu.early.bios.transition;

/* A timed BIOS transition -- a wall-clock sequence of named phases (the opening cutscene, the song-start
   lead-in, and future screen-to-screen wipes / pre-rolls). Deliberately WALL-CLOCK with NO freeze/pause: the
   cutscene + lead-in run on uninterrupted real time today (the F2 overlay skips their step but never advances
   their clock), so a freezable clock would shift their timing. The owning screen draws each phase via
   TerminalCanvas and owns any audio side-effects; the transition only tells time. start(now) takes the clock
   in so the type stays pure + testable (the provider supplies System.nanoTime()).

   .75 step 8: the unified home for the cutscene timing + the lead-in (owner chose BUILD NOW); future
   screen transitions plug in as new PhaseTimeline subclasses without touching the existing ones. */
public interface Transition {

    /* Begin the transition at the given clock reading (System.nanoTime()). */
    void start(long nowNanos);

    /* Milliseconds elapsed since start. */
    long elapsedMs(long nowNanos);

    /* The current phase index (0-based); equals the phase count once every phase has elapsed (i.e. done). */
    int phase(long nowNanos);

    /* True once the last phase has elapsed -- the pack host hands off / tears down. */
    boolean done(long nowNanos);
}
