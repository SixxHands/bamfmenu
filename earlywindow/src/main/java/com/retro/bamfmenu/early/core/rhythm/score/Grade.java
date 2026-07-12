package com.retro.bamfmenu.early.core.rhythm.score;

/* The uniform judgment token fed into ScoringEngine.apply(). A caller emits only the SUBSET it uses (a
   special with no exactness tiers can emit just {PERFECT, MISS}); unused grades simply never fire. */
public enum Grade {
    PERFECT,  // only grade that grows the multiplier meter
    GOOD,
    OKAY,
    BAD,      // only grade that knocks the multiplier down
    MISS,     // breaks combo, tallied
    DROP,     // dropped sustain: breaks combo, no tally, lighter health hit than a miss
    FAIL,     // stray input with nothing in range: breaks combo, no tally, no judgment text; opt-in
    SUSTAIN   // completed hold: banks points, no combo change, no tally
}
