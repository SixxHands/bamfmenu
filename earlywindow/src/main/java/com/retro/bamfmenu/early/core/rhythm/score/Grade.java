package com.retro.bamfmenu.early.core.rhythm.score;

/* The uniform judgment token fed into ScoringEngine.apply(). The rhythm game classifies a press's timing
   window into one of these; a bespoke SPECIAL mode classifies its OWN conditions into the same tokens. A
   caller emits only the SUBSET it uses -- a special that has no exactness tiers can emit just {PERFECT, MISS}
   and the engine handles it; unused grades simply never fire (the permissiveness the pack host asked for).

   - PERFECT/GOOD/OKAY/BAD : graded hits (combo++, banked points x multiplier, health bump). Only PERFECT
     grows the multiplier meter; only BAD knocks it down; GOOD/OKAY leave it alone.
   - MISS  : a missed note -- breaks the combo, miss-tally, full health penalty, shows judgment text.
   - DROP  : a dropped sustain -- breaks the combo, its own (lighter) health penalty, NO tally.
   - FAIL  : a stray input with nothing in range -- breaks the combo + full health penalty but NO tally,
             NO judgment text; a no-fail/Safe-For-Retro caller (or one with no input-bounds concept) never
             feels it. Opt-in: a special that does not model stray input just never emits it.
   - SUSTAIN : a completed hold -- banks points x multiplier + small heal, NO tally, NO combo change. */
public enum Grade {
    PERFECT,
    GOOD,
    OKAY,
    BAD,
    MISS,
    DROP,
    FAIL,
    SUSTAIN
}
