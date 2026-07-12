package com.retro.bamfmenu.early.bios.modes.editor.view;

import com.retro.bamfmenu.early.bios.modes.editor.EditorScreen;
import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.core.asset.Art;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.editor.EditChart;
import com.retro.bamfmenu.early.core.rhythm.editor.TimeMap;
import com.retro.bamfmenu.early.core.rhythm.editor.TimingGrid;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.model.Difficulty;
import com.retro.bamfmenu.early.core.rhythm.model.SongDef;

import java.util.Queue;

/* The horizontal TIMELINE editor (the third + bulkiest of the editor's views, extracted from EditorScreen so no one
   class owns all three screens). Time flows L->R; 4 lanes stack top->bottom; a graded-ASCII waveform overview rides
   the top as a nav minimap. Owns ALL the timeline state + input/render; the parent EditorScreen stays the Screen +
   the song/difficulty browser and OWNS the active `view`. The parent drives this via open()/resumeAfterTest()/step()
   /render()/controlHints(); this view signals "done editing" back through parent.returnToDiffBrowser(). Keyboard
   codes are hard-coded so the class stays free of the LWJGL import (the TetrisScreen/EditorScreen convention). */
public final class TimelineEditorView {

    private static final int KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_SPACE = 32, KEY_S = 83, KEY_G = 71, KEY_Q = 81, KEY_W = 87;
    private static final int KEY_P = 80, KEY_O = 79;
    private static final int KEY_9 = 57, KEY_0 = 48; // fall-speed slower / faster
    private static final int KEY_COMMA = 44, KEY_PERIOD = 46, KEY_SEMICOLON = 59, KEY_APOSTROPHE = 39;
    private static final int KEY_HOME = 268, KEY_END = 269, KEY_DELETE = 261, KEY_BACKSPACE = 259;
    private static final int KEY_LEFT_BRACKET = 91, KEY_RIGHT_BRACKET = 93, KEY_MINUS = 45, KEY_EQUAL = 61;
    private static final int KEY_RIGHT = 262, KEY_LEFT = 263, KEY_DOWN = 264, KEY_UP = 265;
    private static final int MOD_SHIFT = 0x0001;
    private static final int GLFW_PRESS = 1, GLFW_REPEAT = 2;

    private static final String[] LANE_GLYPHS = {"<", "v", "^", ">"};
    private static final int WAVE_ROWS = 3;
    private static final int P_NONE = 0, P_EXIT = 1;

    private final EditorScreen parent;
    private final Queue<int[]> keyIn;
    private final Queue<double[]> mouseIn;
    private final Queue<double[]> scrollIn;

    private final TimeMap map = new TimeMap();
    private TimingGrid grid = new TimingGrid(120, 0);
    private SongDef song;
    private Difficulty diff = Difficulty.NORMAL;
    private EditChart chart;
    private int cursorMs;
    private String notice = "";
    private Note selected;
    private boolean dragging;
    private Note dragNote;
    private int viewLaneTop = 80, viewBandH = 60, viewLaneBottom = 320, viewCharW = 10;
    private int viewWaveTop = 30, viewWaveBottom = 110;
    private boolean overviewDragging;
    private int pending = P_NONE;
    private int songDurationMs;
    private float[] wave = new float[0];
    private boolean previewing;
    private int playheadMs;

    public TimelineEditorView(EditorScreen parent, Queue<int[]> keyIn, Queue<double[]> mouseIn, Queue<double[]> scrollIn) {
        this.parent = parent;
        this.keyIn = keyIn;
        this.mouseIn = mouseIn;
        this.scrollIn = scrollIn;
    }

    public String controlHints() {
        return "ARROWS place   ,/. cursor   SPACE audio   P/O test   [ ] snap " + grid.divisorLabel() + "   9/0 fall   S save   ESC back";
    }

