package com.retro.bamfmenu.early.core.repair.items.optional.page;

import com.retro.bamfmenu.early.core.repair.api.PageRow;
import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairPage;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.view.RepairBox;

import java.util.ArrayList;
import java.util.List;

// Optional/quarantined mod picker. Flips mods through the engine (never renames directly) with no confirm popup toggle with keypress.
public final class OptionalPage implements RepairPage {

    @Override
    public String title() {
        return "Optional mods";
    }

    @Override
    public String hints() {
        return "UP/DOWN  Select      ENTER  Toggle      ESC  Back      F2  Volume";
    }

    @Override
    public List<PageRow> rows(UpdaterState state, int width) {
        List<PageRow> out = new ArrayList<>();
        for (Entry e : entries(state, width)) {
            out.add(e.row());
        }
        return out;
    }

    @Override
    public RepairEffect.Act activate(int index, UpdaterState state, int width) {
        List<Entry> entries = entries(state, width);
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        UpdaterState.Mod mod = entries.get(index).mod();
        if (mod == null) {
            return null; // a reason line, nothing to flip
        }
        if (mod.missing()) {
            return null; // neither name on disk, so there is no rename to make (the row says so)
        }
        boolean turningOff = !mod.disabled();
        String working = (turningOff ? "Turning off " : "Turning on ") + mod.name();
        return new RepairEffect.Act(working, "",
                ops -> RepairResult.ofToggle(ops.setDisabled(mod.path(), turningOff), mod.name(), turningOff));
    }

    /* One list built once and read two ways, so the row a player is looking at and the mod ENTER flips
       can never be a different mod: rows() takes the row, activate() takes the mod at the same index. */
    private record Entry(PageRow row, UpdaterState.Mod mod) {
    }

    private static List<Entry> entries(UpdaterState state, int width) {
        List<Entry> out = new ArrayList<>();
        int cell = Math.max(10, width - 2); // the two columns the selection cursor takes
        for (UpdaterState.Mod mod : state.optional()) {
            // a jar with neither name on disk is not "on": the row would claim a mod is loaded that is not
            // on the box, and the toggle under it can only come back as "See the log."
            String cellText = mod.missing() ? "missing" : mod.disabled() ? "off" : "on";
            String text = RepairBox.dotted(mod.name(), cellText, cell);
            out.add(new Entry(mod.missing() ? PageRow.text(text, Tone.DIM) : PageRow.pick(text), mod));
            if (mod.missing()) {
                for (String line : RepairBox.wrap("not in mods/, Verify and Repair Files puts it back.", cell - 2)) {
                    out.add(new Entry(PageRow.text("  " + line, Tone.DIM), null));
                }
            }
            if (mod.quarantined() && !mod.reason().isEmpty()) {
                for (String line : RepairBox.wrap("pulled: " + mod.reason(), cell - 2)) {
                    out.add(new Entry(PageRow.text("  " + line, Tone.DIM), null));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new Entry(PageRow.text("Nothing optional in this release.", Tone.DIM), null));
        }
        return out;
    }
}
