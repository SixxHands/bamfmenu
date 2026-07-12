package com.retro.bamfmenu.early.bios.modes.rhythm.parts;

import com.retro.bamfmenu.early.core.asset.Art;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.engine.JudgmentArt;
import com.retro.bamfmenu.early.core.rhythm.score.ScoreView;
import com.retro.bamfmenu.early.core.rhythm.panel.LeftPanelAnimation;
import com.retro.bamfmenu.early.core.rhythm.panel.LeftPanelTrack;
import com.retro.bamfmenu.early.core.rhythm.panel.PanelLayout;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.core.rhythm.model.SongDef;

/* The rhythm game VIEW: the highway box + the 4-lane note scroll + the reactive left
   panel + the right-hand score/combo/health HUD + the header (song name + waveform), all drawn THROUGH the
   TerminalCanvas facade, no LWJGL, no game logic. Reads the engine's public note/lane/react state and a
   ScoreView (so it depends on the read-only scoring face, not the mutable ScoringEngine). Moved verbatim out
   of RhythmScreen so the host keeps only lifecycle + input; the editor's audition view can construct a
   renderer over the same model. The SCORE odometer is render-thread-only display state and lives here;
   reset is called per song-start so a replay does not show the previous run's spin.

   Phase B (rhythm UI/FX plan): render is now a LAYOUT pass + a dispatch over the named RhythmUiPart parts.
   Each piece of the highway is its own drawXxx part-unit with a stable, addressable id, so effects (Phase D)
   and the FX chart can target a part by name. This is behavior-PRESERVING: the parts draw exactly
   what the old single method drew, in the same screen positions (the highway is pixel-identical). The count-in
   is an absolute-center OVERLAY, not an FX-target part, so render draws it after the part pass. */
public final class RhythmRenderer {

    private static final String[] LANE_GLYPHS = {"<", "v", "^", ">"};
    private static final float[] EMPTY_WAVE = new float[0];
    private static final String SONG_LABEL = "B-0000B1-ES by Autumonapia";

    /* Scroll-wheel SCORE odometer (render-thread only). */
    private int shownScore;
    private int scoreAnimFrom;
    private int scoreAnimTarget;
    private long scoreAnimUntilNanos;
    private String[][] countArt; // lazy cache of the count-in digit arts (index = digit 0..3)

    /* Reset the SCORE odometer at song start so a replay doesn't show the previous song's spin mid-flight. */
    public void reset() {
        shownScore = 0;
        scoreAnimFrom = 0;
        scoreAnimTarget = 0;
        scoreAnimUntilNanos = 0L;
    }

    /* The highway, drawn as a BIOS-style bordered box that fills the screen (centered) split into three
       columns: left art/info, center the wide 4-lane note highway (color-coded, key boxes that light when
       held, sustains as trails), right the judgment + stats. Controls sit along the bottom (drawn globally).
       render() computes the per-frame geometry once (Layout), bundles the live model (Frame), then dispatches
       every RhythmUiPart in declaration order; the count-in is a centered overlay drawn last. */
    public void render(TerminalCanvas c, BiosContext ctx, RhythmEngine g, ScoreView score, LeftPanelTrack panel) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        c.beginFrameShader(logicalW, logicalH);

        Frame f = new Frame(c, ctx, g, score, panel, computeLayout(c, g));
        for (RhythmUiPart part : RhythmUiPart.values()) {
            draw(part, f);
        }

