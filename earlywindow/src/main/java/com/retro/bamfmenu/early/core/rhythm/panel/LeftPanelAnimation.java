package com.retro.bamfmenu.early.core.rhythm.panel;

/* The reactive left panel of the rhythm highway; a song picks one (LeftPanelTrack can swap them mid-song).
   Returning text instead of drawing keeps all GL in the provider and lets new animation types plug in
   without touching the renderer. */
public interface LeftPanelAnimation {

    /* The reaction state the renderer hands in each frame (times in song-ms). Rebuilt per draw, so
       animations stay stateless except whatever they choose to remember. */
    final class State {
        public int songMs;
        public int reactLane;      // 0..3 = the lane that just came due, -1 = none yet
        public boolean reactChord; // 2+ lanes came due at the same time
        public int reactLaneMs;    // song-ms when that lane/chord reaction fired
        public int reactMissMs;    // song-ms of the most recent miss
        public int reactHoldLane = -1;  // lane (0..3) of a CHART hold note spanning now, -1 = none
        public int combo;
    }

    /* The lines to render this frame, top to bottom, may differ every call so continuous animation works; an empty array draws nothing. */
    String[] frame(State s);

    int color(State s); // ABGR

    /* Miss-recoil shake in chars/lines, 0 = steady; the renderer clamps it so the art never leaves the cell. */
    default int shakeCols(State s) {
        return 0;
    }

    default int shakeRows(State s) {
        return 0;
    }

    /* Placement in the panel cell; defaults() is the original placement, so an animation that doesn't override it looks unchanged. */
    default PanelLayout layout() {
        return PanelLayout.defaults();
    }
}
