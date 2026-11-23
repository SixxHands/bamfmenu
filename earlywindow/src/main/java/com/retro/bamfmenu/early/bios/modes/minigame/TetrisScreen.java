package com.retro.bamfmenu.early.bios.modes.minigame;

import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.tetris.TetrisGame;
import com.retro.bamfmenu.early.core.tetris.TetrisPlaylist;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.Random;

/* Mode.TETRIS host ("Enable Mechanics"): the self-contained terminal Tetris cluster, extracted verbatim
   from the provider's start/step/exit/commitHighScore/drawTetris. Owns the TetrisGame
   instance; the input queue is the provider-owned tetrisInput (onKey enqueues on the main thread, this
   drains on the render thread, shared by reference so the pause overlay's clear still reaches it). The high
   score persists through BiosContext (it is part of "the cache"). Tetris is gravity-tick-based, not
   audio-synced, so it just starts the theme and never reads the clock. */
public final class TetrisScreen implements Screen {

    /* GLFW key code, duplicated so this class stays free of the LWJGL import (same convention as TetrisGame). */
    private static final int KEY_ESCAPE = 256;

    private final Queue<Integer> input;
    private TetrisGame game;

    /* Background-music JUKEBOX for the Tetris game (separate from the Mechanics rhythm chart): a DATA-DRIVEN
       playlist of /songs/tetris<N>.ogg tracks. Plays a random one; when it ends, waits a random 5-15s, then
       plays a DIFFERENT random one. Search is for tetris<number>.ogg only; drop in more (any "tetrisN.ogg")
       and they join automatically; non-ogg (mp3) is ignored since the engine decodes OGG only. */
    private static final long GAP_MIN_MS = 5_000;
    private static final long GAP_MAX_MS = 15_000;
    private final Random rng = new Random();
    private List<String> playlist = List.of();
    private String currentTrack;
    private boolean confirmedPlaying;   // the current track was observed actually playing (so we can detect its end)
    private long resumeAtNanos;         // >0 = in the silent gap; start the next track at this wall-clock time

    public TetrisScreen(Queue<Integer> input) {
        this.input = input;
    }

    @Override
    public Mode mode() {
        return Mode.TETRIS;
    }

    @Override
    public void onEnter(BiosContext ctx) {
        input.clear();
        game = new TetrisGame();
        resumeAtNanos = 0;
        confirmedPlaying = false;
        String bamfos = ctx.bamfosDir();
        Path tetrisDir = (bamfos == null || bamfos.isBlank()) ? null : Path.of(bamfos).resolve("tetris");
        playlist = TetrisPlaylist.discover(tetrisDir);
        System.err.println("[bamfmenu] tetris music: " + playlist.size() + " track(s)");
        if (!playlist.isEmpty()) {
            startTrack(ctx, pickTrack());
        } else {
            ctx.playMusic("/songs/tetris1.ogg");
        }
        ctx.enterMode(Mode.TETRIS); // single atomic state write; sets the 50ms period
    }

    /* Play `track` now and arm end-detection for it. */
    private void startTrack(BiosContext ctx, String track) {
        currentTrack = track;
        confirmedPlaying = false;
        resumeAtNanos = 0;
        ctx.playMusic(track);
    }

    /* A random track, different from the one currently playing when there's more than one. */
    private String pickTrack() {
        if (playlist.isEmpty()) {
            return null;
        }
        if (playlist.size() == 1) {
            return playlist.get(0);
        }
        String t;
        do {
            t = playlist.get(rng.nextInt(playlist.size()));
        } while (t.equals(currentTrack));
        return t;
    }

