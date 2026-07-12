package com.retro.bamfmenu.early.core.rhythm.engine;

import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.rhythm.score.Grade;
import com.retro.bamfmenu.early.core.rhythm.score.ScoreRules;
import com.retro.bamfmenu.early.core.rhythm.score.ScoringEngine;

import java.util.ArrayList;
import java.util.List;

/* The rhythm game's pure logic (the Recovery Mode "BAMF!" engine): 4 lanes, hit windows, hold notes.
   No GL, no audio, no Minecraft; the host feeds it song time + lane presses. Single-threaded (render
   thread), so nothing here is synchronized. The score/combo/health economy lives in the delegated
   ScoringEngine so a bespoke SPECIAL mode can feed its own conditions into the same economy; this engine
   keeps only the chart + timing concerns. */
public final class RhythmEngine {

    public static final int LANES = 4;

    // Fallback note fall speed when a chart doesn't set one (smaller = faster). No core dependencies in bios render.
    public static final int DEFAULT_APPROACH_MS = 1500;

    /* Hit windows (ms from a note's exact time). Asymmetric late tail: pressing up to LATE_MS late counts
     * as BAD instead of MISS; earlier than EARLY_MS is out of range. */
    private static final int PERFECT_MS = 35;  // was 50
    private static final int GOOD_MS = 70;     // was 105
    private static final int OKAY_MS = 100;    // was 160
    private static final int LATE_MS = 145;    // was 260
    private static final int EARLY_MS = 100;   // was 160

    // Public for the renderer/results to READ (as a ScoreView); mutated only by this engine's apply()/tickDrain() calls.
    public final ScoringEngine scoring;

    public final List<Note> notes;
    public int songMs;
    // Set by the host from the loaded chart. Purely visual, timing/judgment never depend on it.
    public int approachMs = DEFAULT_APPROACH_MS;
    /* Count-in digit the renderer draws (3/2/1/0), -1 = not counting. songMs runs NEGATIVE during the
       pre-roll so notes scroll in from the top; the host hands off to the audio clock at songMs=0. */
    public int countDigit = -1;
    public final boolean[] laneHeld = new boolean[LANES];
    /* Left-panel reaction signals, CHART-driven: as each note comes due, the lane(s) you SHOULD hit are
       flagged (shared-time notes = a chord); a miss stamps reactMissMs so the panel can show a hurt look + shake. */
    public int reactLane = -1;          // 0..3 = the lane that just came due, -1 = none yet
    public boolean reactChord;          // 2+ lanes came due at the same time
    public int reactLaneMs = -100000;   // songMs when that lane/chord reaction fired
    public int reactMissMs = -100000;   // songMs of the most recent miss
    private int reactIdx;        // forward pointer into the time-sorted notes; before it = already announced
    private final int chartEndMs;
    private final int tailMs; // grace past the last note before the song counts as finished (host-supplied end-roll)

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

    // The host fades the audio out over the last half-second before this so the song eases into silence, not a hard cut.
    public int finishMs() {
        return chartEndMs + tailMs;
    }

    public void update(int songMs) {
        this.songMs = songMs;
        scoring.tickDrain(songMs); // 1/sec multiplier-meter decay
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
                    /* Held through the end with no release event -> the sustain completes (still holding =
                       PERFECT tail, else GOOD). The graded release + early DROP live in release(); this
                       catches only the held-through case, so a sustain can never linger unresolved. */
                    n.holdResolved = true;
                    n.holdActive = false;
                    scoring.apply(laneHeld[n.lane] ? Grade.PERFECT : Grade.GOOD, songMs);
                }
            }
        }
        scoring.clampAndLatch(); // end-of-update health clamp + fail latch
        announceReactions(songMs);
    }

    // reactIdx walks the time-sorted notes once, so this is cheap; if several beats pass in one frame we report only the latest.
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

    /* Key down: judge the nearest unjudged note in this lane within [-EARLY_MS, +LATE_MS]. The window -> Grade
       classification lives here (rhythm-specific); the score/combo/health effect is the uniform scoring.apply(). */
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
        Grade g = bestAbs <= PERFECT_MS ? Grade.PERFECT
                : bestAbs <= GOOD_MS ? Grade.GOOD
                : bestAbs <= OKAY_MS ? Grade.OKAY
                : Grade.BAD;
        scoring.apply(g, this.songMs); // stamped at the last update's songMs, NOT t, matching the historical press-before-update ordering
        if (best.isHold()) {
            best.holdActive = true;
        }
    }

    /* Holds judge the press AND the release: a release near the note's end grades like a tail note, letting
       go well before it is a DROP. A plain tap's release finds no live sustain and just clears the held flag. */
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

    /* Count-in visual only: light the lane so the buttons RESPOND, but judge NOTHING and touch no
       score/health/combo. The host calls this during the pre-roll instead of press()/release(). */
    public void setLaneVisual(int lane, boolean down) {
        if (lane < 0 || lane >= LANES) {
            return;
        }
        laneHeld[lane] = down;
    }

    /* Hand-authored TEST pattern, NOT synced to any song.
       Exercises every mechanic: taps, chords, bursts, holds, after a lead-in so the player can get ready. */
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
