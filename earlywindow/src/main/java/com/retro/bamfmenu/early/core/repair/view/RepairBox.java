package com.retro.bamfmenu.early.core.repair.view;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-column layout geometry in character columns (36 left, remainder minus 3 borders right).
 * Mirrors the provider's `drawSetup` math so switching from SETUP doesn't shift the divider; update both until extracted.
 */
public final class RepairBox {

    /** Fits "> <longest row label>", the same budget the SETUP option column uses. */
    public static final int LEFT_INNER = 36;

    private RepairBox() {
    }

    /** Column width of the right panel for a screen `maxChars` wide. */
    public static int rightInner(int maxChars) {
        return Math.max(20, maxChars - LEFT_INNER - 3);
    }

    /** Builds the top border of the frame. */
    public static String top(int maxChars) {
        return "┌" + "─".repeat(LEFT_INNER) + "┬" + "─".repeat(rightInner(maxChars)) + "┐";
    }

    /** Builds the bottom border of the frame. */
    public static String bottom(int maxChars) {
        return "└" + "─".repeat(LEFT_INNER) + "┴" + "─".repeat(rightInner(maxChars)) + "┘";
    }

    /** Truncate a line to at most `maxChars` columns. */
    public static String clip(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, Math.max(0, maxChars));
    }

    /* Word-wraps text while preserving explicit '\n' breaks. Duplicated from the provider because core/
       can't depend on bios/ and needs to compile with just the JDK for the offline renderer. */
    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || width < 1) {
            return out;
        }
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (line.length() == 0) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= width) {
                    line.append(' ').append(word);
                } else {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /**
     * Formats a `label .... value` row matching the POST screen.
     * Truncates the label instead of the right-aligned value so long jar names never clip status text.
     */
    public static String dotted(String label, String cell, int width) {
        String name = clip(label, Math.max(1, width - cell.length() - 2));
        int dots = width - name.length() - cell.length() - 2;
        return dots >= 1 ? name + " " + ".".repeat(dots) + " " + cell : clip(name + " " + cell, width);
    }
}
