package com.retro.bamfmenu.early.bios.transition.impl;

import com.retro.bamfmenu.early.bios.transition.PhaseTimeline;

/* The song-start lead-in as a single-phase transition: the playfield is shown with the freshly-played song
   PAUSED at ~0 for holdMs, so the player registers the screen before the first note (shared by RHYTHM +
   SPECIAL). One phase [HOLD]; done() at the deadline. The provider owns the LeadIn through a volatile field --
   it is the single source of truth for "is a lead-in active" (onKey reads the volatile reference to gate
   input; the render thread builds/clears it and owns the audio pause/resume side-effects). The timeline
   itself only tells time. */
public final class LeadIn extends PhaseTimeline {

    public LeadIn(long holdMs) {
        super(new long[]{holdMs});
    }
}
