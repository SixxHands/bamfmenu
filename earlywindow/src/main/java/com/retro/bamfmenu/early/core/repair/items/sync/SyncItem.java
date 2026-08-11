package com.retro.bamfmenu.early.core.repair.items.sync;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;

/* Sync now (syncNow): jars are locked at menu time, so updates stage for next boot.
   Code 6 reports already current with nothing staged to avoid pointless reboots. */
public final class SyncItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.act("sync", "Sync Now",
                "Fetch the version the server is broadcasting. Version will be applied on next boot.",
                RepairGate.FILE_SAFE,
                state -> state.isPinned()
                        ? "Syncs to " + state.pinned() + " (the release lock)"
                        : "Syncs to whatever the server is broadcasting",
                "Sync BAMFOS now?",
                ops -> RepairResult.ofSync(ops.syncNow()));
    }

    @Override
    public int order() {
        return 30;
    }
}