    /* Open the given song+difficulty in the timeline: load the chart (external -> bundled -> empty), its grid +
       audition audio, and reset the view. The parent does the fork/normalize + switches its `view` to EDIT. */
    public void open(BiosContext ctx, SongDef target, Difficulty d) {
        song = target;
        diff = d;
        ctx.setCurrentSong(song);
        String text = ctx.loadChartText(song.id(), diff.token);
        chart = EditChart.fromText(song.id(), diff.token, text, song.bpm());
        grid = new TimingGrid(chart.bpm, 0);
        cursorMs = 0;
        map.viewStartMs = -1000;
        map.pxPerMs = 0.12;
        selected = null;
        dragging = false;
        dragNote = null;
        overviewDragging = false;
        previewing = false;
        playheadMs = 0;
        pending = P_NONE;
        AudioPlayer a = ctx.startSongAudio(song); // decodes + pauses at 0 (silent until Space)
        songDurationMs = a != null ? (int) (a.durationSeconds() * 1000) : 0;
        wave = a != null ? a.waveform() : new float[0];
        notice = (text == null || chart.count() == 0) ? "New " + diff.label + " chart" : "Loaded " + chart.count() + " notes";
    }

    /* Re-enter the timeline after a Test Play WITHOUT reloading the chart (it stays in memory): just rebuild the
       audition audio (the rhythm game closed it) and re-establish Mode.EDITOR. The parent restores its `view`. */
    public void resumeAfterTest(BiosContext ctx) {
        keyIn.clear();
        mouseIn.clear();
        scrollIn.clear();
        previewing = false;
        playheadMs = cursorMs;
        if (song != null) {
            AudioPlayer a = ctx.startSongAudio(song);
            songDurationMs = a != null ? (int) (a.durationSeconds() * 1000) : 0;
            wave = a != null ? a.waveform() : new float[0];
        }
        notice = "Back from test play";
        ctx.enterMode(Mode.EDITOR);
    }

    public void step(BiosContext ctx) {
        double[] s;
        while ((s = scrollIn.poll()) != null) {
            map.panPixels(-s[1] * 80); // wheel pans the view; cursor stays put
        }
        double[] m;
        while ((m = mouseIn.poll()) != null) {
            if (pending == P_NONE) {
                handleMouse(ctx, (int) m[0], (int) m[1], m[2], m[3]);
            }
        }
        if (pending == P_NONE && dragging && dragNote != null && chart != null) {
            int lane = laneForY((int) Math.round(ctx.editorLogicalY(ctx.editorMouseWinY())));
            int t = clampTime(grid.snap(map.msForX((int) Math.round(ctx.editorLogicalX(ctx.editorMouseWinX())))));
            if (lane != dragNote.lane || t != dragNote.timeMs) {
                dragNote = chart.move(dragNote, lane, t); // free 2D move (lane + time); fixes a wrong-lane misclick
                selected = dragNote;
                cursorMs = t;
            }
        }
        if (pending == P_NONE && overviewDragging) {
            panViewToOverviewX((int) Math.round(ctx.editorLogicalX(ctx.editorMouseWinX())));
        }
        int[] e;
        while ((e = keyIn.poll()) != null) {
            int key = e[0];
            int action = e[1];
            int mods = e.length > 2 ? e[2] : 0;
            if (action != GLFW_PRESS && action != GLFW_REPEAT) {
                continue;
            }
            handleKey(ctx, key, mods);
            if (!parent.editing() || ctx.mode() != Mode.EDITOR) {
                return; // left the edit view (back to diffs) or launched a test this frame
            }
        }
        if (previewing) {
            AudioPlayer a = ctx.audio();
            if (a != null && a.isPlaying()) {
                playheadMs = (int) (a.positionSeconds() * 1000);
            } else {
                previewing = false; // reached the song end (or stopped) -> return the playhead to the cursor
                playheadMs = cursorMs;
            }
        }
    }

