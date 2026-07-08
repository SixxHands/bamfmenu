package com.retro.bamfmenu.early.core.rhythm;

import com.retro.bamfmenu.early.core.rhythm.score.Grade;
import com.retro.bamfmenu.early.core.rhythm.score.ScoreRules;
import com.retro.bamfmenu.early.core.rhythm.score.ScoringEngine;

import java.util.ArrayList;
import java.util.List;

/* The rhythm game's pure logic (the Recovery Mode "BAMFBEAT" engine): 4 lanes, scrolling notes with hit
   windows, and hold/sustain notes. No GL, no audio, no Minecraft -- the provider feeds it the current song
   time (from the audio playback position) and the player's lane presses/releases. Single-threaded (render
   thread), so nothing here is synchronized. This is the reusable engine; songs differ only by their note
   chart.

   .75 step 7.1: the SCORING / combo / multiplier / health economy moved out into a uniform
   ScoringEngine (early.game.score) that this engine HOLDS and delegates to -- so a bespoke SPECIAL mode can
   feed its own conditions into the same economy. RhythmEngine keeps only the rhythm-specific concerns: the
   note chart + timing-window classification, auto-miss + sustain resolution, and the chart-driven left-panel
   react signals. press() turns a timing window into a Grade and calls scoring.apply(); update() resolves
   notes into MISS/SUSTAIN/DROP grades. Read the score state via the public `scoring` (a ScoreView). */
public final class RhythmEngine {

    public static final int LANES = 4;

    /* Default note fall speed (ms of look-ahead the playfield height represents). The fallback when a chart
       doesn't author its own; smaller = faster. Per-chart authoring overrides via the `approachMs` field below.
       Lives here in core (the renderer is bios; core can't depend on it). */
    public static final int DEFAULT_APPROACH_MS = 1500;

    /* Hit windows (ms from a note's exact time). Tightened (owner: was too forgiving) -- all still
       easy knobs in one place. Five tiers with an asymmetric late tail: you can catch a note up to LATE_MS after
       it passes (that lands as BAD, not a MISS), but pressing more than EARLY_MS before it is a ghost tap
       (ignored, no penalty). |dt| <= PERFECT/GOOD/OKAY; a late dt beyond OKAY (up to LATE_MS) is BAD. */
    private static final int PERFECT_MS = 35;  // was 50
    private static final int GOOD_MS = 70;     // was 105
    private static final int OKAY_MS = 100;    // was 160
    private static final int LATE_MS = 145;    // was 260 -- catch this late -> BAD
    private static final int EARLY_MS = 100;   // was 160 -- earlier than this -> ghost tap, ignored

    /* One playable note. headJudged: the tap was resolved (hit or missed). headHit: it was a hit (so a
       hold can sustain). holdResolved: the sustain finished (completed or dropped). */
    public static final class Note {
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

    /* The uniform scoring economy (score / combo / multiplier / health / tallies / judgment). Public so the
       renderer + results capture read it through its ScoreView; mutated only via the apply()/tickDrain()
       calls this engine makes below. */
    public final ScoringEngine scoring;

    public final List<Note> notes;
    public int songMs;
    /* Per-chart note fall speed (ms look-ahead) the renderer uses; set by the host from the loaded chart, else
       the DEFAULT. Purely visual -- timing/judgment never depend on it. */
    public int approachMs = DEFAULT_APPROACH_MS;
    /* Song-start count-in digit the renderer draws centered (3/2/1/0) during the pre-roll; -1 = not counting.
       songMs is NEGATIVE during the pre-roll so notes scroll in from the top; the host (RhythmScreen) drives
       both this and that negative clock, then hands off to the audio clock at songMs=0. */
    public int countDigit = -1;
    public final boolean[] laneHeld = new boolean[LANES];
    /* Left-panel reaction signal, read by the renderer's left-panel animation (e.g. the reactive face). It
       is CHART-driven: as each note reaches its time, the lane(s) you SHOULD hit are flagged here (notes
       sharing a time become a chord); a miss stamps reactMissMs so the panel can show a hurt look + shake.
       Touched only on the render thread (update/registerMiss), like the rest of the engine. */
    public int reactLane = -1;          // 0..3 = the lane that just came due, -1 = none yet
    public boolean reactChord;          // 2+ lanes came due at the same time
    public int reactLaneMs = -100000;   // songMs when that lane/chord reaction fired
    public int reactMissMs = -100000;   // songMs of the most recent miss
    private int reactIdx;        // forward pointer into the time-sorted notes; before it = already announced
    private final int chartEndMs;
    private final int tailMs; // grace past the last note before the song counts as finished (per-song, from DetectorConfig)

