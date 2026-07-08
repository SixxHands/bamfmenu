package com.retro.bamfmenu.early.bios.screen.mode.editor;

import com.retro.bamfmenu.early.bios.Mode;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.core.rhythm.chart.Chart;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartIO;
import com.retro.bamfmenu.early.core.song.Difficulty;
import com.retro.bamfmenu.early.core.song.SongDef;
import com.retro.bamfmenu.early.core.song.SongLoader;
import com.retro.bamfmenu.early.core.song.SongRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/* Mode.EDITOR host: the in-house chart editor. It has THREE views -- a SONG browser, a DIFFICULTY browser,
   and the horizontal TIMELINE editor -- but the timeline (the bulk) lives in its own class, TimelineEditorView, so no
   single class owns all three screens. THIS class is the Screen seam + the two cohesive browser views (the song ->
   difficulty selection flow, which share browseSongs/songSel/diffSel) + the coordinator: it OWNS the active `view`,
   dispatches step/render/controlHints, and bridges into the timeline (openForEdit forks/normalizes then hands off;
   the timeline calls back via returnToDiffBrowser/editing).

   "Open Chart Editor" lands on the song browser (not the last-pointed song); you pick a song, then a difficulty (edit
   existing / create new / R = reset-to-empty with confirm), then edit on the timeline. ESC steps back a level (edit ->
   diffs -> songs -> BIOS). Keyboard codes are hard-coded so this class stays free of the LWJGL import (TetrisScreen
   convention). Input drains on the render thread in step() from the provider-owned queues. Retro ASCII look. */
public final class EditorScreen implements Screen {

    private static final int KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_R = 82;
    private static final int KEY_DOWN = 264, KEY_UP = 265;
    private static final int GLFW_PRESS = 1, GLFW_REPEAT = 2;

    private static final int VIEW_SONGS = 0, VIEW_DIFFS = 1, VIEW_EDIT = 2;
    /* The scored difficulties offered in the browser (SPECIAL is not a chartable difficulty). v1 charts the fixed
       EASY/NORMAL/HARD set; editing a song's custom (data-driven) difficulty set is a later editor item. */
    private static final Difficulty[] DIFFS = {Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD};

    private final Queue<int[]> keyIn;
    private final Queue<double[]> mouseIn;
    private final Queue<double[]> scrollIn;
    /* The timeline editor view (the third screen), extracted; this class drives it + it calls back here. */
    private final TimelineEditorView timeline;

    private int view = VIEW_SONGS;
    // --- browser state (shared by the song + difficulty views) ---
    private int songSel;
    private int diffSel = 1; // default NORMAL
    private final List<SongDef> browseSongs = new ArrayList<>(); // default songs + on-disk user songs (combined)
    private int[] songChartedCount = new int[0]; // per browse-song: how many of the 3 diffs have a chart
    private final int[] diffNoteCount = new int[DIFFS.length]; // notes per diff for the selected song (-1 = none)
    private boolean resetConfirm;
    private boolean createConfirm; // "Create New Song" anti-spam reconfirm
    private String browseNotice = "";

    public EditorScreen(Queue<int[]> keyIn, Queue<double[]> mouseIn, Queue<double[]> scrollIn) {
        this.keyIn = keyIn;
        this.mouseIn = mouseIn;
        this.scrollIn = scrollIn;
        this.timeline = new TimelineEditorView(this, keyIn, mouseIn, scrollIn);
    }

    @Override
    public Mode mode() {
        return Mode.EDITOR;
    }

    @Override
    public void onEnter(BiosContext ctx) {
        keyIn.clear();
        mouseIn.clear();
        scrollIn.clear();
        view = VIEW_SONGS;
        songSel = 0;
        recomputeSongList(ctx);
        SongDef cur = ctx.currentSong();
        if (cur != null) {
            for (int i = 0; i < browseSongs.size(); i++) {
                if (browseSongs.get(i) == cur) {
                    songSel = i;
                    break;
                }
            }
        }
        resetConfirm = false;
        createConfirm = false;
        browseNotice = "";
        ctx.enterMode(Mode.EDITOR);
    }

