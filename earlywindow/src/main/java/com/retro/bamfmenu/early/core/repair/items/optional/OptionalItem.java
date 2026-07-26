package com.retro.bamfmenu.early.core.repair.items.optional;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.items.optional.page.OptionalPage;

/* Optional-mods page, also used to re-enable quarantined jars.
   Gated on a non-empty list so releases with no optional or quarantined mods disable this row. */
public final class OptionalItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.page("optional", "Optional Mods",
                "Turn the pack's optional mods on or off, and switch a quarantined one back on.",
                // Needs FILE_SAFE even though opening the page is read-only, because every row renames a jar
                // and would just fail with "locked" during an active sync.
                RepairGate.all(RepairGate.FILE_SAFE,
                        state -> state.optional().isEmpty() ? "No optional mods in this release." : ""),
                OptionalItem::line,
                new OptionalPage());
    }

    /* listOptional returns both manifest optionals and quarantined jars in one list;
       count them separately so quarantined jars aren't double-counted as optional mods. */
    private static String line(UpdaterState state) {
        long optional = 0;
        for (UpdaterState.Mod mod : state.optional()) {
            if (!mod.quarantined()) {
                optional++;
            }
        }
        return optional + " optional, " + state.quarantinedCount() + " quarantined";
    }

    @Override
    public int order() {
        return 40;
    }
}
