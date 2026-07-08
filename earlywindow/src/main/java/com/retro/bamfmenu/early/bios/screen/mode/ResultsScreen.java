package com.retro.bamfmenu.early.bios.screen.mode;

import com.retro.bamfmenu.early.core.asset.Art;
import com.retro.bamfmenu.early.bios.Mode;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

/* Mode.RESULTS host -- a song's end card (score + grade ranking + status), with Retry/Play-Again and an
   explicit back-to-BIOS (no auto-bounce). RhythmScreen banks the run then hands the captured numbers here
   via ctx.showResults(); this screen only displays + routes its two keys back through ctx (retry replays the
   same song+difficulty, ESC returns to BIOS). Extracted verbatim from the provider;
   behavior preserved. Pure logic + canvas drawing -- no GL of its own, no LWJGL imports (GLFW key codes
   duplicated as ints, the TetrisGame convention). */
public final class ResultsScreen implements Screen {

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;

    private final java.util.Queue<Integer> input;
    private boolean completed; // true = cleared, false = game-over (drives the label + Retry vs Play Again)
    private int score;
    private int grade;         // perfect-% for the ranking, or -1 = no grade (game-over / unscored)
    private boolean newBest;

    public ResultsScreen(java.util.Queue<Integer> input) {
        this.input = input;
    }

    @Override
    public Mode mode() {
        return Mode.RESULTS;
    }

    /* Capture the finished run's numbers for display + clear stale input. Called by ctx.showResults() (the
       rhythm<->results hand-off), which then closes the audio + enterMode(RESULTS). */
    public void show(boolean completed, int score, int grade, boolean newBest) {
        this.completed = completed;
        this.score = score;
        this.grade = grade;
        this.newBest = newBest;
        input.clear();
    }

    /* One RESULTS step: ENTER retries/replays the same song+difficulty, ESC returns to BIOS. */
    @Override
    public void step(BiosContext ctx) {
        Integer key;
        while ((key = input.poll()) != null) {
            if (key == KEY_ENTER || key == KEY_KP_ENTER) {
                ctx.startRhythm(); // same currentSong + currentDifficulty, from the top
                return;
            }
            if (key == KEY_ESCAPE) {
                ctx.exitRhythm(); // explicit back to BIOS
                return;
            }
        }
    }

    /* The RESULTS end card: song header + rule, then a centered status / score / rank (grade) block, plus a
       NEW BEST line. Minimal by design; the control bar shows Retry/Play-Again + Back to BIOS. */
    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        int maxChars = Math.max(40, logicalW / c.charWidth() - 1);
        int cols = Math.max(1, logicalW / c.charWidth());
        c.beginFrameShader(logicalW, logicalH);
        String label = ctx.currentSong() != null ? ctx.currentSong().label() : "";
        c.text(c.margin(), c.lineSpacing(), TerminalCanvas.clip(label, maxChars), c.cGold());
        c.text(c.margin(), c.lineSpacing() * 2, TerminalCanvas.rule(maxChars), c.cFg());
        int y = c.lineSpacing() * 6;
        drawCentered(c, completed ? "SONG CLEAR" : "GAME OVER", y, cols,
                completed ? c.cGold() : Art.LANE_COLORS[3]);
        drawCentered(c, "SCORE   " + score, y + c.lineSpacing() * 2, cols, c.cBright());
        String rank = grade >= 0
                ? ctx.gradeLetter(grade) + "    " + grade + "% perfect"
                : "?    (incomplete run)";
        drawCentered(c, "RANK    " + rank, y + c.lineSpacing() * 3, cols, grade >= 0 ? c.cBright() : c.cFg());
        if (newBest) {
            drawCentered(c, "- NEW BEST -", y + c.lineSpacing() * 5, cols, c.cGold());
        }
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return (completed ? "ENTER  Play Again" : "ENTER  Retry")
                + "      ESC  Back to BIOS      F2  Volume";
    }

    /* Draw a string centered across `cols` columns at row y (logical px). */
    private void drawCentered(TerminalCanvas c, String s, int y, int cols, int color) {
        int x = c.margin() + Math.max(0, (cols - s.length()) / 2) * c.charWidth();
        c.text(x, y, s, color);
    }
}
