package com.retro.bamfmenu.early.core.repair.items.notes.page;

import com.retro.bamfmenu.early.core.repair.api.PageRow;
import com.retro.bamfmenu.early.core.repair.api.RepairPage;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.view.RepairBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Release notes view, wrapped and clipped to the panel width so long lines cannot break the border.
 * Rows are non-selectable, so RepairSession automatically maps UP/DOWN to scrolling.
 */
public final class NotesPage implements RepairPage {

    @Override
    public String title() {
        return "Release notes";
    }

    @Override
    public List<PageRow> rows(UpdaterState state, int width) {
        List<PageRow> out = new ArrayList<>();
        if (state.notes().isBlank()) {
            out.add(PageRow.text("No notes cached for this version.", Tone.DIM));
            return out;
        }
        for (String line : RepairBox.wrap(state.notes().replace("\r", ""), Math.max(10, width))) {
            out.add(PageRow.text(RepairBox.clip(line, Math.max(10, width))));
        }
        return out;
    }
}