    private void handleKey(BiosContext ctx, int key, int mods) {
        if (pending != P_NONE) { // an unsaved-changes confirm is up: ENTER discard, S save, else cancel
            if (key == KEY_ENTER) {
                doPending(ctx);
            } else if (key == KEY_S) {
                save(ctx);
                doPending(ctx);
            } else {
                pending = P_NONE;
                notice = "Cancelled";
            }
            return;
        }
        boolean shift = (mods & MOD_SHIFT) != 0;
        switch (key) {
            case KEY_ESCAPE -> exit(ctx);
            case KEY_SPACE -> togglePreview(ctx);
            case KEY_P -> testPlay(ctx, cursorMs);
            case KEY_O -> testPlay(ctx, 0);
            case KEY_S -> save(ctx);
            case KEY_LEFT -> place(0);
            case KEY_DOWN -> place(1);
            case KEY_UP -> place(2);
            case KEY_RIGHT -> place(3);
            case KEY_COMMA -> {
                if (shift) {
                    grid.offsetMs = Math.max(-10000, grid.offsetMs - 5);
                } else {
                    cursorMs = clampTime(grid.step(cursorMs, -1));
                    followCursor();
                }
            }
            case KEY_PERIOD -> {
                if (shift) {
                    grid.offsetMs += 5;
                } else {
                    cursorMs = clampTime(grid.step(cursorMs, +1));
                    followCursor();
                }
            }
            case KEY_HOME -> { cursorMs = 0; followCursor(); }
            case KEY_END -> { cursorMs = songDurationMs > 0 ? songDurationMs : (chart != null ? chart.lastTimeMs() : 0); followCursor(); }
            case KEY_LEFT_BRACKET -> grid.cycleDivisor(-1);
            case KEY_RIGHT_BRACKET -> grid.cycleDivisor(+1);
            case KEY_G -> { grid.snapOn = !grid.snapOn; notice = grid.snapOn ? "Snap ON" : "Snap OFF"; }
            case KEY_MINUS -> map.zoomAt(0.8, map.xForMs(cursorMs));
            case KEY_EQUAL -> map.zoomAt(1.25, map.xForMs(cursorMs));
            case KEY_SEMICOLON -> map.panPixels(-120);
            case KEY_APOSTROPHE -> map.panPixels(120);
            case KEY_Q -> { if (chart != null) { grid.bpm = Math.max(30, grid.bpm - 1); chart.markDirty(); } }
            case KEY_W -> { if (chart != null) { grid.bpm = Math.min(400, grid.bpm + 1); chart.markDirty(); } }
            case KEY_9 -> setApproach(effectiveApproach() + 50); // slower fall (longer look-ahead)
            case KEY_0 -> setApproach(effectiveApproach() - 50); // faster fall (shorter look-ahead)
            case KEY_DELETE, KEY_BACKSPACE -> {
                if (chart != null) {
                    int tol = Math.max(40, (int) (grid.snapStepMs() / 2));
                    notice = chart.removeNearest(cursorMs, tol) ? "Deleted" : "Nothing to delete here";
                }
            }
            default -> { }
        }
    }

    /* Place/toggle a tap in `lane` at the (snapped, clamped) cursor time. */
    private void place(int lane) {
        if (chart == null) {
            return;
        }
        int t = clampTime(grid.snap(cursorMs));
        int tol = Math.max(20, (int) (grid.snapStepMs() / 2));
        Note added = chart.toggle(lane, t, tol);
        notice = added != null ? "Placed lane " + LANE_GLYPHS[lane] + " @ " + t + "ms" : "Removed lane " + LANE_GLYPHS[lane];
    }

