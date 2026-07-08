package com.retro.bamfmenu.early.bios.screen.mode;

import com.retro.bamfmenu.early.bios.Mode;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.transition.impl.CutsceneTimeline;
import com.retro.bamfmenu.early.core.asset.Glitch;

/* Mode.CUTSCENE host -- the Recovery-Mode intro cutscene: an opening right-to-left wipe
   that erases the SETUP screen (drawn under via ctx.renderSetupUnder), a blank cleared hold, the header
   text-scramble settling on "Unzipping...", the face animation synced to the recovery jingle's stabs, then a
   full-screen scramble that hands off to the song. Runs on the shared CutsceneTimeline (wall-clock, no
   freeze, step 8); the timing is the timeline's, the drawing is here. The Recovery face art (faceA/faceB) is
   shared with the SETUP achievement panel so it stays provider-owned and is read via ctx. Extracted verbatim;
   pure logic + canvas drawing, no GL of its own (the raw-GL wipe is canvas.wipeFromRight). */
public final class CutsceneScreen implements Screen {

    private static final long FACE_NOISE_MS = 130;                  // glitch-noise burst before each face frame resolves
    private static final String UNZIP_WORD = "Unzipping";           // decoded base; the trailing dots animate separately
    /* The scramble/noise glyphs come from the shared core.asset.Glitch (also used by the hidden Snake game).
       EMPTY_ART = a blank face frame (draws nothing). */
    private static final String[] EMPTY_ART = new String[0];

    private CutsceneTimeline cutscene;
    private boolean jinglePlayed;
    private float[] stabs = new float[0]; // jingle stab times (s) for syncing the face animation
    /* Shared face footprint so both frames center identically (eyes line up + don't jump between frames). */
    private int faceWidth;
    private int faceHeight;

    @Override
    public Mode mode() {
        return Mode.CUTSCENE;
    }

    @Override
    public void onEnter(BiosContext ctx) {
        ctx.closeAudio();
        jinglePlayed = false;
        stabs = new float[0];
        faceWidth = Math.max(artWidth(ctx.recoveryFaceA()), artWidth(ctx.recoveryFaceB()));
        faceHeight = Math.max(ctx.recoveryFaceA().length, ctx.recoveryFaceB().length);
        cutscene = new CutsceneTimeline();
        cutscene.start(System.nanoTime()); // the shared wall-clock transition timeline (no freeze)
        ctx.enterMode(Mode.CUTSCENE); // single atomic state write; sets the 60fps period
    }

    /* Drive the cutscene timeline (render thread): fire the jingle at the face phase, then hand off to the
       song at the end. The cutscene is a locked special event -- there is no Esc abort (Esc just flashes an
       ERROR), so it always runs to completion. */
    @Override
    public void step(BiosContext ctx) {
        long now = System.nanoTime();
        long elapsed = cutscene.elapsedMs(now);
        if (elapsed >= cutscene.faceStart && !jinglePlayed) {
            jinglePlayed = true;
            ctx.playMusic("/songs/recovery/intro.ogg"); // close+play+setVolume; the shared audio is now the jingle
            AudioPlayer p = ctx.audio();
            if (p != null) {
                stabs = detectStabs(p.superflux(), p.superfluxFrameMs()); // sync the face to the jingle's hits
            }
        }
        if (cutscene.done(now)) {
            /* During startRhythm's long decode the state is still CUTSCENE (so it still holds the hand-off);
               startRhythm flips state to RHYTHM atomically at the very end, so the hold loop never sees POST
               mid-transition and there is no separate flag to clear here. The render thread freezes on the
               last full-screen-scramble frame for the decode -- reads as a glitchy load into the game. */
            ctx.startRhythm(); // closes the jingle + plays recovery.ogg
        }
    }

    /* The cutscene screen, in order: an opening right-to-left wipe that erases the SETUP screen; a blank
       cleared-screen hold; then the header status line text-scrambles random characters and settles on
       "Unzipping..." with animated loading dots (and a rule under it); then the face animation (blank ->
       noise -> eyes -> noise -> smile) plays while the jingle's stabs hit; then the entire screen scrambles
       into glitch-noise and hands off to the song. The header persists from the wipe until the outro. */
    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        int maxChars = Math.max(40, logicalW / c.charWidth() - 1);
        c.beginFrameShader(logicalW, logicalH);

