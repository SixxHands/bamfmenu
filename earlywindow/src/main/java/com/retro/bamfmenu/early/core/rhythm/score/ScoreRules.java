package com.retro.bamfmenu.early.core.rhythm.score;

/* The scoring economy knobs, as one immutable record so the combo/score MACHINE stays uniform while the
   numbers are tunable per caller. defaults() reproduces EXACTLY the rhythm game's historical constants
   (health bounds + per-grade health deltas + multiplier cap + the point table) so the extraction is
   behavior-identical. A bespoke SPECIAL can pass its own ScoreRules (e.g. hMiss()==0 if a miss should not
   cost health) and still get the identical combo/multiplier behavior, the rules vary, the engine does not. */
public record ScoreRules(
        float healthMax,
        float healthStart,
        float hPerfect,
        float hGood,
        float hOkay,
        float hBad,
        float hMiss,
        float hDrop,
        int maxMult,
        int perfectPts,
        int goodPts,
        int okayPts,
        int badPts,
        int sustainPts) {

    /* The verbatim historical values from RhythmEngine (HEALTH_MAX/START, H_*, MAX_MULT, the 350/200/120/40
       point table, and the completed-sustain 120). */
    public static ScoreRules defaults() {
        return new ScoreRules(
                100f,   // healthMax
                50f,    // healthStart
                2f,     // hPerfect
                1f,     // hGood
                0.5f,   // hOkay
                -1f,    // hBad
                -6f,    // hMiss
                -4f,    // hDrop
                4,      // maxMult
                350,    // perfectPts
                200,    // goodPts
                120,    // okayPts
                40,     // badPts
                120);   // sustainPts (a completed hold)
    }
}
