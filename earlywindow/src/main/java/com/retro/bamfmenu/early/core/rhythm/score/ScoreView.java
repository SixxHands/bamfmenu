package com.retro.bamfmenu.early.core.rhythm.score;

/* The read-only face of the scoring state, for whoever DRAWS or REPORTS it -- the rhythm renderer, a
   special's HUD, the results capture, the editor's test view. It exposes every value a GUI needs without
   granting the ability to mutate the economy (only the owning game logic holds the ScoringEngine and calls
   apply/tickDrain). RhythmRenderer takes a ScoreView, not a ScoringEngine. */
public interface ScoreView {

    int score();

    int combo();

    int maxCombo();

    int perfect();

    int good();

    int okay();

    int bad();

    int miss();

    int fail();

    /* The multiplier meter (perfect-count gate) + the points accrued at the current multiplier this combo. */
    int comboPerfects();

    int comboBucket();

    /* The current combo multiplier (1..maxMult) derived from the meter. */
    int comboMult();

    /* Raw health (0..healthMax) and the 0..1 fraction (the bar + the SAFE/low-health colour). */
    float health();

    float healthFraction();

    boolean failed();

    boolean noFail();

    /* The last judgment text (e.g. "PERFECT x3"/"MISS"/"DROP") + the song-time it was stamped, for the
       transient on-screen judgment flash. */
    String lastJudgment();

    int lastJudgmentMs();
}
