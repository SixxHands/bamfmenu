package com.retro.bamfmenu.early.core.repair.items.status.page;

import com.retro.bamfmenu.early.core.repair.api.PageRow;
import com.retro.bamfmenu.early.core.repair.api.RepairPage;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.view.RepairBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Status detail page using the POST screen's `label: value` layout.
 * Reads directly from the snapshot and prints a single error line when status() is unreadable.
 */
public final class StatusPage implements RepairPage {

    /* Matches the POST screen label width ("Update Channel   : ") so both screens line up. */
    private static final int LABEL = 17;
    /** Placeholder shown when the engine didn't return a value (distinct from an empty value). */
    public static final String UNKNOWN = "unknown";

    @Override
    public String title() {
        return "Update status";
    }

    @Override
    public List<PageRow> rows(UpdaterState state, int width) {
        List<PageRow> out = new ArrayList<>();
        if (!state.loaded()) {
            out.add(PageRow.text("Reading the updater ...", Tone.DIM));
            return out;
        }
        // Status couldn't be read, so show unknown for every field instead of misleading defaults like
        // "none" or "0" (e.g. a pinned install would look unpinned even though the pin is still active).
        boolean blind = state.statusUnreadable();
        if (blind) {
            out.add(PageRow.text("Status unavailable.", Tone.ALERT));
            out.add(PageRow.gap());
        }
        out.add(PageRow.text(row("Installed", state.installedOrUnknown())));
        // Kept here after POST collapsed its hardware rows: this is the only place showing which tag
        // is pinned, and it reads last-sync state so it works even when no boot sync ran.
        out.add(PageRow.text(row("Release Lock", state.isPinned() ? state.pinned() + "  PINNED"
                : blind ? UNKNOWN : "none")));
        out.add(PageRow.text(row("Last Sync", blind ? UNKNOWN : state.lastSyncCell())));
        out.add(PageRow.text(row("Disabled Mods", blind ? UNKNOWN : String.valueOf(state.disabledCount()))));
        out.add(PageRow.text(row("Quarantined", blind ? UNKNOWN : String.valueOf(state.quarantinedCount()))));
        // Previously the engine could return WITHHELD if a scrubbed report still contained a credential.
        // Reports are built from typed fields now, so this is always either a filename or none.
        out.add(PageRow.text(row("Problem Report", blind ? UNKNOWN
                : state.hasReport() ? state.reportFile() : "none")));
        if (state.hasReport() && !blind) {
            out.add(PageRow.gap());
            out.add(PageRow.text("THE UPDATER WROTE A PROBLEM REPORT.", Tone.ALERT));
            for (String line : RepairBox.wrap("Send " + state.reportFile()
                    + " to the pack owner. It says what went wrong on this machine and it has no login "
                    + "token in it.", width)) {
                out.add(PageRow.text(line, Tone.ALERT));
            }
        }
        if (state.tokenDead()) {
            out.add(PageRow.gap());
            // Show dead-token errors clearly instead of like a network blip so broken tokens don't go unnoticed.
            out.add(PageRow.text("UPDATE TOKEN EXPIRED.", Tone.ALERT));
            for (String line : RepairBox.wrap("Syncing is dead until the token in .bamfos-updater is replaced.", width)) {
                out.add(PageRow.text(line, Tone.ALERT));
            }
        }
        if (state.isPinned()) {
            out.add(PageRow.gap());
            for (String line : RepairBox.wrap(PIN_WARNING, width)) {
                out.add(PageRow.text(line, Tone.ALERT));
            }
        }
        return out;
    }

    /** The guardrail requires in words, because there is no technical one: BCC 21.1.8 has no join
     *  gate, so a pinned client CAN join a mismatched server and eat a registry kick. */
    public static final String PIN_WARNING =
            "A pinned client can still join a mismatched server and get kicked.";

    private static String row(String label, String value) {
        return (label.length() >= LABEL ? label : label + " ".repeat(LABEL - label.length())) + ": " + value;
    }
}