    @Override
    public void onExit(BiosContext ctx) {
        ctx.closeAudio(); // free any audition audio when the whole editor mode exits
    }

    @Override
    public void step(BiosContext ctx) {
        switch (view) {
            case VIEW_SONGS -> stepSongs(ctx);
            case VIEW_DIFFS -> stepDiffs(ctx);
            default -> timeline.step(ctx);
        }
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        switch (view) {
            case VIEW_SONGS -> renderSongs(c);
            case VIEW_DIFFS -> renderDiffs(c);
            default -> timeline.render(c, ctx);
        }
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return switch (view) {
            case VIEW_SONGS -> "UP/DOWN  select      ENTER  open / create      ESC  exit";
            case VIEW_DIFFS -> "UP/DOWN  difficulty      ENTER  edit/create      R  reset      ESC  back";
            default -> timeline.controlHints();
        };
    }

    /* ---- timeline bridge (called by TimelineEditorView + the provider) ---- */

    /* True while the timeline view is the active screen (the timeline checks this to know when an ESC/back transition
       this frame has handed control back to a browser, so it stops draining input meant for the browser). */
    boolean editing() {
        return view == VIEW_EDIT;
    }

    /* The timeline finished (ESC / discard / saved-then-left): refresh the difficulty counts from disk + show the
       difficulty browser. The timeline already closed the audition audio. */
    void returnToDiffBrowser(BiosContext ctx) {
        recomputeDiffCounts(ctx);
        browseNotice = "";
        view = VIEW_DIFFS;
    }

    /* Re-enter the timeline after a Test Play (provider routes the test's end here). The chart stays in memory; the
       timeline just rebuilds its audition audio. */
    public void resumeAfterTest(BiosContext ctx) {
        view = VIEW_EDIT;
        timeline.resumeAfterTest(ctx);
    }

    /* ===================== SONG BROWSER ===================== */

    private void stepSongs(BiosContext ctx) {
        mouseIn.clear();
        scrollIn.clear();
        int[] e;
        while ((e = keyIn.poll()) != null) {
            int key = e[0];
            int action = e[1];
            if (action != GLFW_PRESS && action != GLFW_REPEAT) {
                continue;
            }
            if (createConfirm) { // anti-spam: every Create New asks first
                if (key == KEY_ENTER) {
                    doCreateNew(ctx);
                } else {
                    createConfirm = false;
                    browseNotice = "Create cancelled";
                }
                continue;
            }
            int rows = browseSongs.size() + 1; // +1 = the trailing "Create New Song" row
            switch (key) {
                case KEY_UP -> songSel = (songSel - 1 + rows) % rows;
                case KEY_DOWN -> songSel = (songSel + 1) % rows;
                case KEY_ENTER -> {
                    if (isCreateRow()) {
                        createConfirm = true;
                        browseNotice = "";
                    } else {
                        int sel = clampSong(songSel);
                        String refuse = refuseReason(sel, browseSongs.get(sel));
                        if (refuse != null) {
                            browseNotice = refuse; // can't chart this folder -> stay in the song menu with the reason
                        } else {
                            diffSel = 1;
                            recomputeDiffCounts(ctx);
                            browseNotice = "";
                            view = VIEW_DIFFS;
                        }
                    }
                    return;
                }
                case KEY_ESCAPE -> {
                    ctx.exitToSetup(5, ""); // leave the editor to the BIOS "Open Chart Editor" row
                    return;
                }
                default -> { }
            }
        }
    }

    /* The selection is on the trailing "Create New Song" row (it sits after all real songs). */
    private boolean isCreateRow() {
        return songSel >= browseSongs.size();
    }

