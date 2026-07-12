package com.retro.bamfmenu.early.bios.modes.special;

import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.special.SpecialSession;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.core.rhythm.model.SongDef;

import java.util.Queue;

/* Mode.SPECIAL host: a song's optional distinct gameplay session. The
   session is pure logic that RETURNS lines, so this screen keeps zero GL of its own; it
   plays the song's audio for the clock, drains the timestamped input (ESC opens the universal pause, the
   rest goes to the session), steps the session, and draws its returned lines centered. Extracted verbatim
   from the provider's start/step/exit/drawSpecial; behavior unchanged.

   The input queue is the provider-owned `specialInput` (onKey enqueues on the main thread; this drains it
   on the render thread), shared by reference so the pause overlay's defensive clear still reaches it. */
public final class SpecialScreen implements Screen {

    /* GLFW key/action codes, duplicated so this class stays free of the LWJGL import (same convention as
       TetrisGame). */
    private static final int KEY_ESCAPE = 256;
    private static final int ACTION_PRESS = 1;

    private final Queue<long[]> input;
    private SpecialSession session;

    public SpecialScreen(Queue<long[]> input) {
        this.input = input;
    }

    @Override
    public Mode mode() {
        return Mode.SPECIAL;
    }

    /* Launch the current song's SPECIAL mode: build + init the session over the song's freshly-played
       (paused-at-~0) audio, arm the lead-in, then enterMode(SPECIAL). Falls back to SETUP if the song has
       no special or its audio is unreadable. */
    @Override
    public void onEnter(BiosContext ctx) {
        input.clear();
        SongDef song = ctx.currentSong();
        SpecialSession s = song != null ? song.newSpecial() : null;
        if (s == null) {
            exit(ctx); // nothing to launch
            return;
        }
        AudioPlayer p = ctx.startSongAudio(song);
        if (p == null) {
            ctx.setNotice("Song data unreadable.");
            exit(ctx);
            return;
        }
        s.init(song, p);
        session = s;
        ctx.armLeadIn(); // settle on the SPECIAL screen for a beat, then start (shared with RHYTHM)
        ctx.enterMode(Mode.SPECIAL); // single atomic state write; sets the 60fps period
    }

    /* Read the audio clock, drain timestamped input (ESC -> universal pause, the rest to the session),
       advance the session, exit when it reports done. */
    @Override
    public void step(BiosContext ctx) {
        SpecialSession s = session;
        if (s == null) {
            exit(ctx);
            return;
        }
        AudioPlayer a = ctx.audio();
        /* Lead-in: show the SPECIAL screen for a beat with the song frozen at ~0; swallow input so an early
           key is dropped, then holdingLeadIn() resumes playback at the deadline. */
        if (ctx.holdingLeadIn()) {
            input.clear();
            return;
        }
        int songMs = a != null ? (int) (a.positionSeconds() * 1000f) : 0;
        long[] ev;
        while ((ev = input.poll()) != null) {
            if ((int) ev[1] == ACTION_PRESS && (int) ev[0] == KEY_ESCAPE) {
                ctx.openPauseMenu(); // universal pause (Resume/Quit), not a straight quit
                return;
            }
            s.handleKey(ev);
        }
        s.step(songMs);
        if (s.isDone()) {
            exit(ctx);
        }
    }

    /* The song header + rule, then the session's returned lines (centered). The session returns lines, so
       all GL stays in the canvas; any custom special plugs in without a renderer change. */
    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        SpecialSession s = session;
        if (s == null) {
            return;
        }
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        int maxChars = Math.max(40, logicalW / c.charWidth() - 1);
        c.beginFrameShader(logicalW, logicalH);

        SongDef song = ctx.currentSong();
        c.text(c.margin(), c.lineSpacing(), TerminalCanvas.clip(song != null ? song.label() : "", maxChars), c.cGold());
        c.text(c.margin(), c.lineSpacing() * 2, TerminalCanvas.rule(maxChars), c.cFg());

        int cols = Math.max(1, logicalW / c.charWidth());
        int rows = Math.max(1, logicalH / c.lineSpacing());
        String[] lines = s.render(cols, rows);
        int y0 = c.lineSpacing() * 5;
        if (lines != null) {
            for (int i = 0; i < lines.length && y0 + i * c.lineSpacing() < logicalH - c.lineSpacing() * 2; i++) {
                String ln = TerminalCanvas.clip(lines[i], maxChars);
                int x = c.margin() + Math.max(0, (cols - ln.length()) / 2) * c.charWidth(); // center short lines
                c.text(x, y0 + i * c.lineSpacing(), ln, c.cBright());
            }
        }
        /* the bottom control bar (ESC Pause) is drawn globally by the provider's drawControlBar */
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return "ESC  Pause";
    }

    /* The host calls onExit when the pause menu's Quit leaves SPECIAL; route it to the same teardown. */
    @Override
    public void onExit(BiosContext ctx) {
        exit(ctx);
    }

    /* Leave SPECIAL back to BIOS SETUP: free audio + the session, clear the lead-in, then enterMode(SETUP). */
    private void exit(BiosContext ctx) {
        ctx.closeAudio();
        input.clear();
        session = null;
        ctx.clearLeadIn();
        ctx.enterMode(Mode.SETUP);
    }
}
