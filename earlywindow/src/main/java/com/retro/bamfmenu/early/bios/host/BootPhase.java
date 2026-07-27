package com.retro.bamfmenu.early.bios.host;

// Passes FML's boot progress labels through verbatim so players see the same words they'd find in latest.log if boot hangs.
public final class BootPhase {

    /** Set once FML asks for the window (setupMinecraftWindow), which is the last thing that happens on
     *  this screen. No label is pushed after that, so without this the log's last row would sit on a
     *  stale label for the whole BIOS hold and the POST exit grace. This one ADDS a fact FML never
     *  labels rather than rewording one it does, which is the only kind of line that earns a place here. */
    public static final String HANDOFF = "Handing off to game";

    /** The phase the terminal shows, hand-off included. handingOff OUTRANKS the label on purpose: FML pushes
     *  nothing after asking for the window, so the label still standing is the one that just finished. */
    public static String phase(boolean handingOff, String label) {
        return handingOff ? HANDOFF : render(label);
    }

    /** FML's label, untouched. Null-safe and trimmed, because the terminal draws this straight into a row. */
    public static String render(String label) {
        return label == null ? "" : label.trim();
    }

    private BootPhase() {
    }
}
