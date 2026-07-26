package com.retro.bamfmenu.early.core.repair.view;

import com.retro.bamfmenu.early.core.repair.api.PageRow;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairPage;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure layout builder turning a session snapshot plus `(cols, rows, tick)` into a RepairFrame.
 * Kept separate from RepairSession so cosmetic layout edits stay away from input and write-lock logic.
 */
public final class RepairFrames {

    /** Header line above the panel, matching SETUP's title style. */
    public static final String TITLE = "BAMFOS Update and Repair Utility, please select an option.";

    private static final char[] SPINNER = {'|', '/', '-', '\\'};

    /** Snapshot of inputs needed to build a frame without reaching back into session state. */
    public record View(List<RepairItem> items, int sel, UpdaterState state, RepairPage page,
                       int pageSel, int pageTop, String working, RepairResult last,
                       String hints, String confirm) {

        /** True while a worker action is running, switching the notice slot to a spinner. */
        public boolean busy() {
            return !working.isEmpty();
        }
    }

    /**
     * Rendered frame paired with the clamped page cursor.
     * Snapping the cursor onto a pickable row keeps ENTER aligned after resizes or refreshes.
     */
    public record Built(RepairFrame frame, int pageSel, int pageTop) {
    }

    private RepairFrames() {
    }

    /** Builds a frame for `maxChars` screen columns, `interiorRows` panel rows, and spinner `tick`. */
    public static Built build(View v, int maxChars, int interiorRows, int tick) {
        int width = RepairBox.rightInner(maxChars);
        int rows = Math.max(v.items().size(), Math.max(1, interiorRows));
        String[] rightText = new String[rows];
        Tone[] rightTone = new Tone[rows];
        for (int i = 0; i < rows; i++) {
            rightText[i] = "";
            rightTone[i] = Tone.NORMAL;
        }
        int pageSel = v.pageSel();
        int pageTop = v.pageTop();
        int dividerRow;
        if (v.page() == null) {
            dividerRow = fillList(v, rightText, rightTone, rows, width, tick);
        } else {
            PageFill fill = fillPage(v, rightText, rightTone, rows, width, tick);
            dividerRow = fill.dividerRow();
            pageSel = fill.pageSel();
            pageTop = fill.pageTop();
        }

        List<RepairFrame.Row> out = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            String left = "";
            Tone leftTone = Tone.NORMAL;
            if (r < v.items().size()) {
                RepairItem item = v.items().get(r);
                boolean on = r == v.sel();
                left = RepairBox.clip((on ? "> " : "  ") + item.label(), RepairBox.LEFT_INNER);
                // a row that cannot run right now is DIM, so the menu answers "not now" before you press
                // it. Before the snapshot lands NOTHING is dimmed, because we have not read the install yet
                // and greying a row on no evidence is the same lie as lighting one
                boolean dim = v.state().loaded() && !item.enabled(v.state());
                leftTone = dim ? Tone.DIM : (on ? Tone.BRIGHT : Tone.NORMAL);
            }
            out.add(r == dividerRow ? RepairFrame.Row.divider(left, leftTone)
                    : RepairFrame.Row.of(left, leftTone, rightText[r], rightTone[r]));
        }
        RepairFrame frame = new RepairFrame(TITLE, RepairBox.top(maxChars), out, RepairBox.bottom(maxChars),
                v.hints(), promptLines(v.confirm()));
        return new Built(frame, pageSel, pageTop);
    }

    /** True if any row is selectable; when false, UP/DOWN scrolls the page instead. */
    public static boolean hasPicks(List<PageRow> rows) {
        for (PageRow row : rows) {
            if (row.selectable()) {
                return true;
            }
        }
        return false;
    }

    /** Index of the first selectable row, or 0 if the page has none. */
    public static int firstPick(List<PageRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).selectable()) {
                return i;
            }
        }
        return 0;
    }

    /* Lays out the default right panel like SETUP: help text, the ├──┤ divider, status line, and notice.
       Returns the row index of the divider, or -1 if there isn't room for it. */
    private static int fillList(View v, String[] text, Tone[] tone, int rows, int width, int tick) {
        if (v.items().isEmpty()) {
            text[0] = RepairBox.clip("No repair tools found.", width);
            tone[0] = Tone.ALERT;
            return -1;
        }
        RepairItem item = v.items().get(v.sel());
        List<String> help = RepairBox.wrap(item.help(), width);
        int r = 0;
        for (; r < help.size() && r < rows; r++) {
            text[r] = RepairBox.clip(help.get(r), width);
        }
        int dividerRow = r < rows ? r : -1;
        int stateRow = dividerRow + 1;
        if (dividerRow >= 0 && stateRow < rows) {
            String why = item.whyNot(v.state());
            if (!v.state().loaded()) {
                text[stateRow] = RepairBox.clip("Reading the updater ...", width);
                tone[stateRow] = Tone.DIM;
            } else if (!why.isEmpty()) {
                text[stateRow] = RepairBox.clip(why, width);
                tone[stateRow] = Tone.ALERT;
            } else {
                text[stateRow] = RepairBox.clip(item.status().line(v.state()), width);
                tone[stateRow] = Tone.BRIGHT;
            }
        }
        int noticeRow = dividerRow + 2;
        if (dividerRow >= 0 && noticeRow < rows) {
            String line = noticeLine(v, tick);
            if (!line.isEmpty()) {
                text[noticeRow] = RepairBox.clip(line, width);
                tone[noticeRow] = noticeTone(v);
            }
        }
        return dividerRow;
    }

    /* Result of laying out a subpage (no divider, plus the clamped cursor index). */
    private record PageFill(int dividerRow, int pageSel, int pageTop) {
    }

    /* A subpage takes the whole right column (same layout as the jukebox song list): title on row 0,
       scrollable content below it, and the bottom row reserved for the status notice. */
    private static PageFill fillPage(View v, String[] text, Tone[] tone, int rows, int width, int tick) {
        RepairPage page = v.page();
        int pageSel = v.pageSel();
        int pageTop = v.pageTop();
        text[0] = RepairBox.clip(page.title(), width);
        tone[0] = Tone.NOTICE;
        int noticeRow = rows - 1;
        String notice = noticeLine(v, tick);
        if (!notice.isEmpty()) {
            text[noticeRow] = RepairBox.clip(notice, width);
            tone[noticeRow] = noticeTone(v);
        }
        if (page.needsReleases() && v.state().releases() == null) {
            int waitRow = Math.min(2, rows - 1);
            text[waitRow] = RepairBox.clip("Reading the release list ...  " + spin(tick), width);
            tone[waitRow] = Tone.DIM;
            return new PageFill(-1, pageSel, pageTop);
        }
        List<PageRow> pageRows = page.rows(v.state(), width);
        boolean picks = hasPicks(pageRows);
        // Rows can shift on resize or refresh (e.g. a highlighted mod disappears), so snap the cursor
        // back to a selectable row instead of leaving it on plain text.
        if (picks && (pageSel < 0 || pageSel >= pageRows.size() || !pageRows.get(pageSel).selectable())) {
            pageSel = firstPick(pageRows);
        }
        int top = 2;                                  // leave one blank row under the title to match SETUP lists
        int slots = Math.max(1, noticeRow - top);
        if (pageRows.size() > slots) {
            int view = Math.max(1, slots - 2);        // the two ends are the ▲/▼ markers, kept fixed
            int first = picks ? Math.max(0, Math.min(pageSel - view / 2, pageRows.size() - view))
                    : Math.max(0, Math.min(pageTop, pageRows.size() - view));
            pageTop = first;
            if (first > 0) {
                text[top] = "  ▲ more";
                tone[top] = Tone.DIM;
            }
            if (first + view < pageRows.size()) {
                text[top + slots - 1] = "  ▼ more";
                tone[top + slots - 1] = Tone.DIM;
            }
            for (int k = 0; k < view; k++) {
                place(text, tone, top + 1 + k, pageRows.get(first + k), first + k, pageSel, picks, width);
            }
        } else {
            for (int k = 0; k < pageRows.size(); k++) {
                place(text, tone, top + k, pageRows.get(k), k, pageSel, picks, width);
            }
        }
        return new PageFill(-1, pageSel, pageTop);
    }

    private static void place(String[] text, Tone[] tone, int row, PageRow pageRow, int index, int pageSel,
                              boolean picks, int width) {
        if (row < 0 || row >= text.length) {
            return;
        }
        boolean on = picks && index == pageSel;
        // indent every row when the page has selectable items so they all line up
        String prefix = picks ? (on ? "> " : "  ") : "";
        text[row] = RepairBox.clip(prefix + pageRow.text(), width);
        tone[row] = on ? Tone.BRIGHT : pageRow.tone();
    }

    /* While the worker holds an action the notice slot belongs to it: naming the action plus a live
       spinner is the only honest thing on screen, since the op gives us no progress to report. */
    private static String noticeLine(View v, int tick) {
        if (v.busy()) {
            return v.working() + " ...  " + spin(tick);
        }
        return v.last() == null ? "" : v.last().message();
    }

    private static Tone noticeTone(View v) {
        if (v.busy()) {
            return Tone.NOTICE;
        }
        if (v.last() == null) {
            return Tone.NORMAL;
        }
        return v.last().kind() == RepairResult.Kind.FAILED ? Tone.ALERT : Tone.NOTICE;
    }

    private static char spin(int tick) {
        return SPINNER[Math.abs(tick / 3) % SPINNER.length];
    }

    private static List<String> promptLines(String confirm) {
        return confirm.isEmpty() ? List.of() : List.of("", confirm, "[ENTER] Yes      [ESC] No");
    }
}
