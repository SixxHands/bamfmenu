package com.retro.bamfmenu.early.bios.host;

// Passes FML's boot progress labels through verbatim so players see the same words they'd find in latest.log if boot hangs.
public final class BootPhase {

    /** Set once FML asks for the window (setupMinecraftWindow), which is the last thing that happens on
     *  this screen. No label is pushed after that, so without this the log's last row would sit on a
     *  stale label for the whole BIOS hold and the POST exit grace. This one ADDS a fact FML never
     *  labels rather than rewording one it does, which is the only kind of line that earns a place here. */
    /* FML's OWN last label, matched exactly, casing included. This said "Handing off to game" and observed on the live boot screen: hand-off is OUR word for the ImmediateWindowProvider contract, and a
       friend reading the last row of a boot that hung has never heard it and cannot search for it. Same
       mistake the class comment above records, one line further down.
       Borrowing FML's wording rather than inventing a synonym is the point: this row still ADDS a fact, because
       FML pushes no label at all once it has asked for the window, so without it the row goes stale. */
    public static final String HANDOFF = "Launching minecraft";

    /**
     * The row a player reads while a BIOS surface is closing the game for them. Startup still has to finish
     * before Minecraft's own loop can take the quit, so the honest thing to say is that it is on its way out,
     * not on its way in. Without this the last row sat on "Launching Minecraft" through the whole wait, which
     * reads as the boot ignoring the box that just promised to close.
     */
    public static final String CLOSING = "Closing minecraft";

    /**
     * The phase the terminal shows. closing OUTRANKS hand-off, and hand-off outranks the label: FML pushes
     * nothing after asking for the window, so the label still standing is the one that just finished, and a
     * queued quit is truer than either of them.
     */
    public static String phase(boolean closing, boolean handingOff, String label) {
        if (closing) {
            return CLOSING;
        }
        return handingOff ? HANDOFF : render(label);
    }

    /** The ordinary boot, where nothing has asked to quit. Kept so every caller and every test that only
     *  cares about the two original phases reads the same as it always did. */
    public static String phase(boolean handingOff, String label) {
        return phase(false, handingOff, label);
    }

    /** FML's label, untouched. Null-safe and trimmed, because the terminal draws this straight into a row. */
    public static String render(String label) {
        return label == null ? "" : label.trim();
    }

    private BootPhase() {
    }
}
