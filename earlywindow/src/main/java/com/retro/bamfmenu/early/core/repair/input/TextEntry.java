package com.retro.bamfmenu.early.core.repair.input;

/**
 * Pure single-line text buffer with a movable caret (no GL or Minecraft deps).
 * `visible(width)` scrolls horizontally so the caret stays on screen for long server addresses.
 */
public final class TextEntry {

    /** Long enough for an IPv6 literal with a port and brackets, short enough that no panel can be flooded. */
    public static final int MAX = 64;

    private final StringBuilder buf = new StringBuilder();
    private final int max;
    private int caret;

    public TextEntry(String initial, int max) {
        this.max = Math.max(1, Math.min(max, MAX));
        for (int i = 0; i < initial.length() && buf.length() < this.max; i++) {
            if (printable(initial.charAt(i))) {
                buf.append(initial.charAt(i));
            }
        }
        caret = buf.length(); // start at the end, so ENTER on an unchanged value is the common one-key path
    }

    /**
     * Inserts a printable ASCII codepoint if there is room.
     * Restricted to ASCII so accepted characters always render in the terminal font atlas.
     */
    public void type(int codepoint) {
        if (buf.length() >= max || codepoint < 0x20 || codepoint > 0x7E) {
            return;
        }
        buf.insert(caret, (char) codepoint);
        caret++;
    }

    /** Backspace: removes the character before the caret. */
    public void erase() {
        if (caret > 0) {
            buf.deleteCharAt(caret - 1);
            caret--;
        }
    }

    public void left() {
        caret = Math.max(0, caret - 1);
    }

    public void right() {
        caret = Math.min(buf.length(), caret + 1);
    }

    public String value() {
        return buf.toString();
    }

    public int caret() {
        return caret;
    }

    /**
     * Returns the visible slice scrolled to keep the ASCII `_` caret in view.
     * `width` is the inner field width in cells, excluding any surrounding frame.
     */
    public String visible(int width) {
        int w = Math.max(4, width);
        StringBuilder shown = new StringBuilder(buf);
        shown.insert(caret, '_');
        if (shown.length() <= w) {
            return shown.toString();
        }
        // keep the caret in view, preferring to show what comes BEFORE it: a player typing at the end
        // needs the tail, and one scrolled off the left is text they already checked
        int start = Math.min(caret + 1 - w / 2, shown.length() - w);
        return shown.substring(Math.max(0, start), Math.max(0, start) + w);
    }

    private static boolean printable(char c) {
        return c >= 0x20 && c <= 0x7E;
    }
}
