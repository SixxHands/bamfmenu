package com.retro.bamfmenu.early.core.repair.api;

/**
 * One row drawn into the right panel, plus whether ENTER activates it.
 * Built fresh from the snapshot each frame; row index is its identity for activate().
 */
public record PageRow(String text, Tone tone, boolean selectable) {

    /** Non-selectable body text (heading, paragraph, or warning). */
    public static PageRow text(String text) {
        return new PageRow(text, Tone.NORMAL, false);
    }

    /** Non-selectable body text with a specific tone. */
    public static PageRow text(String text, Tone tone) {
        return new PageRow(text, tone, false);
    }

    /** A row ENTER acts on. */
    public static PageRow pick(String text) {
        return new PageRow(text, Tone.NORMAL, true);
    }

    /** A blank spacer row. */
    public static PageRow gap() {
        return new PageRow("", Tone.NORMAL, false);
    }
}