        if (g.countDigit >= 0) {
            drawCountdown(c, logicalW, logicalH, g.countDigit); // song-start count-in, centered over the playfield (overlay, not a part)
        }
    }

    /* Compute the highway geometry for this frame: the three-column split, the box, and the lane metrics +
       fall speed. Pure function of the canvas + the chart's approach time; every part reads from this. */
    private static Layout computeLayout(TerminalCanvas c, RhythmEngine g) {
        int cw = c.charWidth();
        int ls = c.lineSpacing();
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        int maxChars = Math.max(48, logicalW / cw - 1);

        int third = (maxChars - 4) / 3;
        int leftW = third;
        int rightW = third;
        int centerW = (maxChars - 4) - leftW - rightW;
        int x0 = c.margin();
        int leftX = x0 + cw;
        int div1X = x0 + (1 + leftW) * cw;
        int centerX = div1X + cw;
        int div2X = x0 + (1 + leftW + 1 + centerW) * cw;
        int rightX = div2X + cw;
        int boxRightX = x0 + (1 + leftW + 1 + centerW + 1 + rightW) * cw;

        int boxTop = ls * 5;
        int boxBot = logicalH - ls * 2;

        int laneW = centerW / RhythmEngine.LANES;
        int lanePad = (centerW - laneW * RhythmEngine.LANES) / 2;
        int laneInner = Math.max(2, laneW - 3);
        int playTop = boxTop + ls;
        int hitLineY = boxBot - ls * 2;
        int playH = Math.max(ls, hitLineY - playTop);
        double pxPerMs = playH / (double) Math.max(1, g.approachMs); // per-chart fall speed (smaller approachMs = faster)

        /* Reserve the judgment zone at the top of the right HUD column; the score/combo/health/tally stats start
           below it at hudTop. The tally is a compact 2-row grid (drawTally), so the stats block is only ~9 rows,
           leaving lots of room for a tall judgment zone above; it shrinks gracefully on short windows so the
           stats always fit. */
        int availRows = Math.max(1, (boxBot - playTop) / ls);
        int statsRows = 9; // SCORE(2) + combo lines(3) + health(2) + the 2-row tally grid(2)
        int judgmentRows = Math.max(2, Math.min(16, availRows - statsRows - 1));
        int hudTop = playTop + judgmentRows * ls;

        return new Layout(hudTop, maxChars, x0, leftX, leftW, div1X, centerX, centerW, div2X, rightX, rightW,
                boxRightX, boxTop, boxBot, laneW, lanePad, laneInner, playTop, hitLineY, pxPerMs);
    }

    /* Dispatch one named part. A null part (e.g. RhythmUiPart.byId returned null for an unknown/renamed id) is
       a graceful no-op, so a future FX chart addressing parts by string id can never crash on a stale name. */
    private void draw(RhythmUiPart part, Frame f) {
        if (part == null) {
            return;
        }
        switch (part) {
            case WAVEFORM -> drawHeader(f);
            case BORDER -> drawBorder(f);
            case LANES -> drawLanes(f);
            case HITZONES -> drawHitzones(f);
            case NOTES -> drawNotes(f);
            case LEFTFRAME -> drawLeftFrame(f);
            case JUDGMENT -> drawJudgment(f);
            case SCORE -> drawScore(f);
            case COMBO -> drawCombo(f);
            case HEALTH -> drawHealth(f);
            case TALLY -> drawTally(f);
        }
    }

    /* WAVEFORM: song name + artist (row 1), then a 3-line waveform of the whole song (rows 2-4) with the
       played portion lit, the color split IS the playhead. Delegates to the shared SongHeaderHud so the main
       game and special modes draw the identical widget. */
    private void drawHeader(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        SongDef song = f.ctx().currentSong();
        AudioPlayer audio = f.ctx().audio();
        RhythmEngine g = f.g();
        String label = song != null ? song.label() : SONG_LABEL;
        float[] wf = audio != null ? audio.waveform() : EMPTY_WAVE;
        int durMs = (audio != null && audio.durationSeconds() > 0f)
                ? (int) (audio.durationSeconds() * 1000f) : Math.max(1, g.chartEndMs());
        float playFrac = durMs > 0 ? g.songMs / (float) durMs : 0f;
        SongHeaderHud.render(c, l.x0(), c.lineSpacing(), c.lineSpacing() * 2, l.maxChars(), label, wf, playFrac);
    }

    /* BORDER: the bordered box top/bottom rules + the four vertical dividers framing the three columns. */
    private void drawBorder(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        int border = c.cFg();
        c.text(l.x0(), l.boxTop(), boxRule(l.leftW(), l.centerW(), l.rightW(), true), border);
        c.text(l.x0(), l.boxBot(), boxRule(l.leftW(), l.centerW(), l.rightW(), false), border);
        for (int y = l.boxTop() + c.lineSpacing(); y < l.boxBot(); y += c.lineSpacing()) {
            c.text(l.x0(), y, "│", border);
            c.text(l.div1X(), y, "│", border);
            c.text(l.div2X(), y, "│", border);
            c.text(l.boxRightX(), y, "│", border);
        }
    }

    /* LANES: the per-lane hit-line dash (the playfield floor the notes fall toward). */
    private void drawLanes(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        for (int lane = 0; lane < RhythmEngine.LANES; lane++) {
            int lx = l.centerX() + (l.lanePad() + lane * l.laneW()) * c.charWidth();
            c.text(lx, l.hitLineY() - c.lineSpacing(), "-".repeat(l.laneInner() + 2), c.cDim());
        }
    }

    /* HITZONES: the [<<<] press indicators at the hit line, lit in the lane color while held, dim otherwise. */
    private void drawHitzones(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        RhythmEngine g = f.g();
        for (int lane = 0; lane < RhythmEngine.LANES; lane++) {
            int lx = l.centerX() + (l.lanePad() + lane * l.laneW()) * c.charWidth();
            boolean held = g.laneHeld[lane];
            String inside = held ? LANE_GLYPHS[lane].repeat(l.laneInner()) : TerminalCanvas.center(LANE_GLYPHS[lane], l.laneInner());
            c.text(lx, l.hitLineY(), "[" + inside + "]", held ? Art.LANE_COLORS[lane] : c.cDim());
        }
    }

    /* NOTES: the falling taps + hold capsules. A cleanly hit/completed note vanishes; a missed/dropped one
       lingers GREY (faded) as it falls past the hit line down to the box bottom. */
    private void drawNotes(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        RhythmEngine g = f.g();
        int songMs = g.songMs;
        for (Note n : g.notes) {
            boolean liveHold = n.isHold() && n.headHit && !n.holdResolved;
            if (n.headJudged && !liveHold && !n.faded) {
                continue; // cleanly hit / completed -> vanish; MISSED or DROPPED -> linger GREY (faded) until off
            }
            int lx = l.centerX() + (l.lanePad() + n.lane * l.laneW()) * c.charWidth();
            int color = n.faded ? c.cDim() : Art.LANE_COLORS[n.lane]; // a miss/drop greys out as it falls past
            int ls = c.lineSpacing();
            int botBound = n.faded ? l.boxBot() : l.hitLineY(); // faded notes keep showing past the hit line, down to the box bottom
            int headY = (int) (l.hitLineY() - (n.timeMs - songMs) * l.pxPerMs());
            if (n.isHold()) {
                /* A hold is a bounded CAPSULE: |-----| dash caps at the head (bottom = where you
                   press) and the tail (top = where you release), | rails down the sides, and an up-arrow (^ over a
                   | stem) near the tail = the release/stop cue. ASCII only. Row roles are assigned by index from
                   the head so the arrow stays at the TAIL for ANY length: a long hold just grows hollow rail rows
                   toward the head; a short one collapses to caps (+ arrow if it fits). */
                int tailY = (int) (l.hitLineY() - (n.endMs() - songMs) * l.pxPerMs());
                String cap = "|" + "-".repeat(l.laneInner()) + "|";
                String arrowHead = railRow(l.laneInner(), '^');
                String arrowStem = railRow(l.laneInner(), '|');
                int rowsN = Math.max(1, (headY - tailY) / ls + 1);
                int i = 0;
                for (int y = headY; y >= tailY; y -= ls) {
                    if (y >= l.playTop() && y <= botBound) {
                        String s = (i == 0 || i == rowsN - 1) ? cap
                                : (i == rowsN - 2) ? arrowHead
                                : arrowStem; // continuous center line all the way up to the ^ (a real arrow)
                        c.text(lx, y, s, color);
                    }
                    i++;
                }
            } else if (headY >= l.playTop() && headY <= (n.faded ? botBound : l.hitLineY() + ls)) {
                c.text(lx, headY, "[" + "#".repeat(l.laneInner()) + "]", color); // tap head = where you press
            }
        }
    }

    /* LEFTFRAME: the reactive left panel. Builds the per-frame animation State from the engine (incl. the
       chart-hold spanning now, so the face holds its last frame on a held note) and draws the active clip. */
    private void drawLeftFrame(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        RhythmEngine g = f.g();
        LeftPanelTrack track = f.panel();
        if (track == null) {
            return;
        }
        int songMs = g.songMs;
        LeftPanelAnimation anim = track.active(songMs);
        if (anim == null) {
            return;
        }
        LeftPanelAnimation.State st = new LeftPanelAnimation.State();
        st.songMs = songMs;
        st.reactLane = g.reactLane;
        st.reactChord = g.reactChord;
        st.reactLaneMs = g.reactLaneMs;
        st.reactMissMs = g.reactMissMs;
        st.combo = f.score().combo();
        /* A CHART hold spanning now -> the face holds its last frame for the hold (prefer the active lane). */
        int holdLane = -1;
        for (Note n : g.notes) {
            if (n.isHold() && n.timeMs <= songMs && songMs <= n.endMs()) {
                holdLane = n.lane;
                if (n.lane == g.reactLane) {
                    break;
                }
            }
        }
        st.reactHoldLane = holdLane;
        drawLeftPanel(c, anim, st, l.leftX(), l.leftW(), l.playTop(), l.boxBot());
    }

    /* JUDGMENT: the current grade. If a per-grade ASCII banner exists (/ui/judge/<grade>.txt) it is uniformly
       scaled to FILL a reserved judgment grid (a TWO-ROW-tall zone at the top of the right HUD column) and
       centered, so it never auto-cuts and never sits over the playfield. The grid is sized for two rows: author
       a banner as one row, or as two rows stacked in the .txt, and it scales to fit either way. With no banner
       for that grade it falls back to the old plain text. Shown for 400ms after the last judgment, by grade. */
    private void drawJudgment(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        ScoreView score = f.score();
        if (f.g().songMs - score.lastJudgmentMs() >= 400 || score.lastJudgment().isEmpty()) {
            return;
        }
        String grade = score.lastJudgment();
        int color = judgmentColor(c, grade);
        String[] art = JudgmentArt.render(grade, score.comboMult()); // per-combo-tier art (hit grades; miss/drop/fail un-tiered)
        int ls = c.lineSpacing();
        if (art == null) {
            c.text(l.rightX(), l.playTop() + ls, grade, color); // fallback: plain text at the top of the HUD
            return;
        }
        /* The reserved JUDGMENT grid: top of the right HUD column, from playTop up to the stats (hudTop), minus a
           1-row gap. The stats flow below hudTop. Art is uniformly scaled to fill this + centered. */
        int gridX = l.rightX();
        int gridW = l.rightW() * c.charWidth();
        int gridY = l.playTop();
        int gridH = Math.max(ls, l.hudTop() - l.playTop() - ls);
        c.drawScaledCentered(art, gridX, gridY, gridW, gridH, color);
    }

    /* SCORE: the SCORE label + the animated odometer value. */
    private void drawScore(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        ScoreView score = f.score();
        int ls = c.lineSpacing();
        c.text(l.rightX(), l.hudTop(), "SCORE", c.cDim());
        c.text(l.rightX(), l.hudTop() + ls, animatedScore(score.score()), c.cBright());
    }

    /* COMBO: the per-hit x-mult / +bucket line (under the score) + the running COMBO count and max. */
    private void drawCombo(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        ScoreView score = f.score();
        int ls = c.lineSpacing();
        if (score.comboBucket() > 0) {
            c.text(l.rightX(), l.hudTop() + ls * 2, "x" + score.comboMult() + "  +" + score.comboBucket(), c.cGold());
        }
        c.text(l.rightX(), l.hudTop() + ls * 3, "COMBO " + score.combo() + "x", c.cDim());
        c.text(l.rightX(), l.hudTop() + ls * 4, "max " + score.maxCombo(), c.cFg());
    }

    /* HEALTH: the HEALTH label (gold [SAFE] when no-fail) + the ascii health bar. */
    private void drawHealth(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        ScoreView score = f.score();
        int ls = c.lineSpacing();
        c.text(l.rightX(), l.hudTop() + ls * 5, score.noFail() ? "HEALTH  [SAFE]" : "HEALTH", score.noFail() ? c.cGold() : c.cDim());
        int hp = Math.round(score.healthFraction() * 100);
        c.text(l.rightX(), l.hudTop() + ls * 6, TerminalCanvas.asciiBar(hp, Math.min(l.rightW(), 16)), score.healthFraction() > 0.3f ? c.cFg() : Art.LANE_COLORS[3]);
    }

    /* TALLY: the Perfect/Good/Okay/Bad/Miss judgment counters. */
    private void drawTally(Frame f) {
        TerminalCanvas c = f.c();
        Layout l = f.layout();
        ScoreView score = f.score();
        int ls = c.lineSpacing();
        /* 5 counters in a compact 3-col x 2-row grid (PERF/GOOD/OKAY on top, BAD/MISS below) -> 2 rows instead
           of 5, so the right column has lots more room for the judgment zone above. Labels shrink to single
           letters on a narrow column; each cell is clipped so it never overflows into the next. */
        int cols = 3;
        int cellChars = Math.max(6, l.rightW() / cols);
        boolean wide = cellChars >= 8;
        String[] labels = wide ? new String[]{"PERF", "GOOD", "OKAY", "BAD ", "MISS"}
                               : new String[]{"P", "G", "O", "B", "M"};
        int[] counts = {score.perfect(), score.good(), score.okay(), score.bad(), score.miss()};
        int top = l.hudTop() + ls * 7;
        for (int i = 0; i < counts.length; i++) {
            int x = l.rightX() + (i % cols) * cellChars * c.charWidth();
            int y = top + (i / cols) * ls;
            c.text(x, y, TerminalCanvas.clip(labels[i] + " " + counts[i], cellChars), c.cFg());
        }
    }

    /* Draw the song-start count-in digit in the ABSOLUTE center of the screen. Each digit is its own art file
       (/ui/count/<n>.txt), single-char today, swappable for full ASCII art later; falls back to the literal
       digit if the file is missing, so the count always shows. */
    private void drawCountdown(TerminalCanvas c, int logicalW, int logicalH, int digit) {
        String[] art = countArt(digit);
        int blockChars = 0;
        for (String line : art) {
            blockChars = Math.max(blockChars, line.length());
        }
        int px = (logicalW - blockChars * c.charWidth()) / 2;
        int py = (logicalH - art.length * c.lineSpacing()) / 2;
        for (int i = 0; i < art.length; i++) {
            c.text(px, py + i * c.lineSpacing(), art[i], c.cBright());
        }
    }

    /* Lazily load + cache the count-in digit arts (index = the digit 0..3). A missing/empty file falls back to
       the literal digit so the count still renders. */
    private String[] countArt(int digit) {
        if (digit < 0 || digit > 3) {
            return new String[]{Integer.toString(Math.max(0, digit))};
        }
        if (countArt == null) {
            countArt = new String[4][];
        }
        if (countArt[digit] == null) {
            String[] a = Art.loadArt("/ui/count/" + digit + ".txt");
            countArt[digit] = a.length == 0 ? new String[]{Integer.toString(digit)} : a;
        }
        return countArt[digit];
    }

    /* Render the active left-panel animation inside the left cell, vertically centered + clipped, with the
       miss-shake jitter kept inside the cell. */
    private void drawLeftPanel(TerminalCanvas c, LeftPanelAnimation anim, LeftPanelAnimation.State st, int leftX, int leftW, int yTop, int yBot) {
        String[] art = anim.frame(st);
        if (art == null || art.length == 0 || leftW <= 2) {
            return;
        }
        int color = anim.color(st);
        PanelLayout pl = anim.layout();
        if (pl == null) {
            pl = PanelLayout.defaults();
        }
        int ls = c.lineSpacing();
        int rows = Math.max(1, (yBot - yTop) / ls - 1);
        int artH = Math.min(art.length, rows);
        /* Vertical placement of the art within the cell's rows (default CENTER == the old behavior). */
        int topRow = switch (pl.vAlign()) {
            case TOP -> Math.max(0, pl.padRows());
            case BOTTOM -> Math.max(0, rows - artH - pl.padRows());
            case CENTER -> Math.max(0, (rows - artH) / 2);
        };
        /* Horizontal placement of the art BLOCK (by its widest line) within the cell's columns
           (default LEFT with a 1-col pad == the old behavior; blockW is unused on the LEFT path). */
        int blockW = 0;
        for (int i = 0; i < artH; i++) {
            blockW = Math.max(blockW, art[i] == null ? 0 : art[i].length());
        }
        blockW = Math.min(blockW, leftW);
        int startCol = switch (pl.hAlign()) {
            case LEFT -> Math.max(0, pl.padCols());
            case RIGHT -> Math.max(0, leftW - blockW - pl.padCols());
            case CENTER -> Math.max(0, (leftW - blockW) / 2);
        };
        int sc = Math.max(-startCol, Math.min(1, anim.shakeCols(st)));
        int sr = Math.max(0, Math.min(1, anim.shakeRows(st)));
        int x = leftX + (startCol + sc) * c.charWidth();
        int wMax = Math.max(0, leftW - startCol - Math.max(0, sc));
        int y0 = yTop + topRow * ls;
        for (int i = 0; i < artH; i++) {
            c.text(x, y0 + (i + sr) * ls, TerminalCanvas.clip(art[i], wMax), color);
        }
    }

    /* The SCORE odometer string (spin the changed digits for ~0.45s, then settle). */
    private String animatedScore(int target) {
        long now = System.nanoTime();
        if (target != scoreAnimTarget) {
            scoreAnimFrom = shownScore;
            scoreAnimTarget = target;
            scoreAnimUntilNanos = now + 450_000_000L;
        }
        if (now >= scoreAnimUntilNanos) {
            shownScore = target;
            return Integer.toString(target);
        }
        String t = Integer.toString(target);
        StringBuilder f = new StringBuilder(Integer.toString(Math.max(0, scoreAnimFrom)));
        while (f.length() < t.length()) {
            f.insert(0, '0');
        }
        int d = 0;
        while (d < t.length() && t.charAt(d) == f.charAt(d)) {
            d++;
        }
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            sb.append(i < d ? t.charAt(i) : (char) ('0' + (int) (Math.random() * 10)));
        }
        return sb.toString();
    }

    private int judgmentColor(TerminalCanvas c, String j) {
        if (j.startsWith("PERFECT")) return c.cGold();
        if (j.startsWith("GOOD")) return Art.LANE_COLORS[2];
        if (j.startsWith("OKAY")) return Art.LANE_COLORS[1];
        if (j.startsWith("BAD")) return Art.LANE_COLORS[0];
        if (j.startsWith("MISS") || j.startsWith("DROP")) return Art.LANE_COLORS[3];
        return c.cFg();
    }

    /* A hold-capsule interior row: | <centered char> | across the lane inner width (e.g. "|  ^  |"). */
    private static String railRow(int inner, char mid) {
        int left = (inner - 1) / 2;
        int right = Math.max(0, inner - 1 - left);
        return "|" + " ".repeat(Math.max(0, left)) + mid + " ".repeat(right) + "|";
    }

    private static String boxRule(int leftW, int centerW, int rightW, boolean top) {
        String l = top ? "┌" : "└";
        String j = top ? "┬" : "┴";
        String r = top ? "┐" : "┘";
        return l + "─".repeat(leftW) + j + "─".repeat(centerW) + j + "─".repeat(rightW) + r;
    }

    /* Per-frame highway geometry, computed once by computeLayout and read by every part. */
    private record Layout(int hudTop, int maxChars, int x0, int leftX, int leftW, int div1X, int centerX, int centerW,
                          int div2X, int rightX, int rightW, int boxRightX, int boxTop, int boxBot,
                          int laneW, int lanePad, int laneInner, int playTop, int hitLineY, double pxPerMs) {
    }

    /* The live model bundle a part draws from: the canvas facade, the BIOS context (song/audio), the engine,
       the read-only score, the left-panel track, and the frame's geometry. */
    private record Frame(TerminalCanvas c, BiosContext ctx, RhythmEngine g, ScoreView score,
                         LeftPanelTrack panel, Layout layout) {
    }
}
