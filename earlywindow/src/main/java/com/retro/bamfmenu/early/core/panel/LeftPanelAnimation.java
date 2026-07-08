package com.retro.bamfmenu.early.core.panel;

/* The reactive left panel of the rhythm highway. A song chooses a left-panel animation (and may swap
   between several at set points in the song -- see LeftPanelTrack); each frame the renderer asks the active
   animation for the monospace lines to show, the colour to draw them in, and a small shake offset, then
   draws them itself. Returning text (rather than drawing here) keeps all GL in the provider and lets new
   animation TYPES -- the reactive face is the first -- plug in without touching the renderer. */
public interface LeftPanelAnimation {

    /* The reaction state the renderer hands in each frame (all times in song-ms). Rebuilt per draw from the
       engine, so animations stay stateless except for whatever they choose to remember. */
    final class State {
        public int songMs;
        public int reactLane;      // 0..3 = the lane that just came due, -1 = none yet
        public boolean reactChord; // 2+ lanes came due at the same time
        public int reactLaneMs;    // song-ms when that lane/chord reaction fired
        public int reactMissMs;    // song-ms of the most recent miss
        public int reactHoldLane = -1;  // lane (0..3) of a CHART hold note spanning now, -1 = none (a held look holds its last frame)
        public int combo;
    }

    /* The lines to render this frame, top to bottom (may differ every call so continuous animation works).
       An empty array draws nothing. */
    String[] frame(State s);

    /* The colour (ABGR) to draw this frame in. */
    int color(State s);

    /* A small per-frame shake the renderer adds, in characters / lines (0 = steady). Used for the "got hit"
       recoil on a miss; the renderer clamps it so the art never leaves the cell. */
    default int shakeCols(State s) {
        return 0;
    }

    default int shakeRows(State s) {
        return 0;
    }

    /* How this animation's art is placed in the panel cell (Phase C1): alignment + padding the renderer honors.
       Defaults to the original placement (left-aligned with a 1-col inset, vertically centered), so an animation
       that doesn't override this looks exactly as it did before. Override to centre/pin content (e.g. a face). */
    default PanelLayout layout() {
        return PanelLayout.defaults();
    }
}
