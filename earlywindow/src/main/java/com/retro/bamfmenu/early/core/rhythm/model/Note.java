package com.retro.bamfmenu.early.core.rhythm.model;

/* One playable note, THE shared definition of the rhythm category (extracted from RhythmEngine, where half its
   importers reached into the engine ONLY to name this type): charts carry them, the engine judges them, the editor
   edits them, the osu converter emits them. Definition at the category root, beside SongDef, the secrets model.
   headJudged: the tap was resolved (hit or missed). headHit: it was a hit (so a hold can sustain).
   holdResolved: the sustain finished (completed or dropped). */
public final class Note {
    public final int lane;
    public final int timeMs;
    public final int holdMs;
    public boolean headJudged;
    public boolean headHit;
    public boolean holdActive;
    public boolean holdResolved;
    public boolean faded; // MISSED or DROPPED -> render greyed-out + linger until it falls off (NOT a clean hit/FAIL)

    public Note(int lane, int timeMs, int holdMs) {
        this.lane = lane;
        this.timeMs = timeMs;
        this.holdMs = holdMs;
    }

    public boolean isHold() {
        return holdMs > 0;
    }

    public int endMs() {
        return timeMs + holdMs;
    }
}
