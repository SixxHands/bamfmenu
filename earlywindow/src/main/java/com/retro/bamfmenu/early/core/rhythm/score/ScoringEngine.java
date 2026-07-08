package com.retro.bamfmenu.early.core.rhythm.score;

/* The uniform scoring / combo / multiplier / health state machine, lifted VERBATIM out of RhythmEngine so it can be driven by ANY game mode, not just the rhythm highway. The rhythm game
   classifies a press's timing window into a Grade then calls apply(); a bespoke SPECIAL classifies its OWN
   conditions into Grades and calls the IDENTICAL apply() -- the economy is byte-identical because it lives
   only here. Single-threaded (render thread), like the rest of the early-window game logic; nothing here is
   synchronized.

   PERMISSIVE BY CONSTRUCTION: a caller emits only the grades it uses. {PERFECT, MISS}-only works; unused
   grade branches never fire. FAIL (stray-input penalty) and tickDrain (the time-based meter decay) are
   opt-in -- a mode with no input-bounds / no song clock simply never calls them. The numbers are a ScoreRules
   the caller supplies, so a special can soften/disable a penalty while keeping the same combo machine. */
public final class ScoringEngine implements ScoreView {

    private final ScoreRules rules;
    private final boolean noFail;

    private int score;
    private int combo;
    private int maxCombo;
    private int perfect;
    private int good;
    private int okay;
    private int bad;
    private int miss;
    private int fail; // "fail" hits: input with no target in range (drain health silently, no tally)
    private int comboPerfects; // the multiplier meter: +1 per PERFECT, -1 per BAD, -1 per second; reset on break
    private int comboBucket;   // points accrued (x multiplier) during the current combo; banked to score on break
    private int lastDrainMs;   // song-time bookkeeping for the 1/sec meter drain
    private float health;
    private boolean failed;
    private String lastJudgment = "";
    private int lastJudgmentMs = -100000;

    public ScoringEngine(ScoreRules rules, boolean noFail) {
        this.rules = rules;
        this.noFail = noFail;
        this.health = rules.healthStart();
    }

    /* The single uniform per-grade entry. stampMs is the song time used to tag the judgment text (so the
       transient flash times correctly); it is ignored by the grades that show no text (FAIL/SUSTAIN).

       The per-grade ORDER is load-bearing and verbatim from the old RhythmEngine.press/registerMiss/
       registerFail + the sustain/drop branches of update():
        - the meter (comboPerfects) is mutated BEFORE comboMult() is read, so a hit that crosses an x2/x3/x4
          threshold banks at the NEW multiplier;
        - only PERFECT grows the meter, only BAD knocks it down, GOOD/OKAY leave it untouched;
        - FAIL shows no judgment, stamps no tally, and is free in no-fail mode;
        - DROP uses its own (lighter) health penalty and adds no tally;
        - SUSTAIN banks points + a small heal with no combo/tally/judgment change. */
    public void apply(Grade g, int stampMs) {
        switch (g) {
            case PERFECT:
            case GOOD:
            case OKAY:
            case BAD: {
                int pts;
                String type;
                if (g == Grade.PERFECT) {
                    perfect++;
                    combo++;
                    comboPerfects++;
                    pts = rules.perfectPts();
                    health += rules.hPerfect();
                    type = "PERFECT";
                } else if (g == Grade.GOOD) {
                    good++;
                    combo++;
                    pts = rules.goodPts();
                    health += rules.hGood();
                    type = "GOOD";
                } else if (g == Grade.OKAY) {
                    okay++;
                    combo++;
                    pts = rules.okayPts();
                    health += rules.hOkay();
                    type = "OKAY";
                } else { // BAD: a late catch -- counts + scores, keeps the combo, knocks the meter down one
                    bad++;
                    combo++;
                    comboPerfects = Math.max(0, comboPerfects - 1);
                    pts = rules.badPts();
                    health += rules.hBad();
                    type = "BAD";
                }
                int mult = comboMult(); // read AFTER the meter change (threshold-crossing rewards immediately)
                comboBucket += pts * mult;
                setJudgment(type + " x" + mult, stampMs);
                maxCombo = Math.max(maxCombo, combo);
                health = clamp(health);
                if (!noFail && health <= 0f) {
                    failed = true;
                }
                return;
            }
            case MISS:
                miss++;
                breakCombo(); // a miss banks the combo bucket + resets streak/meter
                health = clamp(health + rules.hMiss());
                setJudgment("MISS", stampMs);
                return;
            case DROP: // a dropped sustain -- combo break + lighter penalty, no tally. (Clamp deferred to clampAndLatch.)
                breakCombo();
                health += rules.hDrop();
                setJudgment("DROP", stampMs);
                return;
            case FAIL: // stray input with nothing in range: no tally, no judgment; free in no-fail
                if (noFail) {
                    return;
                }
                fail++;
                breakCombo();
                health = clamp(health + rules.hMiss()); // full miss-level penalty
                if (health <= 0f) {
                    failed = true;
                }
                return;
            case SUSTAIN: // a completed hold: banks points + small heal, no combo/tally/judgment
                comboBucket += rules.sustainPts() * comboMult();
                health = Math.min(rules.healthMax(), health + 1f);
                return;
        }
    }

