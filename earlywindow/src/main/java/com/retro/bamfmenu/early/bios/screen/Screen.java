package com.retro.bamfmenu.early.bios.screen;

import com.retro.bamfmenu.early.bios.Mode;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

/* One BIOS screen / Mode (POST, SETUP, CUTSCENE, RHYTHM, TETRIS, RESULTS, SPECIAL). The provider hosts the
   render thread, owns GL + the `state` field, and drives the active screen each frame: step(ctx) advances
   logic + drains the screen's own input queue (it takes NO canvas, so a screen physically cannot draw off
   the render thread), render(canvas, ctx) draws THROUGH the TerminalCanvas facade only (no LWJGL), and
   controlHints(ctx) returns the screen's row of the shared bottom bar. onEnter/onExit replace the old
   per-mode start / exit setup. This is the seam the renderFrame switch collapses into a Map of Mode-to-Screen.

   .75 scaffold: the interface exists now; concrete screens move out of TerminalWindowProvider one
   migration step at a time, easiest cluster first. */
public interface Screen {

    /* The Mode this screen backs, for the dispatch table. */
    Mode mode();

    /* Called once when this screen becomes active (the old start*). */
    default void onEnter(BiosContext ctx) {
    }

    /* Called once when leaving this screen (the old exit*). */
    default void onExit(BiosContext ctx) {
    }

    /* Advance one frame on the render thread: drain this screen's input queue + mutate its own state. Takes
       NO canvas by design, so a screen cannot issue GL outside render(). May request a transition via ctx. */
    void step(BiosContext ctx);

    /* Draw this frame through the canvas facade (render thread, inside the provider's bound FBO). */
    void render(TerminalCanvas canvas, BiosContext ctx);

    /* This screen's text for the shared bottom control bar ("" = none). Takes ctx so it can vary on
       sub-state (e.g. SETUP's jukebox vs option list, RESULTS clear vs game-over). */
    default String controlHints(BiosContext ctx) {
        return "";
    }
}