    private void doCreateNew(BiosContext ctx) {
        createConfirm = false;
        String id = ctx.createNewSong();
        if (id == null) {
            browseNotice = "Create failed (see log)";
            return;
        }
        recomputeSongList(ctx);
        for (int i = 0; i < browseSongs.size(); i++) {
            if (browseSongs.get(i).id().equals(id)) {
                songSel = i;
                break;
            }
        }
        browseNotice = "Created " + id + " -- drop a song.ogg into BAMFOS/songs/" + id + "/ to chart it";
    }

    private void renderSongs(TerminalCanvas c) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        int ls = c.lineSpacing();
        c.beginFrameShader(logicalW, logicalH);
        int cols = Math.max(8, logicalW / c.charWidth() - 1);
        c.text(c.margin(), ls, "CHART EDITOR  ::  SELECT SONG", c.cGold());
        c.text(c.margin(), ls * 2, TerminalCanvas.rule(cols), c.cFg());
        int dc = defaultCount();
        int y = ls * 4;
        c.text(c.margin(), y, "DEFAULT SONGS", c.cDim());
        y += ls;
        for (int i = 0; i < browseSongs.size(); i++) {
            if (i == dc) { // transition into the user-songs section
                y += ls;
                c.text(c.margin(), y, "USER SONGS", c.cDim());
                y += ls;
            }
            boolean isSel = i == songSel;
            int cc = i < songChartedCount.length ? songChartedCount[i] : 0;
            SongDef bs = browseSongs.get(i);
            String label = bs.name() + (i >= dc ? "  <" + bs.id() + ">" : ""); // show the folder id for user songs (tells a fork from its default)
            if (i >= dc) {
                if (bs.ogg() == null) {
                    label += "  (needs audio)"; // audio-less -> can't open until an .ogg is dropped in
                } else if (SongLoader.MULTI_OGG.equals(bs.ogg())) {
                    label += "  (too many .ogg)"; // more than one song ogg -> use only one per chart
                }
            }
            c.text(c.margin(), y, (isSel ? "> " : "  ") + label + "   [" + cc + "/" + DIFFS.length + " charts]", isSel ? c.cBright() : c.cFg());
            y += ls;
        }
        if (browseSongs.size() <= dc) { // no user songs yet
            y += ls;
            c.text(c.margin(), y, "USER SONGS   (none yet -- edit a default to fork one, or drop a folder in BAMFOS/songs/)", c.cDim());
        }
        y += ls; // the trailing Create New row
        boolean createSel = isCreateRow();
        c.text(c.margin(), y, (createSel ? "> " : "  ") + "[ + Create New Song ]", createSel ? c.cGold() : c.cDim());
        if (!browseNotice.isEmpty()) {
            c.text(c.margin(), logicalH - ls * 2, TerminalCanvas.clip(browseNotice, cols), c.cDim());
        }
        if (createConfirm) {
            c.popup(logicalW, logicalH,
                    new String[]{"", "CREATE NEW SONG", "Make a new empty song folder?", "", "ENTER create    other cancel", ""},
                    new int[]{c.cBright(), c.cGold(), c.cFg(), c.cBright(), c.cFg(), c.cBright()});
        }
    }

    /* ===================== DIFFICULTY BROWSER ===================== */

    private void stepDiffs(BiosContext ctx) {
        mouseIn.clear();
        scrollIn.clear();
        int[] e;
        while ((e = keyIn.poll()) != null) {
            int key = e[0];
            int action = e[1];
            if (action != GLFW_PRESS && action != GLFW_REPEAT) {
                continue;
            }
            if (resetConfirm) {
                if (key == KEY_ENTER) {
                    doReset(ctx);
                } else {
                    resetConfirm = false;
                    browseNotice = "Reset cancelled";
                }
                continue;
            }
            switch (key) {
                case KEY_UP -> diffSel = (diffSel - 1 + DIFFS.length) % DIFFS.length;
                case KEY_DOWN -> diffSel = (diffSel + 1) % DIFFS.length;
                case KEY_ENTER -> { openForEdit(ctx); return; }
                case KEY_R -> resetConfirm = true;
                case KEY_ESCAPE -> {
                    recomputeSongList(ctx);
                    browseNotice = "";
                    view = VIEW_SONGS;
                    return;
                }
                default -> { }
            }
        }
    }

    private void renderDiffs(TerminalCanvas c) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        int ls = c.lineSpacing();
        c.beginFrameShader(logicalW, logicalH);
        int cols = Math.max(8, logicalW / c.charWidth() - 1);
        SongDef s = browseSongs.get(clampSong(songSel));
        String sName = s.name() + (isUserIndex(clampSong(songSel)) ? "  <" + s.id() + ">" : "");
        c.text(c.margin(), ls, "CHART EDITOR  ::  " + sName + "  ::  SELECT DIFFICULTY", c.cGold());
        c.text(c.margin(), ls * 2, TerminalCanvas.rule(cols), c.cFg());
        int y = ls * 4;
        for (int i = 0; i < DIFFS.length; i++) {
            boolean sel = i == diffSel;
            int nc = diffNoteCount[i];
            String status = nc < 0 ? "(empty - create)" : "(" + nc + " notes)";
            c.text(c.margin(), y, (sel ? "> " : "  ") + DIFFS[i].label + "   " + status, sel ? c.cBright() : c.cFg());
            y += ls;
        }
        c.text(c.margin(), logicalH - ls * 3, "ENTER edit/create    R reset (clear to empty)    ESC back", c.cDim());
        if (!browseNotice.isEmpty()) {
            c.text(c.margin(), logicalH - ls * 2, TerminalCanvas.clip(browseNotice, cols), c.cDim());
        }
        if (resetConfirm) {
            c.popup(logicalW, logicalH,
                    new String[]{"", "CLEAR CHART", s.name() + " " + DIFFS[diffSel].label + " -> empty?", "", "ENTER clear    other cancel", ""},
                    new int[]{c.cBright(), c.cGold(), c.cFg(), c.cBright(), c.cFg(), c.cBright()});
        }
    }

    /* Wipe a difficulty's chart to empty (the pack's chosen Reset semantics): write a 0-note chart to the
       song's BAMFOS folder (which also materializes the folder), then refresh the counts. */
    private void doReset(BiosContext ctx) {
        resetConfirm = false;
        SongDef s = browseSongs.get(clampSong(songSel));
        Difficulty d = DIFFS[diffSel];
        boolean ok = ctx.saveChart(s.id(), d.token, ChartIO.write(new Chart(d.token, s.bpm(), 0, 0, new ArrayList<>())));
        browseNotice = ok ? (d.label + " cleared to empty") : "Reset FAILED (see log)";
        recomputeDiffCounts(ctx);
    }

    /* ===================== timeline handoff ===================== */

    /* ENTER on a difficulty: resolve the target folder (a bundled default FORKS to a numbered copy; a user song edits
       in place, after a mirror-rename to its OGG name), then hand the loaded chart to the timeline editor. The
       fork/normalize + browser-rebind is a browser concern; the timeline owns everything after the handoff. */
    private void openForEdit(BiosContext ctx) {
        if (browseSongs.isEmpty()) {
            return;
        }
        int idx = clampSong(songSel);
        SongDef picked = browseSongs.get(idx);
        SongDef target;
        if (isUserIndex(idx)) {
            String refuse = refuseReason(idx, picked);
            if (refuse != null) { // audio-less or multi-OGG -> can't chart (belt-and-suspenders; the menu also guards)
                browseNotice = refuse;
                view = VIEW_SONGS;
                return;
            }
            // Mirror the on-disk layout: rename the folder to the OGG's name (synchronous fix-then-load, no junk),
            // then rebind the browser to the (possibly) renamed song so it edits THAT folder.
            SongDef fixed = ctx.normalizeUserSong(picked);
            target = fixed != null ? fixed : picked;
            if (fixed != null && !fixed.id().equals(picked.id())) {
                browseNotice = "Renamed to " + fixed.id();
                recomputeSongList(ctx);
                for (int i = 0; i < browseSongs.size(); i++) {
                    if (browseSongs.get(i).id().equals(fixed.id())) {
                        songSel = i;
                        target = browseSongs.get(i);
                        break;
                    }
                }
            }
        } else {
            target = ctx.forkSong(picked); // a bundled default: fork to a numbered copy, then edit the copy
            if (target == null) {
                browseNotice = "Fork failed (see log)";
                return;
            }
            browseNotice = "Forked " + picked.name() + " -> " + target.id();
            // Rebind the browser to the fork so the REST of the session edits THAT one folder: picking another
            // difficulty (or re-entering this song) now sees a user song and edits in place instead of forking
            // AGAIN into recovery-3/-4/.... Fixes the "object permanence" bug where difficulties scattered across
            // separate numbered forks.
            recomputeSongList(ctx);
            for (int i = 0; i < browseSongs.size(); i++) {
                if (browseSongs.get(i).id().equals(target.id())) {
                    songSel = i;
                    target = browseSongs.get(i);
                    break;
                }
            }
        }
        timeline.open(ctx, target, DIFFS[diffSel]);
        view = VIEW_EDIT;
    }

    /* ===================== shared browse helpers ===================== */

    private int clampSong(int i) {
        int n = browseSongs.size();
        return n == 0 ? 0 : ((i % n) + n) % n;
    }

    private boolean hasChart(BiosContext ctx, String id, Difficulty d) {
        String t = ctx.loadChartText(id, d.token);
        return t != null && !t.isBlank();
    }

    private int noteCount(BiosContext ctx, String id, Difficulty d) {
        String t = ctx.loadChartText(id, d.token);
        if (t == null || t.isBlank()) {
            return -1;
        }
        return ChartIO.read(t, d.token).notes().size();
    }

    private void recomputeSongList(BiosContext ctx) {
        browseSongs.clear();
        for (SongDef d : SongRegistry.SONGS) {
            browseSongs.add(d); // default (bundled) songs first
        }
        browseSongs.addAll(ctx.userSongs()); // then on-disk user songs (forks + drop-ins)
        songChartedCount = new int[browseSongs.size()];
        for (int i = 0; i < browseSongs.size(); i++) {
            int cc = 0;
            for (Difficulty d : DIFFS) {
                if (hasChart(ctx, browseSongs.get(i).id(), d)) {
                    cc++;
                }
            }
            songChartedCount[i] = cc;
        }
    }

    /* A browse row is a USER song (on disk) iff it sorts after the bundled defaults: browseSongs is built as the
       defaults [0, dc) followed by ctx.userSongs() [dc, size). Index-based (not ogg-based) so an audio-less user
       song -- whose ogg is null -- is still correctly classified as a user song. */
    private boolean isUserIndex(int i) {
        return i >= defaultCount();
    }

    /* Why a user song can't be opened for charting, or null if it's openable. Defaults are always openable; a user
       song needs exactly ONE playable OGG: none -> "add one"; more than one -> "use only one song per chart". */
    private String refuseReason(int idx, SongDef s) {
        if (!isUserIndex(idx)) {
            return null;
        }
        if (s.ogg() == null) {
            return "No OGG to edit a chart for -- drop a song.ogg into BAMFOS/songs/" + s.id() + "/";
        }
        if (SongLoader.MULTI_OGG.equals(s.ogg())) {
            return "Use only one song per chart! -- remove the extra .ogg from BAMFOS/songs/" + s.id() + "/";
        }
        return null;
    }

    private int defaultCount() {
        return SongRegistry.SONGS.length;
    }

    private void recomputeDiffCounts(BiosContext ctx) {
        if (browseSongs.isEmpty()) {
            return;
        }
        SongDef s = browseSongs.get(clampSong(songSel));
        for (int i = 0; i < DIFFS.length; i++) {
            diffNoteCount[i] = noteCount(ctx, s.id(), DIFFS[i]);
        }
    }
}
