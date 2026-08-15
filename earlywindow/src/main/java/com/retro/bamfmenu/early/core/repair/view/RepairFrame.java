package com.retro.bamfmenu.early.core.repair.view;

import com.retro.bamfmenu.early.core.repair.api.Tone;

import java.util.List;

/**
 * Pre-clipped render frame for UPDATE AND REPAIR (pure data, no GL or Minecraft deps).
 * `rows` covers the interior only; border strings stay separate so divider rows can render rules.
 */
public record RepairFrame(String title, String topBar, List<Row> rows, String bottomBar,
                          String hints, List<String> prompt, boolean promptAlert) {

    /** The shape every frame had before a confirm could be an alert. */
    public RepairFrame(String title, String topBar, List<Row> rows, String bottomBar,
                       String hints, List<String> prompt) {
        this(title, topBar, rows, bottomBar, hints, prompt, false);
    }

    /** One interior row: the menu cell on the left, the panel cell on the right. */
    public record Row(String left, Tone leftTone, String right, Tone rightTone, boolean divider) {

        /** A plain content row. */
        public static Row of(String left, Tone leftTone, String right, Tone rightTone) {
            return new Row(left, leftTone, right, rightTone, false);
        }

        /** The ├──┤ split under the help text, where SETUP puts its state line. */
        public static Row divider(String left, Tone leftTone) {
            return new Row(left, leftTone, "", Tone.NORMAL, true);
        }
    }

    /** True when a confirmation dialog is open and capturing input. */
    public boolean prompting() {
        return !prompt.isEmpty();
    }
}
