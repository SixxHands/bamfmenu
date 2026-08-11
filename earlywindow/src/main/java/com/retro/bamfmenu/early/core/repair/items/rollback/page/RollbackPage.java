package com.retro.bamfmenu.early.core.repair.items.rollback.page;

import com.retro.bamfmenu.early.core.repair.api.PageRow;
import com.retro.bamfmenu.early.core.repair.api.ReleaseList;
import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairPage;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.items.status.page.StatusPage;
import com.retro.bamfmenu.early.core.repair.view.RepairBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Rollback picker (newest first, capped at 10 by the engine).
 * Installed rows and offline fallback rows are never pickable so syncTo cannot pin on a dead network.
 * Handles empty lists (fresh offline install or empty repo) and shows the "first version" line only when LIVE.
 */
public final class RollbackPage implements RepairPage {

    /** Joke text originally on the SETUP row, still shown when no releases are available. */
    public static final String FIRST_VERSION_GAG = "This is the first version, silly!";

    @Override
    public String title() {
        return "Roll back to an older release";
    }

    @Override
    public boolean needsReleases() {
        return true;
    }

    @Override
    public String hints() {
        return "UP/DOWN  Select      ENTER  Roll back      ESC  Back      F2  Volume";
    }

    @Override
    public List<PageRow> rows(UpdaterState state, int width) {
        List<PageRow> out = new ArrayList<>();
        for (Entry e : entries(state, width)) {
            out.add(e.row());
        }
        return out;
    }

    @Override
    public RepairEffect.Act activate(int index, UpdaterState state, int width) {
        List<Entry> entries = entries(state, width);
        if (index < 0 || index >= entries.size() || entries.get(index).tag() == null) {
            return null;
        }
        String tag = entries.get(index).tag();
        return new RepairEffect.Act("Rolling back to " + tag,
                "Roll back to " + tag + "? This pins BAMFOS to the selected version.",
                ops -> RepairResult.ofSync(ops.syncTo(tag)));
    }

    /* Keep rows and tags in parallel lists so the displayed row and the tag synced on ENTER share
       the same index. A null tag marks a non-selectable info line. */
    private record Entry(PageRow row, String tag) {
    }

    private static List<Entry> entries(UpdaterState state, int width) {
        List<Entry> out = new ArrayList<>();
        int cell = Math.max(12, width - 2); // the two columns the selection cursor takes
        ReleaseList list = state.releases();
        if (list == null) {
            out.add(new Entry(PageRow.text("Checking release list ...", Tone.DIM), null));
            return out;
        }
        for (String line : RepairBox.wrap(StatusPage.PIN_WARNING, cell)) {
            out.add(new Entry(PageRow.text(line, Tone.ALERT), null));
        }
        out.add(new Entry(PageRow.gap(), null));
        if (!list.live()) {
            Tone tone = reasonTone(list.code());
            for (String line : RepairBox.wrap(offlineReason(list.code(), list.rows().isEmpty()), cell)) {
                out.add(new Entry(PageRow.text(line, tone), null));
            }
            out.add(new Entry(PageRow.gap(), null));
        }
        if (list.rows().isEmpty() && list.code() != ReleaseList.NO_ANSWER) {
            // Show the currently installed version under the reason so the picker doesn't look empty.
            // Skip for NO_ANSWER since we don't know the installed version either.
            for (String line : RepairBox.wrap(list.live() ? "This repo has no releases yet."
                    : "Nothing installed yet, so there is nothing to roll back to.", cell)) {
                out.add(new Entry(PageRow.text(line, Tone.DIM), null));
            }
            return out;
        }
        for (ReleaseList.Release r : list.rows()) {
            String cellText = r.installed() ? "INSTALLED" : (r.prerelease() ? "pre-release" : r.day());
            String text = RepairBox.dotted(r.tag(), cellText, cell);
            boolean pickable = list.live() && !r.installed();
            out.add(new Entry(pickable ? PageRow.pick(text) : PageRow.text(text, Tone.DIM),
                    pickable ? r.tag() : null));
        }
        if (list.nothingOlder()) {
            out.add(new Entry(PageRow.gap(), null));
            out.add(new Entry(PageRow.text(FIRST_VERSION_GAG, Tone.NOTICE), null));
        }
        return out;
    }

    /* Explains why only the installed version is listed. Each error code gets a distinct message so a
       dead token doesn't look like an offline error. If there are no rows, omit the trailing note about
       the installed version. */
    private static String offlineReason(int code, boolean noRows) {
        return switch (code) {
            case ReleaseList.TOKEN_DEAD -> "UPDATE TOKEN EXPIRED. The release list needs a working token.";
            case ReleaseList.RATE_LIMITED -> "GitHub is rate limiting. Try again later.";
            case ReleaseList.NO_ANSWER -> RepairResult.noAnswer().message();
            default -> noRows ? "Offline." : "Offline. Only the current installed version can be shown.";
        };
    }

    /* Dead credentials use error tone so they stand out from transient offline states;
       rate limits stay dim since they clear within an hour. */
    private static Tone reasonTone(int code) {
        return code == ReleaseList.TOKEN_DEAD || code == ReleaseList.NO_ANSWER ? Tone.ALERT : Tone.DIM;
    }
}
