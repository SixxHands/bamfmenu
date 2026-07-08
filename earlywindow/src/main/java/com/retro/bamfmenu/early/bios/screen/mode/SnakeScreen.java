package com.retro.bamfmenu.early.bios.screen.mode;

import com.retro.bamfmenu.early.bios.Mode;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.core.asset.Glitch;
import com.retro.bamfmenu.early.core.snake.SnakeGame;

import java.util.Queue;

/* Mode.SNAKE host: the HIDDEN Snake game (revealed by typing the "snake" secret in the Safe-For-Retro box). The
   self-contained-game pattern, like TetrisScreen: owns a SnakeGame, drains the provider-owned input queue on the
   render thread, advances on its own move-tick timer (decoupled from the frame rate), and draws on the terminal.
   The signature touch (owner's ask): the snake body is rendered with the recovery cutscene's GLITCH shimmer -- each
   segment is re-rolled random `Glitch` symbols every frame -- so the snake reads as living static. The food + grid
   stay solid. No audio, no high-score persistence (a pure Easter egg); ESC backs out to BIOS. */
public final class SnakeScreen implements Screen {

    private static final int KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_KP_ENTER = 335;

    private final Queue<Integer> input;
    private SnakeGame game;
    private long nextMoveNanos;

    public SnakeScreen(Queue<Integer> input) {
        this.input = input;
    }

    @Override
    public Mode mode() {
        return Mode.SNAKE;
    }

    @Override
    public void onEnter(BiosContext ctx) {
        input.clear();
        game = new SnakeGame();
        nextMoveNanos = System.nanoTime() + moveIntervalNanos();
        ctx.enterMode(Mode.SNAKE);
    }

    @Override
    public void onExit(BiosContext ctx) {
        exit(ctx);
    }

    @Override
    public void step(BiosContext ctx) {
        SnakeGame g = game;
        if (g == null) {
            exit(ctx);
            return;
        }
        Integer k;
        while ((k = input.poll()) != null) {
            if (k == KEY_ESCAPE) {
                exit(ctx); // ESC always backs out to BIOS (no pause overlay for the Easter egg)
                return;
            }
            if (g.gameOver) {
                if (k == KEY_ENTER || k == KEY_KP_ENTER) {
                    g.reset();
                    nextMoveNanos = System.nanoTime() + moveIntervalNanos();
                }
            } else {
                g.handleDir(k); // arrows steer; everything else is ignored by the game
            }
        }
        long now = System.nanoTime();
        if (!g.gameOver && now >= nextMoveNanos) {
            g.tick();
            nextMoveNanos = now + moveIntervalNanos();
        }
    }

    /* The move cadence: starts ~130ms/cell and eases faster as you score, floored so it stays playable. The render
       runs every frame (for the glitch shimmer); the snake only MOVES on this slower timer. */
    private long moveIntervalNanos() {
        SnakeGame g = game;
        int ms = 130 - (g != null ? g.score * 4 : 0);
        return Math.max(70, ms) * 1_000_000L;
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return "<- -> ^ v  Move      ENTER  Retry      ESC  Back";
    }

    /* Leave Snake back to BIOS SETUP, landing on the "Safe For Retro Mode" row (index 2) where the secret box lives. */
    private void exit(BiosContext ctx) {
        int score = game != null ? game.score : 0;
        game = null;
        input.clear();
        ctx.exitToSetup(2, "Snake: " + score);
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        SnakeGame g = game;
        if (g == null) {
            return; // exit ran earlier this frame; the cleared background is already correct
        }
        int needRows = SnakeGame.ROWS + 7; // grid box (ROWS+2) + title + rule + score + margins
        int byHeight = (int) Math.ceil((double) needRows * c.lineSpacing() * c.fbWidth() / Math.max(1, c.fbHeight()));
        int logicalW = Math.max(c.logicalW(), byHeight);
        int logicalH = Math.max(1, logicalW * c.fbHeight() / Math.max(1, c.fbWidth()));
        int across = Math.max(1, logicalW / c.charWidth());

        c.beginFrameShader(logicalW, logicalH);

        int border = c.cFg();
        int fieldChars = SnakeGame.COLS * 2 + 2; // "|" + COLS cells of 2 chars + "|"
        int blockX = Math.max(c.margin(), (across - fieldChars) / 2 * c.charWidth());
        int titleY = c.lineSpacing();
        c.text(blockX, titleY, "SNAKE", c.cGold());
        c.text(blockX, titleY + c.lineSpacing(), TerminalCanvas.rule(fieldChars), border);

        int gridTop = titleY + c.lineSpacing() * 3;
        int innerX = blockX + c.charWidth();
        String hbar = "+" + "-".repeat(SnakeGame.COLS * 2) + "+";
        c.text(blockX, gridTop, hbar, border);

        // base grid: each row is "|" + COLS dim " ." cells + "|"
        StringBuilder empty = new StringBuilder(fieldChars);
        empty.append('|');
        for (int col = 0; col < SnakeGame.COLS; col++) {
            empty.append(" .");
        }
        empty.append('|');
        String emptyRow = empty.toString();
        for (int r = 0; r < SnakeGame.ROWS; r++) {
            c.text(blockX, gridTop + (r + 1) * c.lineSpacing(), emptyRow, c.cDim());
        }
        c.text(blockX, gridTop + (SnakeGame.ROWS + 1) * c.lineSpacing(), hbar, border);

        // food: a solid gold marker
        c.text(innerX + g.foodC * 2 * c.charWidth(), gridTop + (g.foodR + 1) * c.lineSpacing(), "()", c.cGold());

        // snake: each segment is 2 fresh GLITCH symbols (re-rolled every frame -> living static); head gold, body bright
        boolean head = true;
        for (int[] cell : g.body) {
            int x = innerX + cell[1] * 2 * c.charWidth();
            int y = gridTop + (cell[0] + 1) * c.lineSpacing();
            c.text(x, y, Glitch.noise(2), head ? c.cGold() : c.cBright());
            head = false;
        }

        int statY = gridTop + (SnakeGame.ROWS + 3) * c.lineSpacing();
        c.text(blockX, statY, "SCORE " + g.score + "    LENGTH " + g.body.size(), c.cDim());

        if (g.gameOver) {
            c.popup(logicalW, logicalH,
                    new String[]{"", "GAME OVER", "Score: " + g.score, "", "[ENTER] Retry    [ESC] Back", ""},
                    new int[]{c.cBright(), c.cGold(), c.cBright(), c.cBright(), c.cFg(), c.cBright()});
        }
    }
}
