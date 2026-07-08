package com.retro.bamfmenu.early.core.panel;

/* A timed/scripted left-panel animation: plays a list of keyframes (each = ASCII art + how long to hold it +
   its colour) off the song clock, independent of the chart. This is the SECOND LeftPanelAnimation type
   (after the reactive FaceFrameAnimation), shipped to prove the left cell is PERMISSIVE: a song composes its
   LeftPanelTrack from ANY mix of types, swapping at song-ms points, and a brand-new look is just a class like
   this -- the renderer never changes (it draws whatever frame(State) returns). Looping by default; one-shot
   holds the final frame. Single-threaded (render thread), so no synchronization.

   No song uses this yet (per-song art/animation authoring is deferred); it exists so the framework is
   complete and the first real scripted panel is a drop-in. */
public final class ScriptedFrameAnimation implements LeftPanelAnimation {

    /* The per-frame step is the shared `Keyframe` record (art + hold ms + colour), also used by FaceFrameAnimation. */

    private final Keyframe[] frames;
    private final int totalMs;
    private final boolean loop;
    /* The song-ms at which this animation becomes active (matches the LeftPanelTrack start time for this
       entry), so the timeline runs animation-relative rather than from absolute song time. */
    private final int startMs;

    ScriptedFrameAnimation(int startMs, boolean loop, Keyframe... frames) {
        this.frames = frames != null ? frames : new Keyframe[0];
        this.loop = loop;
        this.startMs = startMs;
        int t = 0;
        for (Keyframe k : this.frames) {
            t += Math.max(1, k.durationMs());
        }
        this.totalMs = Math.max(1, t);
    }

    private Keyframe current(State s) {
        if (frames.length == 0) {
            return null;
        }
        int elapsed = s.songMs - startMs;
        if (elapsed <= 0) {
            return frames[0];
        }
        if (!loop && elapsed >= totalMs) {
            return frames[frames.length - 1]; // one-shot: settle on the last frame
        }
        int t = loop ? (elapsed % totalMs) : elapsed;
        int acc = 0;
        for (Keyframe k : frames) {
            acc += Math.max(1, k.durationMs());
            if (t < acc) {
                return k;
            }
        }
        return frames[frames.length - 1];
    }

    @Override
    public String[] frame(State s) {
        Keyframe k = current(s);
        return (k != null && k.art() != null) ? k.art() : new String[0];
    }

    @Override
    public int color(State s) {
        Keyframe k = current(s);
        return k != null ? k.color() : 0xFFAAAAAA;
    }
}