    private void handleMouse(BiosContext ctx, int button, int action, double winX, double winY) {
        if (chart == null) {
            return;
        }
        int lx = (int) Math.round(ctx.editorLogicalX(winX));
        int iy = (int) Math.round(ctx.editorLogicalY(winY));
        boolean inLanes = iy >= viewLaneTop && iy <= viewLaneBottom;
        boolean inOverview = songDurationMs > 0 && iy >= viewWaveTop && iy < viewWaveBottom;
        if (button == 0 && action == 1 && inOverview) { // click/drag the waveform minimap to scrub the main view
            overviewDragging = true;
            panViewToOverviewX(lx);
            return;
        }
        if (button == 0 && action == 1) {            // left press
            Note hit = inLanes ? findNoteAt(lx, iy) : null;
            if (hit != null) {
                selected = hit;
                dragNote = hit;
                dragging = true;
                cursorMs = hit.timeMs;
            } else if (inLanes) {
                int lane = laneForY(iy);
                int t = clampTime(grid.snap(map.msForX(lx)));
                int tol = Math.max(20, (int) (grid.snapStepMs() / 2));
                Note added = chart.toggle(lane, t, tol);
                cursorMs = t;
                if (added != null) {
                    selected = added;
                    dragNote = added;
                    dragging = true;
                    notice = "Placed " + LANE_GLYPHS[lane] + " @ " + t + "ms";
                } else {
                    selected = null;
                    notice = "Removed";
                }
            } else {
                cursorMs = Math.max(0, grid.snap(map.msForX(lx))); // click empty area = move cursor
            }
        } else if (button == 0 && action == 0) {     // left release
            dragging = false;
            dragNote = null;
            overviewDragging = false;
            if (chart != null) {
                chart.sort();
            }
        } else if (button == 1 && action == 1) {     // right press = delete
            Note hit = inLanes ? findNoteAt(lx, iy) : null;
            if (hit != null) {
                chart.remove(hit);
                if (selected == hit) {
                    selected = null;
                }
                notice = "Deleted";
            }
        }
    }

