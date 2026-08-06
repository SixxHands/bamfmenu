package com.retro.bamfmenu.early.core.repair.items.sync;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;

/* Sync now (syncNow()). By menu time the jars are loaded and locked, so this ALWAYS stages
   and the answer always says next boot; a row that claimed to update a running game would be lying
   about the one thing a player cares about. */
public final class SyncItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.act("sync", "Sync Now",
                "Fetch the version the server is broadcasting. Version will be applied on the next boot.",
                RepairGate.FILE_SAFE,
                state -> state.isPinned()
                        ? "Syncs to " + state.pinned() + " (the release lock)"
                        : "Syncs to whatever the server broadcasts",
                "Sync BAMFOS now?",
                ops -> RepairResult.ofSync(ops.syncNow()));
    }

    @Override
    public int order() {
        return 30;
    }
}