        long elapsed = cutscene.elapsedMs(System.nanoTime());
        int ruleY = c.lineSpacing() * 2;
        int bodyTop = ruleY + c.lineSpacing();
        int bodyBot = logicalH - c.lineSpacing();

        /* erase the SETUP screen the player was on, right-to-left. The header is deliberately NOT
           drawn yet -- it should appear as the wipe reveals the cleared screen, not pop in on top of the
           content being erased. */
        if (elapsed < CutsceneTimeline.WIPE1) {
            float p = elapsed / (float) CutsceneTimeline.WIPE1;
            ctx.renderSetupUnder();
            c.wipeFromRight(logicalW, logicalH, 0, logicalH, p);
            return;
        }

        /* Hold the fully-cleared (blank) screen a beat after the wipe so the clear reads as "done" before
           anything new appears -- the per-frame clear to bg leaves the screen blank, so just draw nothing. */
        if (elapsed < CutsceneTimeline.SCRAMBLE_START) {
            return;
        }

        /* Outro: once the smile has been held, the ENTIRE screen scrambles into glitch-noise, which then
           hands off to the song (step starts the game at outroEnd). Drawn over everything. */
        if (elapsed >= cutscene.faceEnd) {
            drawFullScreenScramble(c, logicalW, logicalH);
            return;
        }

        /* Header status line: scrambles random characters and decodes into "Unzipping" (left-to-right), then
           the trailing dots animate as a loading indicator; it holds (with the rule under it) through the
           face phase. */
        String head = elapsed < cutscene.settle
                ? scrambleText(UNZIP_WORD, CutsceneTimeline.SCRAMBLE_START, elapsed, CutsceneTimeline.SCRAMBLE_MS)
                : UNZIP_WORD + ".".repeat((int) ((elapsed / 400) % 3) + 1);
        c.text(c.margin(), c.lineSpacing(), head, c.cGold());
        c.text(c.margin(), ruleY, TerminalCanvas.rule(maxChars), c.cFg());

