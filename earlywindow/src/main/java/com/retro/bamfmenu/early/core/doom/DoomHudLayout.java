package com.retro.bamfmenu.early.core.doom;

import java.util.ArrayList;
import java.util.List;

/* Headless layout for a faithful ASCII recreation of DOOM's bottom STATUS BAR, drawn into the same screen region we
   already crop DOOM's own (mush) status bar out of. Same pattern as DoomMenuLayout: pure (data + region cols x rows)
   -> draw-ops, so the bar can be rasterised to a PNG offline (DoomHudProbe) and eyeballed before it hits the terminal.

   HYBRID look, matching the original: the big VALUES (ammo / health% / armor%) are FIGlet art (the mini font, the one
   the menu items used) so they read like DOOM's giant red numbers; the small bits (panel labels, the ARMS 2-7 grid,
   the BULL/SHEL/RCKT/CELL list, keys) are plain native text. Panels run left->right in DOOM's order; the FACE panel is
   reserved EMPTY for now (later: drop DOOM's real downsampled mug into it). Colour is assigned by Kind at draw time. */
public final class DoomHudLayout {

    public enum Kind {
        LABEL, BIGNUM, ARMS_ON, ARMS_OFF, STAT, KEY_BLUE, KEY_YELLOW, KEY_RED, BORDER
    }

    public record Op(int col, int row, String text, Kind kind) {
    }

    /**
     * Snapshot of just the bits the bar needs. arms/ammoCounts/ammoMax/keys may be
     * null (-> shown empty).
     */
    public record Hud(int health, int armor, int ammo, boolean hasAmmo, String weapon,
            boolean[] arms, int[] ammoCounts, int[] ammoMax, boolean[] keys) {
    }

    // Panel widths as fractions of the bar (AMMO HEALTH ARMS FACE ARMOR STATS);
    // FACE is reserved empty for now.
    private static final double[] FRAC = { 0.12, 0.16, 0.17, 0.12, 0.16, 0.27 };

    private DoomHudLayout() {
    }

    public static List<Op> layout(Hud h, int cols, int rows) {
        return layout(h, cols, rows, "small"); // match what DoomScreen ships, so the offline probe previews the real
                                               // thing
    }

    public static List<Op> layout(Hud h, int cols, int rows, String numFont) {
        List<Op> ops = new ArrayList<>();
        if (h == null || cols < 24 || rows < 5) {
            return ops;
        }
        int[] x = new int[FRAC.length + 1];
        double acc = 0;
        for (int i = 0; i < FRAC.length; i++) {
            x[i] = (int) Math.round(acc * cols);
            acc += FRAC[i];
        }
        x[FRAC.length] = cols;

        // beveled-panel frame: top + bottom rule, vertical separators between panels
        ops.add(new Op(0, 0, "─".repeat(cols), Kind.BORDER));
        ops.add(new Op(0, rows - 1, "─".repeat(cols), Kind.BORDER));
        for (int i = 1; i < FRAC.length; i++) {
            for (int r = 0; r < rows; r++) {
                ops.add(new Op(x[i], r, "│", Kind.BORDER));
            }
        }

        Figlet num = Figlet.font(numFont);
        int top = 1, bot = rows - 2; // inclusive content rows

        bigNumPanel(ops, num, h.hasAmmo() ? String.valueOf(h.ammo()) : "", "", "AMMO", x[0], x[1], top, bot);
        bigNumPanel(ops, num, String.valueOf(h.health()), "%", "HEALTH", x[1], x[2], top, bot);
        armsPanel(ops, h.arms(), x[2], x[3], top, bot);
        // FACE panel x[3]..x[4] -- intentionally empty (reserved for the real
        // downsampled mug later)
        bigNumPanel(ops, num, String.valueOf(h.armor()), "%", "ARMOR", x[4], x[5], top, bot);
        statsPanel(ops, h, x[5], x[6], top, bot);
        return ops;
    }

