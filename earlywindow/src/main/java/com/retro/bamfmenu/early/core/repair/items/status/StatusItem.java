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

    /* Version, then pin state. An unreadable status() gets the version half only: it never told us what
       this box tracks, and "tracking the server" is the wrong half to guess when a tag= pin the page
       cannot see is still steering the boot door. */
    private static String line(UpdaterState state) {
        if (state.isPinned()) {
            return "Server using " + state.installedOrUnknown() + ", currently pinned to " + state.pinned()
                    + ". Select Remove Release lock to unpin the BAMFOS version";
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
