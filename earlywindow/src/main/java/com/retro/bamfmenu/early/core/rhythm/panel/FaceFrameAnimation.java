package com.retro.bamfmenu.early.core.rhythm.panel;

/* The reactive face left-panel animation, now CLIP-based: each look is a multi-frame `Keyframe[]` clip, so a look
   eases in instead of snapping. The face is a CHALLENGER / demonstrator (DDR-style): it
   animates the CHART's upcoming notes and winces on a MISS; it NEVER reacts to the player's correct presses.

   State machine (render thread only):
   - A new MISS (reactMissMs advances) or a new note/chord coming DUE (reactLaneMs advances) ABORTS the in-progress
     clip and restarts the new look from frame 0, even for the SAME lane, so faster input never leaves a stale,
     half-finished animation (the look "re-triggers", it doesn't lazily finish).
   - A lane/chord/miss look plays ONCE; when its clip finishes it falls back to IDLE.
   - IDLE LOOPS (so the face is alive between notes) when an idle clip is supplied; with no idle clip it holds the
     last lane/chord look in the idle tint (the old between-notes behavior, so Recovery is unchanged).
   - A look on a HELD note (the chart has a hold spanning this lane, reactHoldLane) holds its LAST frame for the
     hold's span (a sustained "sing", not a loop), then falls to IDLE.

   A 1-frame clip reproduces the old static behavior exactly; the convenience constructor wraps the old single-frame
   `String[]` looks as 1-frame clips with the old hold durations, so existing callers (Recovery) are byte-compatible.
   Single-threaded; no synchronization. */
public final class FaceFrameAnimation implements LeftPanelAnimation {

    /* Old hold windows, reused as the 1-frame convenience-clip durations so the back-compat path matches exactly. */
    private static final int LANE_HOLD_MS = 320;
    private static final int MISS_HOLD_MS = 360;
    private static final int SHAKE_MS = 260;

    /* Look ids (distinct from the 0..3 lane indices). */
    private static final int MISS = -2, CHORD = -3, IDLE = -1;
    private static final int ENGINE_SENTINEL = -100000; // RhythmEngine's initial reactLaneMs/reactMissMs

    private final Keyframe[][] lanes; // [0..3] per-lane clip
    private final Keyframe[] chord;
    private final Keyframe[] miss;
    private final Keyframe[] idle;    // optional; empty -> idle holds the last lane/chord look (old behavior)
    private final int idleColor;

    // state machine (render thread only)
    private int activeLook = IDLE;
    private int lookStartMs;
    private int lastLook = IDLE;              // last lane/chord look shown (for the no-idle-clip fallback)
    private int seenLaneMs = ENGINE_SENTINEL; // last consumed reactLaneMs
    private int seenMissMs = ENGINE_SENTINEL; // last consumed reactMissMs
    private int tickedMs = Integer.MIN_VALUE;  // advance the machine once per frame (frame()+color() both call tick)

    /* Clip constructor: each look is a `Keyframe[]` clip (a song can supply multi-frame looks here, e.g. Gaster). */
    public FaceFrameAnimation(Keyframe[][] lanes, Keyframe[] chord, Keyframe[] miss, Keyframe[] idle, int idleColor) {
        this.lanes = lanes != null ? lanes : new Keyframe[0][];
        this.chord = chord != null ? chord : new Keyframe[0];
        this.miss = miss != null ? miss : new Keyframe[0];
        this.idle = idle != null ? idle : new Keyframe[0];
        this.idleColor = idleColor;
    }

    /* Back-compat convenience constructor: the OLD single-frame looks (one `String[]` per look + per-look colors).
       Each look becomes a 1-frame clip with the old hold duration -> identical static behavior + timing. */
    public FaceFrameAnimation(String[][] lanes, String[] chord, String[] miss, String[] idle,
                              int[] laneColors, int chordColor, int missColor, int idleColor) {
        this(toLaneClips(lanes, laneColors), toClip(chord, chordColor, LANE_HOLD_MS),
                toClip(miss, missColor, MISS_HOLD_MS), toClip(idle, idleColor, LANE_HOLD_MS), idleColor);
    }

    private static Keyframe[] toClip(String[] art, int color, int durationMs) {
        return (art != null && art.length > 0) ? new Keyframe[]{new Keyframe(art, durationMs, color)} : new Keyframe[0];
    }

    private static Keyframe[][] toLaneClips(String[][] lanes, int[] colors) {
        if (lanes == null) {
            return new Keyframe[0][];
        }
        Keyframe[][] out = new Keyframe[lanes.length][];
        for (int i = 0; i < lanes.length; i++) {
            int color = (colors != null && i < colors.length) ? colors[i] : 0xFFFFFFFF;
            out[i] = toClip(lanes[i], color, LANE_HOLD_MS);
        }
        return out;
    }

