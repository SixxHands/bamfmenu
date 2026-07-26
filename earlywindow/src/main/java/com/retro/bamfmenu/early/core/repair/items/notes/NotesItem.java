package com.retro.bamfmenu.early.core.repair.items.notes;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairStatus;
import com.retro.bamfmenu.early.core.repair.items.notes.page.NotesPage;

/* Release notes page (backed by releaseNotes). Named "Release Notes" instead of "What's New"
   because it always shows the cached notes for the installed release on every boot. */
public final class NotesItem implements RepairDef {

    @Override
    public RepairItem item() {
        return RepairItem.page("notes", "Release Notes",
                "What the installed release says it changed.",
                state -> state.notes().isBlank() ? "No notes cached for this version." : "",
                RepairStatus.NONE,
                new NotesPage());
    }

    @Override
    public int order() {
        return 50;
    }
}
