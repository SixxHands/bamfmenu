package com.retro.bamfmenu.early.core.updater;

import java.util.Locale;

/**
 * ASCII progress bar (`#` over `.`) returning the row text and green prefix length for two-pass drawing.
 * Duplicated from the updater console bar so earlywindow doesn't depend on updater classes.
 */
public final class ProgressBar {

    /** Rendered bar text and how many leading characters should be drawn filled. */
    public record Row(String text, int fill) {
    }

    private ProgressBar() {
    }

    /**
     * Formats a byte progress bar aligned under the Fetching value column.
     * Returns empty when `total <= 0` so unknown lengths don't draw a fake bar.
     */
    public static Row of(int indent, int width, long done, long total) {
        if (total <= 0 || width <= 0) {
            return null;
        }
        // Clamp both ends: a resumed download can exceed the reported size, and an oversized fill
        // would pass a negative count to repeat() and crash the boot window.
        int pct = (int) Math.min(100, Math.max(0, done * 100 / total));
        int filled = width * pct / 100;
        String pad = " ".repeat(Math.max(0, indent));
        String text = String.format(Locale.ROOT, "%s[%s%s] %3d%%  %s/%s %s",
                pad, "#".repeat(filled), ".".repeat(width - filled), pct,
                num(done, total), num(total, total), unit(total));
        // +1 includes '[' in the green prefix once there is progress; at 0% the bracket stays gray.
        return new Row(text, filled == 0 ? 0 : pad.length() + 1 + filled);
    }

    /** Scales both values using `total`'s unit and prints the unit once so numbers stay steady across the download. */
    static String num(long bytes, long scale) {
        return String.format(Locale.ROOT, scale >= 1024L ? "%.1f" : "%.0f", bytes / (double) divisor(scale));
    }

    static String unit(long scale) {
        if (scale >= 1024L * 1024L * 1024L) {
            return "GB";
        }
        if (scale >= 1024L * 1024L) {
            return "MB";
        }
        return scale >= 1024L ? "KB" : "B";
    }

    private static long divisor(long scale) {
        if (scale >= 1024L * 1024L * 1024L) {
            return 1024L * 1024L * 1024L;
        }
        if (scale >= 1024L * 1024L) {
            return 1024L * 1024L;
        }
        return scale >= 1024L ? 1024L : 1L;
    }
}