        /* Body: blank until the face window, then the eyes/smile reveal (blank -> noise -> eyes -> noise ->
           smile, synced to the jingle stabs). */
        if (elapsed >= cutscene.faceStart && elapsed < cutscene.faceEnd) {
            drawFaceCentered(c, faceForStabs(ctx, elapsed), bodyTop, bodyBot, logicalW);
        }
    }

    private void drawFaceCentered(TerminalCanvas c, String[] art, int yTop, int yBot, int logicalW) {
        if (art.length == 0) {
            return;
        }
        /* Center + top-align by the SHARED footprint (not this frame's own size) so the eyes sit at the
           same spot in both frames -- the smile just grows downward, the eyes never jump. */
        int across = Math.max(1, logicalW / c.charWidth());
        int x = c.margin() + Math.max(0, (across - faceWidth) / 2) * c.charWidth();
        int rows = Math.max(1, (yBot - yTop) / c.lineSpacing());
        int y = yTop + Math.max(0, (rows - faceHeight) / 2) * c.lineSpacing();
        for (int i = 0; i < art.length; i++) {
            c.text(x, y + i * c.lineSpacing(), art[i], c.cBright());
        }
    }

    /* Fill the entire canvas with per-frame random glitch noise -- the quick "scramble into the game"
       transition at the end of the cutscene. (It also covers the song's decode, which briefly freezes the
       render thread on the last noise frame before the game screen appears.) Render thread only. */
    private void drawFullScreenScramble(TerminalCanvas c, int logicalW, int logicalH) {
        int cols = Math.max(1, logicalW / c.charWidth());
        char[] buf = new char[cols];
        for (int y = 0; y + c.lineSpacing() <= logicalH; y += c.lineSpacing()) {
            for (int i = 0; i < cols; i++) {
                buf[i] = Glitch.glyph();
            }
            c.text(0, y, new String(buf), c.cBright());
        }
    }

    private static int artWidth(String[] art) {
        int w = 0;
        for (String s : art) {
            w = Math.max(w, s.length());
        }
        return w;
    }

    /* The face reveal, synced to the jingle's two stabs (jingle-relative time from the cutscene clock, which
       starts the jingle at cutscene.faceStart and freezes on pause -- NOT AL_SEC_OFFSET, which resets to 0
       once the jingle finishes): completely blank, then a brief glitch-noise burst that resolves into the eyes
       (frame A) on the first stab, then another noise burst that resolves into the smile (frame B) on the last
       stab, held thereafter. So the sequence is always blank -> noise -> eyes -> noise -> smile. */
    private String[] faceForStabs(BiosContext ctx, long elapsed) {
        float jingleSec = Math.max(0L, elapsed - cutscene.faceStart) / 1000f;
        float t0;
        float t1;
        if (stabs.length >= 2) {
            t0 = stabs[0];
            t1 = stabs[stabs.length - 1];
        } else if (stabs.length == 1) {
            t0 = stabs[0];
            t1 = t0 + 0.30f;          // only one stab detected -> reveal the smile a beat after the eyes
        } else {
            t0 = 0.10f;               // no stabs detected -> fixed fallback offsets
            t1 = 0.45f;
        }
        float noise = FACE_NOISE_MS / 1000f;
        /* Clamp the smile reveal into the face window so it always lands with time to hold (guards a future
           jingle whose last stab is late), then keep it after the eyes so blank->noise->eyes->noise->smile
           never collapses. For the tuned ~0.36s stab neither clamp bites. */
        float faceWindowSec = Math.max(0L, cutscene.faceEnd - cutscene.faceStart) / 1000f;
        t1 = Math.min(t1, faceWindowSec - noise - 0.60f);
        t1 = Math.max(t1, t0 + noise + 0.08f);
        if (jingleSec < t0) {
            return EMPTY_ART;                  // completely blank
        }
        if (jingleSec < t0 + noise) {
            return noiseLike(ctx.recoveryFaceA());  // first noise burst, resolving into the eyes
        }
        if (jingleSec < t1) {
            return ctx.recoveryFaceA();        // first frame: eyes
        }
        if (jingleSec < t1 + noise) {
            return noiseLike(ctx.recoveryFaceB());  // second noise burst, resolving into the smile
        }
        return ctx.recoveryFaceB();            // second frame: smile (held to the end)
    }

    /* A glitch-noise version of an art frame: every inked cell becomes a random GLITCH character (spaces stay
       blank), regenerated each call so it shimmers like static, with the SAME footprint as the target frame
       so the reveal into the real frame does not jump. */
    private static String[] noiseLike(String[] art) {
        String[] out = new String[art.length];
        for (int i = 0; i < art.length; i++) {
            String line = art[i];
            StringBuilder sb = new StringBuilder(line.length());
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                sb.append(c == ' ' ? ' ' : Glitch.glyph());
            }
            out[i] = sb.toString();
        }
        return out;
    }

    /* A left-to-right text-decode reveal (the cutscene header "unzipping" flourish): from startMs over durMs
       the label settles left-to-right -- decoded positions show the final character, the rest spin through
       random GLITCH characters every frame -- until the whole label is settled. Render thread only; pure. */
    private String scrambleText(String target, long startMs, long elapsed, long durMs) {
        long t = elapsed - startMs;
        if (t >= durMs) {
            return target;
        }
        float progress = t <= 0 ? 0f : (float) t / durMs;
        int locked = (int) (progress * target.length()); // characters decoded so far, left-to-right
        StringBuilder sb = new StringBuilder(target.length());
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (i < locked || c == ' ') {
                sb.append(c);
            } else {
                sb.append(Glitch.glyph());
            }
        }
        return sb.toString();
    }

    /* Peak-pick a short clip's SuperFlux onset envelope into stab times (seconds) -- the prominent hits the
       face syncs to. The recovery jingle has exactly two clear stabs (~90ms and ~360ms). */
    private static float[] detectStabs(float[] env, double frameMs) {
        if (env == null || env.length < 3 || frameMs <= 0) {
            return new float[0];
        }
        java.util.List<Float> out = new java.util.ArrayList<>();
        int minGap = Math.max(1, (int) Math.round(110.0 / frameMs));
        int last = -minGap;
        for (int h = 1; h < env.length - 1; h++) {
            if (env[h] >= 0.30f && env[h] >= env[h - 1] && env[h] >= env[h + 1] && h - last >= minGap) {
                out.add((float) (h * frameMs / 1000.0));
                last = h;
            }
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }
}