    /* Drain the multiplier meter by 1 per second of song time (continues through note gaps -- no
       forgiveness); only the multiplier decays, the combo streak + bucket are untouched. When the meter
       empties the clock re-syncs to songMs so a freshly-earned perfect is not instantly over-drained after a
       lag stall or audio scrub. Time-based + opt-in: call ONCE per frame BEFORE per-note work; a caller with
       no song clock simply never calls it. */
    public void tickDrain(int songMs) {
        while (comboPerfects > 0 && songMs - lastDrainMs >= 1000) {
            comboPerfects--;
            lastDrainMs += 1000;
        }
        if (comboPerfects == 0) {
            lastDrainMs = songMs;
        }
    }

    /* The end-of-update health clamp + fail latch (the old RhythmEngine.update tail): catches any unclamped
       intermediate (e.g. a DROP's raw subtraction) and latches failure once. Call after the per-note loop. */
    public void clampAndLatch() {
        health = clamp(health);
        if (!noFail && health <= 0f) {
            failed = true;
        }
    }

    /* End the current combo: bank its accumulated (multiplied) points into the score and reset the streak +
       meter. Internal -- triggered by MISS/FAIL/DROP via apply(). */
    private void breakCombo() {
        score += comboBucket;
        comboBucket = 0;
        comboPerfects = 0;
        combo = 0;
    }

    /* Bank any pending combo points into the score WITHOUT resetting the streak -- for a natural finish, where
       the song ends mid-combo and the bucket should still count. */
    public void flushPending() {
        score += comboBucket;
        comboBucket = 0;
    }

    /* The current combo multiplier (1..maxMult) from the perfect meter: x1 below 4, then x2 at 4, x3 at 8,
       x4 at 16 (doubling thresholds), capped at maxMult. */
    @Override
    public int comboMult() {
        if (comboPerfects < 4) {
            return 1;
        }
        int e = 31 - Integer.numberOfLeadingZeros(comboPerfects / 4); // floor(log2(comboPerfects/4))
        return Math.min(2 + e, rules.maxMult());
    }

    private void setJudgment(String s, int stampMs) {
        lastJudgment = s;
        lastJudgmentMs = stampMs;
    }

    private float clamp(float h) {
        return h < 0f ? 0f : (h > rules.healthMax() ? rules.healthMax() : h);
    }

    /* ---- ScoreView (read-only) ---- */
    @Override public int score() { return score; }
    @Override public int combo() { return combo; }
    @Override public int maxCombo() { return maxCombo; }
    @Override public int perfect() { return perfect; }
    @Override public int good() { return good; }
    @Override public int okay() { return okay; }
    @Override public int bad() { return bad; }
    @Override public int miss() { return miss; }
    @Override public int fail() { return fail; }
    @Override public int comboPerfects() { return comboPerfects; }
    @Override public int comboBucket() { return comboBucket; }
    @Override public float health() { return health; }
    @Override public float healthFraction() { return health / rules.healthMax(); }
    @Override public boolean failed() { return failed; }
    @Override public boolean noFail() { return noFail; }
    @Override public String lastJudgment() { return lastJudgment; }
    @Override public int lastJudgmentMs() { return lastJudgmentMs; }
}