    /* Per-frame playlist tick: when the current track finishes, wait a random 5-15s, then play a different one.
       Only active when a tetris<N>.ogg playlist was found (else the fallback theme just plays as before). */
    private void updateMusic(BiosContext ctx) {
        if (playlist.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (resumeAtNanos > 0) {
            if (now >= resumeAtNanos) {
                startTrack(ctx, pickTrack());
            }
            return;
        }
        AudioPlayer a = ctx.audio();
        if (a != null && a.isPlaying()) {
            confirmedPlaying = true;
        } else if (confirmedPlaying) {
            // the track finished -> a random silent gap, then a different track
            long gapMs = GAP_MIN_MS + (long) (rng.nextDouble() * (GAP_MAX_MS - GAP_MIN_MS));
            resumeAtNanos = now + gapMs * 1_000_000L;
            confirmedPlaying = false;
        }
    }

    /* Drain the queued input, honor a quit signal, otherwise tick gravity and bank any new high score. All
       TetrisGame mutation happens here, on the render thread. */
    @Override
    public void step(BiosContext ctx) {
        TetrisGame g = game;
        if (g == null) {
            exit(ctx);
            return;
        }
        updateMusic(ctx); // advance the background playlist (random track -> end -> 5-15s gap -> different track)
        boolean quit = false;
        Integer k;
        while ((k = input.poll()) != null) {
            if (k == KEY_ESCAPE && g.isPlaying()) {
                ctx.openPauseMenu(); // pause ONLY during play; at game-over ESC falls through to handleKey -> quit
                return;
            }
            if (g.handleKey(k) == TetrisGame.SIGNAL_QUIT_TO_BIOS) {
                quit = true; // game-over ENTER/ESC -> back to BIOS
            }
        }
        if (quit) {
            exit(ctx);
            return;
        }
        if (g.isPlaying()) {
            g.tick();
        }
        commitHighScore(ctx);
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return "<- -> Move   UP Rotate   DOWN Soft   SPACE Drop   ESC Pause";
    }

    @Override
    public void onExit(BiosContext ctx) {
        exit(ctx);
    }

    /* Persist a new high score the moment it is set (cheap, rare) so a force-closed launcher cannot lose it. */
    private void commitHighScore(BiosContext ctx) {
        TetrisGame g = game;
        if (g != null && g.score > ctx.tetrisHighScore()) {
            ctx.setTetrisHighScore(g.score);
            ctx.save();
        }
    }

    /* Leave the game and return to BIOS SETUP on the Enable Mechanics row. Commit the score + free audio +
       the game first; exitToSetup sets the landing row/notice (provider-owned SETUP-nav) and flips state. */
    private void exit(BiosContext ctx) {
        commitHighScore(ctx);
        ctx.closeAudio();
        game = null;
        input.clear();
        ctx.exitToSetup(3, "High score: " + ctx.tetrisHighScore()); // 3 = Enable Mechanics
    }

    /* The game screen: a compact title + rule, the bordered playfield (locked cells in one pass, the
       falling piece overlaid in gold), a side panel, and the pause / game-over popups. The logical canvas
       is sized so the 22-row field always fits vertically whatever the window aspect. Colors follow the
       active theme. Ported verbatim from the provider's drawTetris. */
    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        TetrisGame g = game;
        if (g == null) {
            return; // exit ran earlier this frame; the cleared background is already correct
        }

        int needRows = TetrisGame.ROWS + 8; // field box (ROWS+2) + title + rule + legend + margins
        int byHeight = (int) Math.ceil((double) needRows * c.lineSpacing() * c.fbWidth() / Math.max(1, c.fbHeight()));
        int logicalW = Math.max(c.logicalW(), byHeight);
        int logicalH = Math.max(1, logicalW * c.fbHeight() / Math.max(1, c.fbWidth()));
        int across = Math.max(1, logicalW / c.charWidth());

        c.beginFrameShader(logicalW, logicalH);

        int fieldChars = TetrisGame.COLS * 2 + 2; // "|" + 10 cells of "[]" + "|"
        int panelChars = 22;
        int blockChars = fieldChars + 3 + panelChars;
        int blockX = Math.max(c.margin(), (across - blockChars) / 2 * c.charWidth());

        int border = c.cFg();
        int titleY = c.lineSpacing();
        c.text(blockX, titleY, "TETRIS", c.cGold()); // the deadpan reveal: "Enable Mechanics" was Tetris all along
        c.text(blockX, titleY + c.lineSpacing(), TerminalCanvas.rule(blockChars), border);

        int fieldTop = titleY + c.lineSpacing() * 3;
        int fieldX = blockX;
        int innerX = fieldX + c.charWidth();
        String hbar = "+" + "-".repeat(TetrisGame.COLS * 2) + "+";
        c.text(fieldX, fieldTop, hbar, border);

        /* Mark the active-piece cells so the base pass leaves them blank; we overlay them in gold next. */
        boolean[][] active = new boolean[TetrisGame.ROWS][TetrisGame.COLS];
        boolean showActive = g.isPlaying() || g.paused;
        if (showActive) {
            for (int[] cell : g.activeCells()) {
                int r = cell[0];
                int col = cell[1];
                if (r >= 0 && r < TetrisGame.ROWS && col >= 0 && col < TetrisGame.COLS) {
                    active[r][col] = true;
                }
            }
        }

        for (int r = 0; r < TetrisGame.ROWS; r++) {
            int ry = fieldTop + (r + 1) * c.lineSpacing();
            StringBuilder row = new StringBuilder(fieldChars);
            row.append('|');
            for (int col = 0; col < TetrisGame.COLS; col++) {
                if (active[r][col]) {
                    row.append("  ");
                } else if (g.cells[r][col] != 0) {
                    row.append("[]");
                } else {
                    row.append(" .");
                }
            }
            row.append('|');
            c.text(fieldX, ry, row.toString(), c.cFg());
        }
        c.text(fieldX, fieldTop + (TetrisGame.ROWS + 1) * c.lineSpacing(), hbar, border);

        if (showActive) {
            for (int[] cell : g.activeCells()) {
                int r = cell[0];
                int col = cell[1];
                if (r >= 0 && r < TetrisGame.ROWS && col >= 0 && col < TetrisGame.COLS) {
                    c.text(innerX + col * 2 * c.charWidth(), fieldTop + (r + 1) * c.lineSpacing(), "[]", c.cGold());
                }
            }
        }

        /* Side panel: stats, the NEXT preview, and the high score. */
        int panelX = fieldX + (fieldChars + 3) * c.charWidth();
        int line = fieldTop + c.lineSpacing();
        line = drawPanelStat(c, panelX, line, "SCORE", g.score);
        line = drawPanelStat(c, panelX, line, "LEVEL", g.level);
        line = drawPanelStat(c, panelX, line, "LINES", g.lines);
        line = drawPanelStat(c, panelX, line, "HIGH", ctx.tetrisHighScore());
        c.text(panelX, line, "NEXT", c.cDim());
        line += c.lineSpacing();
        int[][] preview = TetrisGame.shape(g.next, 0);
        for (int pr = 0; pr < 4; pr++) {
            StringBuilder prow = new StringBuilder(8);
            for (int pc = 0; pc < 4; pc++) {
                boolean on = false;
                for (int[] cell : preview) {
                    if (cell[0] == pr && cell[1] == pc) {
                        on = true;
                        break;
                    }
                }
                prow.append(on ? "[]" : "  ");
            }
            c.text(panelX, line, prow.toString(), c.cGold());
            line += c.lineSpacing();
        }

        /* the bottom control bar (Tetris controls) is drawn globally by the provider's drawControlBar */

        if (g.paused) {
            drawPausePopup(c, logicalW, logicalH, g);
        } else if (g.gameOver) {
            drawGameOverPopup(c, logicalW, logicalH, g);
        }
    }