    /**
     * A DOOM-style panel: a big FIGlet number (digits only) + a small native suffix
     * (e.g. "%") over a small label,
     * the block centred in the panel. The "%" stays native so the readable wide
     * digit font doesn't overflow.
     */
    private static void bigNumPanel(List<Op> ops, Figlet num, String digits, String suffix, String label, int x0,
            int x1, int top, int bot) {
        int w = x1 - x0;
        String[] art = (!digits.isEmpty() && num != null) ? num.render(digits) : new String[0];
        int aw = Figlet.width(art);
        int totalW = aw + suffix.length();
        int blockH = art.length + 1; // value rows + the label row
        int start = top + Math.max(0, ((bot - top + 1) - blockH) / 2);
        int ax = x0 + Math.max(0, (w - totalW) / 2);
        for (int r = 0; r < art.length && (start + r) <= bot; r++) {
            ops.add(new Op(ax, start + r, art[r], Kind.BIGNUM));
        }
        if (!suffix.isEmpty() && art.length > 0) {
            ops.add(new Op(ax + aw, Math.min(bot, start + art.length - 1), suffix, Kind.BIGNUM));
        }
        int lr = Math.min(bot, start + Math.max(1, art.length));
        ops.add(new Op(x0 + Math.max(0, (w - label.length()) / 2), lr, label, Kind.LABEL));
    }

    /**
     * ARMS weapons 2..7 as a boxed 2x3 grid (the original's beveled number cells);
     * each digit lit if owned, dim if not.
     */
    private static void armsPanel(List<Op> ops, boolean[] arms, int x0, int x1, int top, int bot) {
        int w = x1 - x0;
        String topB = "┌─┬─┬─┐", midB = "├─┼─┼─┤", botB = "└─┴─┴─┘";
        int tx = x0 + Math.max(0, (w - topB.length()) / 2);
        int start = top + Math.max(0, ((bot - top + 1) - 6) / 2); // 5 table rows + the label row
        ops.add(new Op(tx, start, topB, Kind.BORDER));
        armsRow(ops, new int[] { 2, 3, 4 }, arms, tx, start + 1);
        ops.add(new Op(tx, start + 2, midB, Kind.BORDER));
        armsRow(ops, new int[] { 5, 6, 7 }, arms, tx, start + 3);
        ops.add(new Op(tx, start + 4, botB, Kind.BORDER));
        ops.add(new Op(x0 + Math.max(0, (w - 4) / 2), Math.min(bot, start + 5), "ARMS", Kind.LABEL));
    }

    private static void armsRow(List<Op> ops, int[] nums, boolean[] arms, int tx, int row) {
        ops.add(new Op(tx, row, "│", Kind.BORDER));
        for (int c = 0; c < nums.length; c++) {
            int n = nums[c];
            boolean owned = arms != null && n < arms.length && arms[n];
            ops.add(new Op(tx + 1 + c * 2, row, String.valueOf(n), owned ? Kind.ARMS_ON : Kind.ARMS_OFF));
            ops.add(new Op(tx + 2 + c * 2, row, "│", Kind.BORDER));
        }
    }

    /**
     * Colour keys (blue/yellow/red, card OR skull) stacked next to the armour, then
     * the spaced ammo-type list.
     */
    private static void statsPanel(List<Op> ops, Hud h, int x0, int x1, int top, int bot) {
        boolean[] k = h.keys();
        int kx = x0 + 1;
        if (has(k, 0) || has(k, 3)) {
            ops.add(new Op(kx, top, "█", Kind.KEY_BLUE));
        }
        if (has(k, 1) || has(k, 4)) {
            ops.add(new Op(kx, top + 1, "█", Kind.KEY_YELLOW));
        }
        if (has(k, 2) || has(k, 5)) {
            ops.add(new Op(kx, top + 2, "█", Kind.KEY_RED));
        }
        String[] names = { "BULL", "SHEL", "RCKT", "CELL" };
        int[] idx = { 0, 1, 3, 2 }; // am_clip, am_shell, am_misl, am_cell
        int[] cnt = h.ammoCounts(), max = h.ammoMax();
        int lx = x0 + 4; // leave the key column on the left
        int row = top;
        for (int i = 0; i < names.length && row <= bot; i++, row++) {
            int c = (cnt != null && idx[i] < cnt.length) ? cnt[idx[i]] : 0;
            int m = (max != null && idx[i] < max.length) ? max[idx[i]] : 0;
            ops.add(new Op(lx, row, String.format("%s  %3d / %3d", names[i], c, m), Kind.STAT));
        }
    }

    private static boolean has(boolean[] a, int i) {
        return a != null && i < a.length && a[i];
    }
}
