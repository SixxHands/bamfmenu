package com.retro.bamfmenu.early.core.repair.items.lock;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;

/* Clears a release lock without staging a sync; unpinned installs update normally on the next boot.
   Uses a dedicated unpin op instead of overloading syncTo(""). */
public final class ClearLockItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.act("clearlock", "Remove Release Lock",
                "Drop the version pin so BAMFOS follows the server's broadcast again.",
                RepairGate.all(RepairGate.FILE_SAFE, ClearLockItem::whyNot),
                state -> "Locked to " + state.pinned() + ". BAMFOS is not following the server.",
                "Clear the release lock?",
                ops -> RepairResult.ofUnpin(ops.unpin()))
                // Pins are user-set so we don't pop a dialog, but they block updates; highlight the row red as a reminder.
                .alerting(UpdaterState::isPinned);
    }

    /* If status() couldn't be read, we don't know whether a pin is set. Disabling this row with
       "nothing pinned" would be misleading if a tag= pin actually exists and the updater keeps using it. */
    private static String whyNot(UpdaterState state) {
        if (state.isPinned()) {
            return "";
        }
        return state.statusUnreadable() ? "Could not read the release lock."
                : "Nothing pinned. BAMFOS tracks the server.";
    }

    @Override
    public int order() {
        return 70;
    }
}