    private int drawPanelStat(TerminalCanvas c, int x, int y, String label, int value) {
        c.text(x, y, label, c.cDim());
        c.text(x, y + c.lineSpacing(), Integer.toString(value), c.cBright());
        return y + c.lineSpacing() * 2 + c.lineSpacing() / 2; // value row + a half-row gap before the next stat
    }

    /* The ESC "are you sure you'll lose your progress" prompt: No (resume) and Yes (quit) on their own
       lines so the per-line color array can highlight the arrow-selected one. No is the default. */
    private void drawPausePopup(TerminalCanvas c, int logicalW, int logicalH, TetrisGame g) {
        int no = g.pauseSelYes ? c.cDim() : c.cGold();
        int yes = g.pauseSelYes ? c.cGold() : c.cDim();
        c.popup(logicalW, logicalH,
                new String[]{"", "Quit MECHANICS?", "You will lose all progress.", "", "No", "Yes"},
                new int[]{c.cBright(), c.cBright(), c.cFg(), c.cBright(), no, yes});
    }

    private void drawGameOverPopup(TerminalCanvas c, int logicalW, int logicalH, TetrisGame g) {
        c.popup(logicalW, logicalH,
                new String[]{"GAME OVER", "Score: " + g.score, "", "[ENTER] Back to BIOS"},
                new int[]{c.cGold(), c.cBright(), c.cBright(), c.cFg()});
    }
}