    public RhythmEngine(List<Note> chart, boolean noFail, int tailMs) {
        this.scoring = new ScoringEngine(ScoreRules.defaults(), noFail); // no-fail = Safe For Retro
        this.tailMs = tailMs;
        this.notes = chart;
        this.notes.sort((a, b) -> Integer.compare(a.timeMs, b.timeMs));
        int end = 0;
        for (Note n : notes) {
            end = Math.max(end, n.endMs());
        }
        this.chartEndMs = end;
    }

    public int chartEndMs() {
        return chartEndMs;
    }

    public boolean isFinished() {
        return !scoring.failed() && songMs > chartEndMs + tailMs;
    }

    /* The song-time at which a natural finish -> RESULTS fires (chart end + the end-roll tail). The host fades
       the audio out over the last half-second before this so the song eases into silence, not a hard cut. */
    public int finishMs() {
        return chartEndMs + tailMs;
    }

    /* Advance to the current song time: drain the multiplier meter, auto-miss notes whose window has passed,
       and resolve sustains -- each resolution routed through scoring.apply() so the economy stays uniform. */
    public void update(int songMs) {
        this.songMs = songMs;
        scoring.tickDrain(songMs); // 1/sec multiplier-meter decay, re-syncing on empty (verbatim, now in scoring)
        for (Note n : notes) {
            if (!n.headJudged && songMs > n.timeMs + LATE_MS) {
                n.headJudged = true;
                n.headHit = false;
                n.faded = true; // missed -> grey out + linger (uniform with dropped holds)
                scoring.apply(Grade.MISS, songMs);
                reactMissMs = songMs; // tell the left panel to show its hurt look + shake (view signal, engine-side)
            }
            if (n.headHit && n.isHold() && !n.holdResolved) {
                n.holdActive = laneHeld[n.lane];
                if (songMs > n.endMs() + LATE_MS) {
                    /* Held all the way through with no explicit release (or the release event was lost) -> the
                       sustain completes: still holding = a clean PERFECT tail, otherwise a GOOD completion. The
                       graded release + the early-release DROP are handled in release() on the actual key-up, so
                       this only catches the held-through case (a sustain can never linger unresolved). */
                    n.holdResolved = true;
                    n.holdActive = false;
                    scoring.apply(laneHeld[n.lane] ? Grade.PERFECT : Grade.GOOD, songMs);
                }
            }
        }
        scoring.clampAndLatch(); // end-of-update health clamp + fail latch (the old update() tail)
        announceReactions(songMs);
    }

    /* Chart-driven left-panel reaction: as the clock crosses each note's time, announce the lane you should
       be hitting (notes sharing a time collapse into a chord). reactIdx walks the time-sorted notes once, so
       this is cheap; if several beats pass in one frame we report the latest. */
    private void announceReactions(int songMs) {
        int beatMs = Integer.MIN_VALUE, laneMask = 0, distinct = 0, single = -1;
        while (reactIdx < notes.size() && notes.get(reactIdx).timeMs <= songMs) {
            Note n = notes.get(reactIdx);
            if (n.timeMs != beatMs) {
                beatMs = n.timeMs;
                laneMask = 0;
                distinct = 0;
                single = -1;
            }
            int bit = 1 << n.lane;
            if ((laneMask & bit) == 0) {
                laneMask |= bit;
                distinct++;
                single = n.lane;
            }
            reactIdx++;
        }
        if (beatMs != Integer.MIN_VALUE) {
            reactChord = distinct >= 2;
            reactLane = single;
            reactLaneMs = songMs;
        }
    }

