package com.retro.bamfmenu.early.bios.transition.impl;

import com.retro.bamfmenu.early.bios.transition.PhaseTimeline;

/* The Recovery-Mode intro cutscene's phase timeline: an opening right-to-left wipe -> a blank cleared hold ->
   the header text-scramble settling on "Unzipping..." -> the face animation (synced to the jingle stabs) ->
   a full-screen scramble that hands off to the song. TIMING ONLY -- the art / draw / jingle / stab-sync stay
   in the owning code (the provider today, CutsceneScreen at step 9); this holds the cumulative phase
   boundaries (verbatim from the provider's old CS_* constants + computeCutsceneTimeline) so the cutscene runs
   on one shared transition clock. Wall-clock, no freeze. */
public final class CutsceneTimeline extends PhaseTimeline {

    public static final long WIPE1 = 600;                        // erase the SETUP screen, right-to-left
    public static final long CLEAR_HOLD = 800;                   // hold the blank cleared screen after the wipe
    public static final long SCRAMBLE_START = WIPE1 + CLEAR_HOLD; // begin scrambling the header (= 1400)
    public static final long SCRAMBLE_MS = 900;                  // scramble duration before "Unzipping" settles
    public static final long SETTLE_HOLD = 600;                  // hold the settled label before the face
    public static final long FACE_HOLD = 2200;                   // smile lands + holds (the "register" beat)
    public static final long OUTRO_SCRAMBLE_MS = 500;            // full-screen scramble before the song handoff

    public final long settle;     // header decoded -> "Unzipping" (+ animated dots)
    public final long faceStart;  // face animation + jingle begin
    public final long faceEnd;    // face ends; smile held -> outro scramble begins
    public final long outroEnd;   // full-screen scramble done -> hand off to the song

    public CutsceneTimeline() {
        super(boundaries());
        this.settle = phaseEnd(2);
        this.faceStart = phaseEnd(3);
        this.faceEnd = phaseEnd(4);
        this.outroEnd = phaseEnd(5);
    }

    /* The 6 cumulative phase-end times (ms): WIPE, CLEAR-HOLD, SCRAMBLE, SETTLE, FACE, OUTRO. Verbatim from
       the old computeCutsceneTimeline: settle=2300, faceStart=2900, faceEnd=5100, outroEnd=5600. */
    private static long[] boundaries() {
        long settle = SCRAMBLE_START + SCRAMBLE_MS;
        long faceStart = settle + SETTLE_HOLD;
        long faceEnd = faceStart + FACE_HOLD;
        long outroEnd = faceEnd + OUTRO_SCRAMBLE_MS;
        return new long[]{WIPE1, SCRAMBLE_START, settle, faceStart, faceEnd, outroEnd};
    }
}
