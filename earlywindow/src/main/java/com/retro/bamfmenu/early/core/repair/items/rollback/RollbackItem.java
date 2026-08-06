package com.retro.bamfmenu.early.core.repair.items.rollback;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.items.rollback.page.RollbackPage;

/* Rollback menu row backed by listReleases + syncTo. Opening the picker is read-only and only gated
   by the single-writer lock; choosing a release triggers a confirm prompt before touching files. */
public final class RollbackItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.page("rollback", "Roll Back BAMFOS",
                "Go back to an older release. A roll back will pin BAMFOS to the selected version.",
                RepairGate.FILE_SAFE,
                state -> state.isPinned() ? "PINNED to " + state.pinned() : "tracking the server",
                new RollbackPage());
    }

    @Override
    public int order() {
        return 60;
    }
}
