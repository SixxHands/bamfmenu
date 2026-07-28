package com.retro.bamfos.updater.engine.console;

import java.util.Locale;

// Single-line download progress bar for interactive server terminals (disabled when stdout is piped or logged to a file).
public final class ConsoleBar {

    /** Fits the whole line inside 78 columns, so an 80-wide console never wraps a redraw into two rows. */
    private static final int WIDTH = 28;
    private static final int NAME = 22;

    private final boolean live;
    private int lastPct = -1;
    private int painted;   // width of the line currently on screen so finish() can clear it

    private ConsoleBar(boolean live) {
        this.live = live;
    }

    /** Draws a progress bar when attached to an interactive terminal; stays silent if stdout is piped. */
    public static ConsoleBar forThisProcess() {
        return new ConsoleBar(System.console() != null);
    }

    public static ConsoleBar silent() {
        return new ConsoleBar(false);
    }

    /** Repaints progress for `done` of `total` bytes. Does nothing when total < 0 (unknown length). */
    public void at(String asset, long done, long total) {
        if (!live || total <= 0) {
            return;
        }
        int pct = pct(done, total);
        if (pct == lastPct) {
            return; // the caller polls ~4Hz; a bar 28 cells wide cannot show more than whole percents
        }
        lastPct = pct;
        String line = render(asset, done, total, pct);
        painted = line.length();
        System.out.print("\r" + line);
        System.out.flush();
    }

    /** Wipe the bar so the next ordinary log line does not land on top of its tail. Safe to call always. */
    public void finish() {
        if (!live || painted == 0) {
            lastPct = -1;
            return;
        }
        System.out.print("\r" + " ".repeat(painted) + "\r");
        System.out.flush();
        painted = 0;
        lastPct = -1;
    }

    static int pct(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        // clamp both ways: a resumed download can report more bytes than advertised, and >100% would pass
        // a negative count to repeat() below
        return (int) Math.min(100, Math.max(0, done * 100 / total));
    }

    static String render(String asset, long done, long total, int pct) {
        int fill = WIDTH * pct / 100;
        return String.format(Locale.ROOT, "  %-" + NAME + "s [%s%s] %3d%%  %s/%s %s",
                clip(asset), "#".repeat(fill), ".".repeat(WIDTH - fill), pct,
                num(done, total), num(total, total), unit(total));
    }

    /** Keep the end of the filename since the version at the tail is what usually differs between jars. */
    static String clip(String name) {
        return name.length() <= NAME ? name : ".." + name.substring(name.length() - (NAME - 2));
    }

    /** Formats "done/total UNIT" using one divisor derived from `scale` (the total) so the unit stays
     *  consistent across both numbers and does not jump mid-transfer. */
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
