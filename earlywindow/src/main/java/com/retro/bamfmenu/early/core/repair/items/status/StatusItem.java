package com.retro.bamfmenu.early.core.repair.items.status;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.items.status.page.StatusPage;

/* Read-only status row that opens a subpage showing everything from status().
   Ungated so players can still open it and see the error when status() can't be read. */
public final class StatusItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.page("status", "Update Status",
                "Everything the updater knows about this install.",
                RepairGate.OPEN, StatusItem::line, new StatusPage());
    }

    /* Version plus pin state; omit the pin half when status() is unreadable instead of guessing.
       Keep the pinned line short so it fits the ~67-column cell without clipping the hint. */
    private static String line(UpdaterState state) {
        if (state.isPinned()) {
            // Shortened unpin hint so it fits within the ~67-column limit.
            return "Pinned to " + state.pinned() + ". Select Remove Release Lock to unpin.";
        }
        if (state.statusUnreadable()) {
            return state.installedOrUnknown();
        }
        return "Tracking server version at " + state.installedOrUnknown() + ".";
    }

    @Override
    public int order() {
        return 10;
    }
}