    /* A lane key went down at song-time t: judge the nearest unjudged note in that lane within the window
       [-EARLY_MS, +LATE_MS] (signed: + is late). Nearest by |dt|. The timing window -> Grade classification
       lives here (rhythm-specific); the score/combo/health effect is the uniform scoring.apply(). */
    public void press(int lane, int t) {
        if (lane < 0 || lane >= LANES) {
            return;
        }
        laneHeld[lane] = true;
        Note best = null;
        int bestAbs = Integer.MAX_VALUE;
        for (Note n : notes) {
            if (n.lane != lane || n.headJudged) {
                continue;
            }
            int dt = t - n.timeMs;
            if (dt < -EARLY_MS || dt > LATE_MS) {
                continue;
            }
            int a = Math.abs(dt);
            if (a < bestAbs) {
                best = n;
                bestAbs = a;
            }
        }
        if (best == null) {
            scoring.apply(Grade.FAIL, t); // pressed with no note in range -> a "fail" hit; free in no-fail
            return;
        }
        best.headJudged = true;
        best.headHit = true;
        /* Classify the timing window into a Grade; the uniform engine does the points/meter/health/judgment.
           The judgment text is stamped at the engine's current songMs (the last update's value), matching the
           historical behaviour where press() ran before update() each frame. */
        Grade g = bestAbs <= PERFECT_MS ? Grade.PERFECT
                : bestAbs <= GOOD_MS ? Grade.GOOD
                : bestAbs <= OKAY_MS ? Grade.OKAY
                : Grade.BAD;
        scoring.apply(g, this.songMs);
        if (best.isHold()) {
            best.holdActive = true;
        }
    }

    /* A lane key went UP at song-time t. If a hold sustain is live in this lane, RESOLVE it by the release timing
       (the pack's "judge the press AND the release"): a release near the note's end is a graded completion (like
       a tail note, |t-endMs| -> PERFECT/GOOD/OKAY/BAD), while letting go well before the end is a DROP (combo
       break + grey-out). A plain tap's release finds no live sustain and just clears the held flag. */
    public void release(int lane, int t) {
        if (lane < 0 || lane >= LANES) {
            return;
        }
        laneHeld[lane] = false;
        for (Note n : notes) {
            if (n.lane != lane || !n.headHit || !n.isHold() || n.holdResolved) {
                continue;
            }
            int relDt = t - n.endMs(); // + = released late (held past the end), - = released early
            n.holdResolved = true;
            n.holdActive = false;
            if (relDt < -EARLY_MS) {
                n.faded = true;                       // let go too early -> drop (combo break) + grey out
                scoring.apply(Grade.DROP, this.songMs);
            } else {
                int a = Math.abs(relDt);              // graded tail: how close the release was to the end
                Grade g = a <= PERFECT_MS ? Grade.PERFECT
                        : a <= GOOD_MS ? Grade.GOOD
                        : a <= OKAY_MS ? Grade.OKAY
                        : Grade.BAD;
                scoring.apply(g, this.songMs);
            }
            break; // at most one live sustain per lane
        }
    }

    /* Visual-only lane state for the song-start count-in (the 3-2-1-0): light the lane so the buttons RESPOND to
       presses, but DON'T judge -- no note is hit, missed, dropped, or failed, and the score/health/combo are
       untouched. The host calls this during the pre-roll instead of press()/release(); once the count-in ends it
       switches back to the scoring press()/release(). */
    public void setLaneVisual(int lane, boolean down) {
        if (lane < 0 || lane >= LANES) {
            return;
        }
        laneHeld[lane] = down;
    }

    /* A hand-authored TEST pattern for (NOT synced to any song's music -- generates the
       real chart from the audio). Exercises every mechanic: single taps in each lane, two-note chords,
       quick bursts, and hold notes. Starts after a lead-in so the player can get ready. */
    public static List<Note> demoChart() {
        List<Note> n = new ArrayList<>();
        int t = 2500;
        int step = 420;
        /* warm-up: one tap per lane, ascending */
        for (int lane = 0; lane < LANES; lane++) {
            n.add(new Note(lane, t, 0));
            t += step;
        }
        /* alternating stream */
        int[] stream = {0, 1, 2, 3, 2, 1, 0, 1, 3, 2};
        for (int s : stream) {
            n.add(new Note(s, t, 0));
            t += step;
        }
        /* a couple of chords */
        n.add(new Note(0, t, 0));
        n.add(new Note(3, t, 0));
        t += step * 2;
        n.add(new Note(1, t, 0));
        n.add(new Note(2, t, 0));
        t += step * 2;
        /* hold notes (sustains) */
        n.add(new Note(0, t, 900));
        n.add(new Note(3, t, 900));
        t += step * 3;
        /* a fast burst */
        int[] burst = {0, 1, 2, 3, 0, 1, 2, 3};
        for (int s : burst) {
            n.add(new Note(s, t, 0));
            t += step / 2;
        }
        /* a long single hold to finish */
        n.add(new Note(2, t + step, 1400));
        return n;
    }
}
