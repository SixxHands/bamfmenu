package com.retro.bamfmenu.early.core.secret.secrets.doom.game;

import com.retro.bamfmenu.early.core.util.Figlet;
import java.util.ArrayList;
import java.util.List;

/* Headless layout for the DOOM menus: turn (items, selection, terminal cols x rows) into a flat list of draw-ops,
   (col, row, text, kind), which the BIOS maps to colored TerminalCanvas.text calls. Kept in core/ (no GL) so the
   SAME logic can be rasterized to a PNG offline (DoomMenuProbe) and eyeballed before it ever reaches the user's window.

   ONE consistent style for every DOOM menu (the Options-menu look): the big DOOM-logo FIGlet banner up top + a
   clean native 1-line item list centered below it. Earlier big-FIGlet-item and 2-column experiments were dropped;
   all menus stay uniform. Native labels are short (the longest real DOOM item is ~17 chars) so they never approach
   the ~107-col width; the only tight axis is HEIGHT, so the banner shrinks (Doom h8 -> small h5 -> a 1-line header)
   if a menu ever has so many items the big banner wouldn't leave room. */
public final class DoomMenuLayout {

    public enum Kind { BANNER, HEADER, ITEM, ITEM_SELECTED }

    /** One text fragment to draw at a character cell. row/col are in native cells (multiply by cell size to get px). */
    public record Op(int col, int row, String text, Kind kind) { }

    private DoomMenuLayout() { }

    public static List<Op> layout(String[] items, int sel, int availCols, int availRows) {
        List<Op> ops = new ArrayList<>();
        if (items == null || items.length == 0) {
            return ops;
        }
        int n = items.length;

        // Banner sized to the rows the item list leaves: Poison (the big textured @!:. logo) when there's room, else
        // Doom, else small, else a 1-line header. For real DOOM menus (<= ~10 short items) this is always Poison.
        int leftover = availRows - n - 1;
        Figlet bannerFont = pickBanner(leftover);
        String[] banner = bannerFont != null ? bannerFont.render("DOOM") : null;
        int headRows;
        if (banner != null) {
            for (int r = 0; r < banner.length; r++) {
                ops.add(new Op(Math.max(0, (availCols - banner[r].length()) / 2), 1 + r, banner[r], Kind.BANNER));
            }
            headRows = banner.length + 1;
        } else {
            String hdr = "===  D O O M  ===";
            ops.add(new Op(Math.max(0, (availCols - hdr.length()) / 2), 1, hdr, Kind.HEADER));
            headRows = 2;
        }

        int top = 1 + headRows;
        int areaRows = Math.max(1, availRows - top - 1);
        int y = top + Math.max(0, (areaRows - n) / 2); // center the list (incl. any spacer rows) below the banner
        for (int i = 0; i < n; i++) {
            String item = items[i] == null ? "" : items[i];
            if (item.isEmpty()) {
                continue; // a status==-1 spacer row (Options/Sound) -> leave a blank gap, never decorate a cursor here
            }
            String label = (i == sel) ? "►  " + item + "  ◄" : item;
            Kind k = (i == sel) ? Kind.ITEM_SELECTED : Kind.ITEM;
            ops.add(new Op(Math.max(0, (availCols - label.length()) / 2), y + i, label, k));
        }
        return ops;
    }

    /* Pick the tallest banner font whose ASCII art still leaves room for the n-item list + a 1-row top and bottom
       margin. leftover = availRows - n - 1; a font of height h needs leftover >= h + 2 (the +1 gap below the banner
       + the 1-row bottom margin). Falls through to the next tier if a font is missing from the jar. */
    private static Figlet pickBanner(int leftover) {
        if (leftover >= 14) { // Poison h12
            Figlet f = Figlet.font("Poison");
            if (f != null) {
                return f;
            }
        }
        if (leftover >= 10) { // Doom h8
            Figlet f = Figlet.font("Doom");
            if (f != null) {
                return f;
            }
        }
        if (leftover >= 7) { // small h5
            Figlet f = Figlet.font("small");
            if (f != null) {
                return f;
            }
        }
        return null; // 1-line header
    }
}
