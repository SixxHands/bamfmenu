package com.retro.bamfmenu.early.core.repair.items.verify;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;

/* The mangled-install fix (repair): hash every managed file against the installed
   manifest and put back what drifted. It asks first, because it can restore a config the player edited by
   hand (with a .bak, but still). */
public final class VerifyItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.act("verify", "Verify and Repair Files",
                "Hash-check every managed file and put back what drifted.",
                RepairGate.FILE_SAFE,
                state -> "Last sync: " + state.lastSyncCell(),
                "Verify and repair every managed file?",
                ops -> RepairResult.ofSync(ops.repair()));
    }

    @Override
    public int order() {
        return 20;
    }
}