    /* Advance the state machine ONCE per frame (frame() + color() both call this with the same per-frame State). */
    private void tick(State s) {
        if (s.songMs == tickedMs) {
            return; // already advanced this frame
        }
        tickedMs = s.songMs;
        if (s.reactMissMs > seenMissMs) {                            // a new miss outranks everything
            seenMissMs = s.reactMissMs;
            seenLaneMs = Math.max(seenLaneMs, s.reactLaneMs);        // consume any concurrent lane-due
            activeLook = MISS;
            lookStartMs = s.songMs;
        } else if (s.reactLane >= 0 && s.reactLaneMs > seenLaneMs) { // a new note/chord came DUE
            seenLaneMs = s.reactLaneMs;
            activeLook = s.reactChord ? CHORD : s.reactLane;
            lastLook = activeLook;
            lookStartMs = s.songMs;
        } else if (activeLook != IDLE && !holding(s) && finishedOnce(s)) { // once-clip done + not held -> idle
            activeLook = IDLE;
            lookStartMs = s.songMs;
        }
    }

    /* True if the active look is a lane whose chart has a hold spanning now (hold the last frame instead of idling). */
    private boolean holding(State s) {
        return activeLook >= 0 && activeLook == s.reactHoldLane;
    }

    private boolean finishedOnce(State s) {
        Keyframe[] clip = rawClip(activeLook);
        return clip.length > 0 && (s.songMs - lookStartMs) >= totalMs(clip);
    }

    @Override
    public String[] frame(State s) {
        tick(s);
        Keyframe k = activeKeyframe(s);
        return (k != null && k.art() != null) ? k.art() : new String[0];
    }

    @Override
    public int color(State s) {
        tick(s);
        Keyframe k = activeKeyframe(s);
        return k != null ? k.color() : idleColor;
    }

    /* The keyframe to show this frame for the active look (after tick has chosen it). */
    private Keyframe activeKeyframe(State s) {
        if (activeLook == IDLE) {
            if (idle.length > 0) {
                return frameAt(idle, s.songMs - lookStartMs, true, false); // IDLE loops
            }
            // no idle clip -> hold the last lane/chord look's last frame, in the idle tint (old between-notes look)
            Keyframe[] last = nonEmpty(rawClip(lastLook));
            return last.length > 0 ? new Keyframe(last[last.length - 1].art(), 1, idleColor) : null;
        }
        Keyframe[] clip = nonEmpty(rawClip(activeLook));
        if (clip.length == 0) {
            return null;
        }
        return frameAt(clip, s.songMs - lookStartMs, false, holding(s)); // play once; a held look holds its last frame
    }

    /* Pick a keyframe by elapsed ms: holdLast -> the final frame; loop -> wrap; else one-shot (settle on the last). */
    private static Keyframe frameAt(Keyframe[] clip, int elapsed, boolean loop, boolean holdLast) {
        if (clip.length == 0) {
            return null;
        }
        if (holdLast) {
            return clip[clip.length - 1];
        }
        int total = totalMs(clip);
        if (elapsed <= 0) {
            return clip[0];
        }
        if (!loop && elapsed >= total) {
            return clip[clip.length - 1];
        }
        int t = loop ? (elapsed % total) : elapsed;
        int acc = 0;
        for (Keyframe k : clip) {
            acc += Math.max(1, k.durationMs());
            if (t < acc) {
                return k;
            }
        }
        return clip[clip.length - 1];
    }

    private static int totalMs(Keyframe[] clip) {
        int t = 0;
        for (Keyframe k : clip) {
            t += Math.max(1, k.durationMs());
        }
        return Math.max(1, t);
    }

    private Keyframe[] rawClip(int look) {
        if (look == MISS) {
            return miss;
        }
        if (look == CHORD) {
            return chord;
        }
        if (look == IDLE) {
            return idle;
        }
        return (look >= 0 && look < lanes.length && lanes[look] != null) ? lanes[look] : new Keyframe[0];
    }

    /* The clip if it has frames, else the chord clip (degrade so a missing resource never blanks the panel). */
    private Keyframe[] nonEmpty(Keyframe[] clip) {
        if (clip != null && clip.length > 0) {
            return clip;
        }
        return (chord != null && chord.length > 0) ? chord : new Keyframe[0];
    }

    @Override
    public int shakeCols(State s) {
        int dt = s.songMs - s.reactMissMs;
        if (dt < 0 || dt >= SHAKE_MS) {
            return 0;
        }
        return ((dt / 45) % 2 == 0) ? 1 : -1; // left-right jitter
    }

    @Override
    public int shakeRows(State s) {
        int dt = s.songMs - s.reactMissMs;
        if (dt < 0 || dt >= SHAKE_MS) {
            return 0;
        }
        return ((dt / 60) % 3 == 0) ? 1 : 0; // an occasional vertical bob
    }
}