    private Note findNoteAt(int lx, int ly) {
        if (chart == null) {
            return null;
        }
        int lane = laneForY(ly);
        int tolX = Math.max(8, viewCharW * 2);
        Note best = null;
        int bestD = Integer.MAX_VALUE;
        for (Note n : chart.notes) {
            if (n.lane != lane) {
                continue;
            }
            int d = Math.abs(map.xForMs(n.timeMs) - lx);
            if (d <= tolX && d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    private int laneForY(int ly) {
        int lane = viewBandH > 0 ? (ly - viewLaneTop) / viewBandH : 0;
        return Math.max(0, Math.min(3, lane));
    }

    private void panViewToOverviewX(int x) {
        if (songDurationMs <= 0) {
            return;
        }
        int span = TerminalCanvas.LOGICAL_W - 2 * TerminalCanvas.MARGIN;
        double frac = Math.max(0.0, Math.min(1.0, (double) (x - TerminalCanvas.MARGIN) / Math.max(1, span)));
        double centerMs = frac * songDurationMs;
        double visibleMs = (TerminalCanvas.LOGICAL_W - map.originX) / map.pxPerMs;
        map.viewStartMs = centerMs - visibleMs / 2;
    }

    private int clampTime(int t) {
        t = Math.max(0, t);
        if (songDurationMs > 0) {
            t = Math.min(t, songDurationMs);
        }
        return t;
    }

    private void followCursor() {
        int margin = 120;
        int cx = map.xForMs(cursorMs);
        if (cx < map.originX + margin) {
            map.viewStartMs = cursorMs - margin / map.pxPerMs;
        } else if (cx > TerminalCanvas.LOGICAL_W - margin) {
            map.viewStartMs = cursorMs - (TerminalCanvas.LOGICAL_W - margin - map.originX) / map.pxPerMs;
        }
    }

    private void togglePreview(BiosContext ctx) {
        if (previewing) {
            AudioPlayer a = ctx.audio();
            if (a != null) {
                a.pause();
            }
            previewing = false;
            playheadMs = cursorMs;
        } else {
            previewFrom(ctx, cursorMs);
        }
    }

    private void previewFrom(BiosContext ctx, int ms) {
        AudioPlayer a = ctx.audio();
        if (a == null) {
            notice = "No audio loaded";
            return;
        }
        int from = Math.max(0, ms);
        a.playFrom(from / 1000f);
        previewing = true;
        playheadMs = from;
    }

    /* Test Play: hand a COPY of the current chart to the real rhythm game (P = from cursor, O = from start). */
    private void testPlay(BiosContext ctx, int startMs) {
        if (chart == null || chart.count() == 0) {
            notice = "Nothing to test (chart is empty)";
            return;
        }
        if (previewing) {
            AudioPlayer a = ctx.audio();
            if (a != null) {
                a.pause();
            }
            previewing = false;
        }
        notice = "Test play...";
        ctx.startRhythmTest(chart.notes, Math.max(0, startMs), effectiveApproach());
    }

    private void save(BiosContext ctx) {
        if (chart == null) {
            return;
        }
        chart.bpm = grid.bpm; // persist the effective/edited grid tempo into the saved chart
        boolean ok = ctx.saveChart(chart.songId, chart.difficultyToken, chart.toText());
        if (ok) {
            chart.markClean();
            notice = "Saved " + chart.count() + " notes -> BAMFOS/songs/" + chart.songId + "/" + chart.difficultyToken + ".chart";
        } else {
            notice = "SAVE FAILED (see log)";
        }
    }

    /* The chart's effective note fall speed (its authored value, or the engine default when unset). */
    private int effectiveApproach() {
        return chart != null && chart.approachMs > 0 ? chart.approachMs : RhythmEngine.DEFAULT_APPROACH_MS;
    }

    /* Set the per-chart fall speed (clamped); smaller = faster. Saved into THIS chart's .chart, so it affects this
       chart's real play AND Test Play but never other songs. */
    private void setApproach(int ms) {
        if (chart == null) {
            return;
        }
        int v = Math.max(400, Math.min(3000, ms));
        if (v != chart.approachMs) {
            chart.approachMs = v;
            chart.markDirty();
        }
        notice = "Fall speed " + v + "ms"
                + (v == RhythmEngine.DEFAULT_APPROACH_MS ? " (default)" : v < RhythmEngine.DEFAULT_APPROACH_MS ? " (faster)" : " (slower)");
    }

    /* ESC from the timeline: guard unsaved edits, else step back to the difficulty browser. */
    private void exit(BiosContext ctx) {
        if (chart != null && chart.dirty()) {
            pending = P_EXIT;
            notice = "Unsaved changes!  ENTER discard   S save   other cancel";
            return;
        }
        backToDiffs(ctx);
    }

    private void doPending(BiosContext ctx) {
        pending = P_NONE;
        backToDiffs(ctx); // the only pending action: leave the chart back to the difficulty browser
    }

    private void backToDiffs(BiosContext ctx) {
        ctx.closeAudio();
        previewing = false;
        parent.returnToDiffBrowser(ctx); // hand control back to the parent's difficulty browser
    }

    public void render(TerminalCanvas c, BiosContext ctx) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        int ls = c.lineSpacing();
        c.beginFrameShader(logicalW, logicalH);
        map.originX = c.margin();

        int cols = Math.max(8, logicalW / c.charWidth() - 1);
        c.text(c.margin(), ls, "CHART EDITOR  ::  " + (song != null ? song.name() : "(no song)") + "   [" + diff.label + "]", c.cGold());
        int waveTop = ls * 2;
        drawWaveOverview(c, waveTop, ls, logicalW);
        int ruleY = waveTop + WAVE_ROWS * ls;
        c.text(c.margin(), ruleY, TerminalCanvas.rule(cols), c.cFg());

        int laneTop = ruleY + ls * 2; // +2: leaves the row at laneTop-ls free for measure numbers (was colliding with the rule)
        int bandH = Math.max(2 * ls, (logicalH - laneTop - ls * 3) / 4);
        int laneBottom = laneTop + 4 * bandH;
        viewLaneTop = laneTop;
        viewBandH = bandH;
        viewLaneBottom = laneBottom;
        viewCharW = c.charWidth();
        viewWaveTop = waveTop;
        viewWaveBottom = waveTop + WAVE_ROWS * ls;

        if (chart != null) {
            drawGrid(c, laneTop, laneBottom, ls, logicalW);
            for (int lane = 0; lane < 4; lane++) {
                int y = laneTop + lane * bandH + bandH / 2;
                c.text(2, y, LANE_GLYPHS[lane], c.cDim());
            }
            for (Note note : chart.notes) {
                int x = map.xForMs(note.timeMs);
                if (x < map.originX || x > logicalW) {
                    continue;
                }
                int y = laneTop + note.lane * bandH + bandH / 2;
                int color = Art.LANE_COLORS[note.lane % Art.LANE_COLORS.length];
                String glyph = note.holdMs > 0 ? "[==]" : "[]";
                c.text(x, y - ls, glyph, color); // 3 rows tall per lane (fills the band, was a single thin row)
                c.text(x, y, glyph, color);
                c.text(x, y + ls, glyph, color);
                if (note == selected) {
                    c.text(x, y - ls * 2, "v", c.cBright());
                }
            }
            int cx = map.xForMs(cursorMs);
            if (cx >= map.originX && cx <= logicalW) {
                for (int y = laneTop; y < laneBottom; y += ls) {
                    c.text(cx, y, "|", c.cBright());
                }
            }
            drawVBar(c, map.xForMs(0), laneTop, laneBottom, ls, c.cGold());                  // gold song START
            if (songDurationMs > 0) {
                drawVBar(c, map.xForMs(songDurationMs), laneTop, laneBottom, ls, c.cGold()); // gold song END
            }
            if (previewing) {
                int px = map.xForMs(playheadMs);
                if (px >= map.originX && px <= logicalW) {
                    for (int y = laneTop; y < laneBottom; y += ls) {
                        c.text(px, y, "|", c.cGold()); // sweeping playhead (gold); the edit cursor stays white
                    }
                }
            }
        }

        String hud = "song " + (song != null ? song.id() : "-")
                + "   cursor " + cursorMs + "ms"
                + "   bpm " + (int) grid.bpm
                + "   fall " + effectiveApproach() + "ms"
                + "   off " + (int) grid.offsetMs + "ms"
                + "   snap " + (grid.snapOn ? grid.divisorLabel() : "OFF")
                + "   notes " + (chart != null ? chart.count() : 0)
                + (chart != null && chart.dirty() ? "  *" : "")
                + "   " + notice;
        c.text(c.margin(), logicalH - ls * 2, TerminalCanvas.clip(hud, cols), c.cDim());

        if (pending != P_NONE) {
            c.popup(logicalW, logicalH,
                    new String[]{"", "UNSAVED CHANGES", "Leave this chart?", "", "ENTER discard    S save    other cancel", ""},
                    new int[]{c.cBright(), c.cGold(), c.cFg(), c.cBright(), c.cFg(), c.cBright()});
        }
    }

    private void drawVBar(TerminalCanvas c, int x, int yTop, int yBottom, int ls, int color) {
        if (x < c.margin() || x > c.logicalW()) {
            return;
        }
        for (int y = yTop; y < yBottom; y += ls) {
            c.text(x, y, "‖", color);
        }
    }

    /* Retro graded-ASCII waveform overview (WAVE_ROWS tall) + ASCII viewport bracket [ ] / cursor | / playhead. */
    private void drawWaveOverview(TerminalCanvas c, int waveTop, int ls, int logicalW) {
        if (songDurationMs <= 0) {
            c.text(c.margin(), waveTop, "(loading audio...)", c.cDim());
            return;
        }
        int cols = Math.max(8, logicalW / c.charWidth() - 1);
        for (int r = 0; r < WAVE_ROWS; r++) {
            int rowFromBottom = WAVE_ROWS - 1 - r;
            StringBuilder sb = new StringBuilder(cols);
            for (int col = 0; col < cols; col++) {
                float amp = wave.length > 0 ? wave[Math.min(wave.length - 1, col * wave.length / cols)] : 0f;
                int h = (int) Math.ceil(amp * WAVE_ROWS);
                sb.append(rowFromBottom < h ? (amp > 0.6f ? '#' : amp > 0.3f ? '+' : '.') : ' ');
            }
            c.text(c.margin(), waveTop + r * ls, sb.toString(), c.cDim());
        }
        int waveBottom = waveTop + WAVE_ROWS * ls;
        drawOverviewLine(c, overviewX(c, (int) map.viewStartMs, logicalW), waveTop, waveBottom, ls, "[", c.cBright());
        drawOverviewLine(c, overviewX(c, (int) map.msForX(logicalW), logicalW), waveTop, waveBottom, ls, "]", c.cBright());
        drawOverviewLine(c, overviewX(c, cursorMs, logicalW), waveTop, waveBottom, ls, "|", c.cBright());
        if (previewing) {
            drawOverviewLine(c, overviewX(c, playheadMs, logicalW), waveTop, waveBottom, ls, "|", c.cGold());
        }
    }

    private void drawOverviewLine(TerminalCanvas c, int x, int yTop, int yBottom, int ls, String ch, int color) {
        for (int y = yTop; y < yBottom; y += ls) {
            c.text(x, y, ch, color);
        }
    }

    private int overviewX(TerminalCanvas c, int ms, int logicalW) {
        int span = logicalW - 2 * c.margin();
        return c.margin() + (int) ((double) Math.max(0, Math.min(songDurationMs, ms)) / Math.max(1, songDurationMs) * span);
    }

    private void drawGrid(TerminalCanvas c, int laneTop, int laneBottom, int ls, int logicalW) {
        double beatMs = grid.msPerBeat();
        double measureMs = beatMs * TimingGrid.BEATS_PER_MEASURE;
        double subMs = grid.snapStepMs();
        double tL = map.msForX(map.originX);
        double tR = map.msForX(logicalW);

        if (subMs > 0 && subMs * map.pxPerMs >= 9 && grid.divisor() > 1) {
            long first = (long) Math.floor((tL - grid.offsetMs) / subMs);
            long last = (long) Math.ceil((tR - grid.offsetMs) / subMs);
            if (last - first < 900) {
                for (long sIdx = first; sIdx <= last; sIdx++) {
                    if (grid.isBeat(sIdx)) {
                        continue;
                    }
                    int x = map.xForMs(grid.offsetMs + sIdx * subMs);
                    if (x < map.originX || x > logicalW) {
                        continue;
                    }
                    c.text(x, laneTop, ".", c.cDim());
                    c.text(x, laneBottom - ls, ".", c.cDim());
                }
            }
        }
        if (beatMs * map.pxPerMs >= 7) {
            long first = (long) Math.floor((tL - grid.offsetMs) / beatMs);
            long last = (long) Math.ceil((tR - grid.offsetMs) / beatMs);
            if (last - first < 700) {
                for (long b = first; b <= last; b++) {
                    if (b % TimingGrid.BEATS_PER_MEASURE == 0) {
                        continue;
                    }
                    int x = map.xForMs(grid.offsetMs + b * beatMs);
                    if (x < map.originX || x > logicalW) {
                        continue;
                    }
                    for (int y = laneTop; y < laneBottom; y += ls * 2) {
                        c.text(x, y, "|", c.cDim());
                    }
                }
            }
        }
        if (measureMs * map.pxPerMs >= 5) {
            long first = (long) Math.floor((tL - grid.offsetMs) / measureMs);
            long last = (long) Math.ceil((tR - grid.offsetMs) / measureMs);
            if (last - first < 500) {
                for (long mIdx = first; mIdx <= last; mIdx++) {
                    int x = map.xForMs(grid.offsetMs + mIdx * measureMs);
                    if (x < map.originX || x > logicalW) {
                        continue;
                    }
                    for (int y = laneTop; y < laneBottom; y += ls) {
                        c.text(x, y, "|", c.cFg());
                    }
                    if (mIdx >= 0) {
                        c.text(x + 2, laneTop - ls, Long.toString(mIdx + 1), c.cDim());
                    }
                }
            }
        }
    }
}
