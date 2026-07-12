package com.retro.bamfmenu.early.bios.modes.minigame;

import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.core.secret.secrets.doom.game.DoomAscii;
import com.retro.bamfmenu.early.core.secret.secrets.doom.game.DoomEngine;
import com.retro.bamfmenu.early.core.secret.secrets.doom.game.DoomHudLayout;
import com.retro.bamfmenu.early.core.secret.secrets.doom.game.DoomMenuLayout;
import com.retro.bamfmenu.early.core.secret.secrets.doom.game.DoomState;

import java.util.Queue;

/* Mode.DOOM host: the hidden "doom" secret. Runs the REAL vendored DOOM engine headless on a daemon
   thread and draws its framebuffer as colored ASCII on the terminal. Keys feed DOOM except Esc, which
   always means "leave to the BIOS"; Tab is remapped (in DoomEngine) to DOOM's own Esc so its in-game
   menu doesn't collide with ours. */
public final class DoomScreen implements Screen {

    private static final int KEY_ESCAPE = 256;
    private static final int ACTION_PRESS = 1; // GLFW_PRESS (RELEASE = 0)
    // the game area renders at GRID_DENSITY x the native terminal cell count (smaller font, more detail), DOOM-only
    private static final int GRID_DENSITY = 2;
    // dense rows for the status bar attached under the game box, matching real DOOM's bar:view ratio
    private static final int HUD_ROWS = 8;
    // status-bar colors as ABGR (0xAABBGGRR, like TerminalCanvas): DOOM-red numbers + the three key colors
    private static final int HUD_RED = 0xFF3040FF; // (255, 64, 48)
    private static final int HUD_KEY_BLUE = 0xFFFF8246; // (70, 130, 255)
    private static final int HUD_KEY_YELLOW = 0xFF32E1FF; // (255, 225, 50)
    private static final int HUD_KEY_RED = 0xFF3C46FF; // (255, 70, 60)

    private final Queue<int[]> input;
    private DoomEngine engine;
    private long startNanos;

    public DoomScreen(Queue<int[]> input) {
        this.input = input;
    }

    @Override
    public Mode mode() {
        return Mode.DOOM;
    }

    @Override
    public void onEnter(BiosContext ctx) {
        input.clear();
        engine = new DoomEngine();
        engine.setSaveDir(java.nio.file.Paths.get(ctx.bamfosDir(), "doom")); // saves live with the rest of BAMFOS
        engine.start(); // non-blocking; render() shows a loading screen until ready()
        startNanos = System.nanoTime();
        ctx.enterMode(Mode.DOOM);
    }

    @Override
    public void onExit(BiosContext ctx) {
        exit(ctx);
    }

    @Override
    public void step(BiosContext ctx) {
        DoomEngine e = engine;
        if (e == null) {
            exit(ctx);
            return;
        }
        if (e.failed() || !e.isRunning()) { // boot error, or DOOM's own "Quit Game" stopped the loop -> back to BIOS
            exit(ctx);
            return;
        }
        int[] ev;
        while ((ev = input.poll()) != null) {
            int key = ev[0];
            boolean down = ev[1] == ACTION_PRESS;
            if (key == KEY_ESCAPE) {
                if (down) {
                    exit(ctx); // Esc ALWAYS leaves DOOM for the BIOS (Tab is the in-game menu key)
                    return;
                }
                continue;
            }
            e.postGlfwKey(key, down);
        }
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return "<- -> Turn   ^ v Move   CTRL Fire   SPACE Use   1-7 Wpn   TAB Menu   ESC Bios";
    }

    private void exit(BiosContext ctx) {
        DoomEngine e = engine;
        engine = null;
        if (e != null) {
            e.stop();
        }
        input.clear();
        ctx.exitToSetup(2, "DOOM"); // land on the Safe-For-Retro row where the secret box lives
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        DoomEngine e = engine;
        if (e == null) {
            return;
        }
        int nativeW = c.logicalW(), nativeH = c.logicalH();
        c.beginFrameShader(nativeW, nativeH); // native size for the title (+ loading text)
        int cw = Math.max(1, c.charWidth());
        int ls = Math.max(1, c.lineSpacing());
        c.text(c.margin(), ls, "D O O M", c.cGold());

        DoomEngine.Frame f = e.ready() ? e.frame() : null;
        if (f == null) {
            double secs = (System.nanoTime() - startNanos) / 1.0e9;
            String dots = ".".repeat(1 + ((int) (secs * 2)) % 3);
            c.text(c.margin(), ls * 3, "BOOTING DOOM" + dots, c.cBright());
            c.text(c.margin(), ls * 4, "can it run doom? yes.", c.cDim());
            return;
        }

        // Read DOOM's live state so we can draw a crisp HUD + menu as terminal text; DOOM's own pixel HUD/menu fonts are unreadable downsampled to ASCII.
        DoomState.Snapshot st = e.hud();

        // Special menu states first (the game ASCII is skipped for all of these = clean black backdrop):
        // a confirm/info PROMPT (quit/end-game/nightmare...) -> show the prompt text, NOT the stale menu behind it.
        if (st.message() != null) {
            drawMessage(c, st.message(), nativeW, nativeH, cw, ls);
            return;
        }
        // a READ THIS! help page (a full-screen image in DOOM) -> a readable placeholder, not a phantom "[ EMPTY ]".
        if (st.menuActive() && st.helpScreen()) {
            drawHelp(c, nativeW, nativeH, cw, ls);
            return;
        }
        // an ordinary menu -> the big DOOM-banner + native list.
        if (st.menuActive() && st.menuItems() != null && st.menuItems().length > 0) {
            drawMenu(c, st, nativeW, nativeH, cw, ls);
            return;
        }

        /* Draw the game in a DENSER (smaller-font) logical canvas so it reads as DOOM, then restore the
           native size so the title (already drawn) and the provider's control hints stay full-size. LOCAL
           remap, not global. The beauty of this being a canvas is that we can resize anything we want!
           However... that still doesn't fix the games hud from being legible in ascii rendering, so that
           gets cropped out below and redrawn as our own. Resize by remapping the canvas, never by sizing
           the characters to fit the region, doing it by characters crunched everything into giant
           overlapping chunks. */
        int gw = nativeW * GRID_DENSITY, gh = nativeH * GRID_DENSITY;
        c.logicalSize(gw, gh);
        try {
            int availCols = Math.max(1, gw / cw);
            int availRows = Math.max(1, gh / ls);
            int topRows = 3 * GRID_DENSITY; // clear the native-size title above
            // reserve below the box: the DENSE status bar (in a level; +1 gap row) and the provider's
            // native-size control-hints line (2 native rows)
            int botRows = (st.inLevel() ? HUD_ROWS + 1 : 0) + 2 * GRID_DENSITY;
            int boxRowsAvail = Math.max(3, availRows - topRows - botRows);
            // The DOOM image sits INSIDE a 1-cell border (like Galaga/Tetris) -> reserve 2 cols + 2 rows for the frame.
            int maxInnerCols = Math.max(1, availCols - 2);
            int maxInnerRows = Math.max(1, boxRowsAvail - 2);
            /* In a level, CROP DOOM's pixel status bar (bottom ~16%) out of the ASCII (our own clean HUD
               replaces it) so the rendered region is just the 3D viewport; preserve ITS aspect on the tall
               monospace cells. */
            int srcH = st.inLevel() ? (f.height() * 84 / 100) : f.height();
            double cellAspect = ((double) f.width() / srcH) * ls / cw;
            int cols, rows;
            if ((double) maxInnerCols / maxInnerRows > cellAspect) {
                rows = maxInnerRows;
                cols = (int) Math.round(rows * cellAspect);
            } else {
                cols = maxInnerCols;
                rows = (int) Math.round(cols / cellAspect);
            }
            cols = Math.max(1, Math.min(cols, maxInnerCols));
            rows = Math.max(1, Math.min(rows, maxInnerRows));

            // Border box (drawn in the dense game canvas, like Galaga/Tetris), centered, with the image one cell inside.
            int boxCols = cols + 2;
            int boxX0 = Math.max(0, (availCols - boxCols) / 2) * cw;
            int boxY0 = topRows * ls;
            int border = c.cDim();
            String topBar = "┌" + "─".repeat(cols) + "┐";
            String botBar = "└" + "─".repeat(cols) + "┘";
            c.text(boxX0, boxY0, topBar, border);
            c.text(boxX0, boxY0 + (rows + 1) * ls, botBar, border);
            for (int by = 0; by < rows; by++) {
                c.text(boxX0, boxY0 + (by + 1) * ls, "│", border);
                c.text(boxX0 + (cols + 1) * cw, boxY0 + (by + 1) * ls, "│", border);
            }

            DoomAscii.Rendered r = DoomAscii.render(f, cols, rows, 0, srcH);
            char[] g = r.glyphs();
            int[] col = r.colors();
            int x0 = boxX0 + cw;
            int y0 = boxY0 + ls;
            StringBuilder sb = new StringBuilder(cols);
            for (int y = 0; y < rows; y++) {
                int bas = y * cols;
                int runStart = 0;
                int runColor = col[bas];
                sb.setLength(0);
                for (int x = 0; x < cols; x++) {
                    int cc = col[bas + x];
                    // flush the run when the color changes (batches flat regions -> far fewer draws)
                    if (cc != runColor) {
                        c.text(x0 + runStart * cw, y0 + y * ls, sb.toString(), runColor);
                        sb.setLength(0);
                        runStart = x;
                        runColor = cc;
                    }
                    sb.append(g[bas + x]);
                }
                if (sb.length() > 0) {
                    c.text(x0 + runStart * cw, y0 + y * ls, sb.toString(), runColor);
                }
            }

            /* Faithful DOOM status bar ATTACHED under the box, same DENSE glyph scale + same width as the box
               (real DOOM's view:bar relationship). Drawn inside the remap on purpose. */
            if (st.inLevel()) {
                drawHud(c, st, boxX0, boxY0 + (rows + 2) * ls + ls, boxCols, cw, ls);
            }
        } finally {
            // restore native so the provider's hints + later draws this frame are full-size
            c.logicalSize(nativeW, nativeH);
        }
    }

    // The layout lives in headless DoomHudLayout (PNG-previewable offline); here we just color each draw-op by its Kind.
    private void drawHud(TerminalCanvas c, DoomState.Snapshot st, int xPx, int yPx, int cols, int cw, int ls) {
        DoomHudLayout.Hud hud = new DoomHudLayout.Hud(st.health(), st.armor(), st.ammo(), st.hasAmmo(), st.weapon(),
                st.arms(), st.ammoCounts(), st.ammoMax(), st.keys());
        for (DoomHudLayout.Op op : DoomHudLayout.layout(hud, cols, HUD_ROWS, "small")) {
            int colour = switch (op.kind()) {
                case BIGNUM -> HUD_RED;
                case LABEL -> c.cGold();
                case ARMS_ON -> c.cBright();
                case ARMS_OFF -> c.cDim();
                case STAT -> c.cFg();
                case KEY_BLUE -> HUD_KEY_BLUE;
                case KEY_YELLOW -> HUD_KEY_YELLOW;
                case KEY_RED -> HUD_KEY_RED;
                case BORDER -> c.cDim();
            };
            c.text(xPx + op.col() * cw, yPx + op.row() * ls, op.text(), colour);
        }
    }

    // The layout (banner font + placement) lives in headless DoomMenuLayout (PNG-previewable offline); here we just color each draw-op.
    private void drawMenu(TerminalCanvas c, DoomState.Snapshot st, int nativeW, int nativeH, int cw, int ls) {
        int availCols = Math.max(1, nativeW / cw);
        int availRows = Math.max(1, nativeH / ls);
        for (DoomMenuLayout.Op op : DoomMenuLayout.layout(st.menuItems(), st.menuSel(), availCols, availRows)) {
            int colour = switch (op.kind()) {
                case ITEM_SELECTED -> c.cBright();
                case BANNER, HEADER, ITEM -> c.cGold();
            };
            c.text(op.col() * cw, op.row() * ls, op.text(), colour);
        }
    }

    private void drawMessage(TerminalCanvas c, String msg, int nativeW, int nativeH, int cw, int ls) {
        int availCols = Math.max(1, nativeW / cw);
        int availRows = Math.max(1, nativeH / ls);
        String[] lines = msg.split("\n", -1);
        int y = Math.max(1, (availRows - lines.length) / 2);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int x = Math.max(0, (availCols - line.length()) / 2) * cw;
            c.text(x, (y + i) * ls, line, c.cBright());
        }
    }

    // The HELP/CREDIT lumps are full-screen pictures; without this the help menu's lone blank item renders as a phantom "[ EMPTY ]".
    private void drawHelp(TerminalCanvas c, int nativeW, int nativeH, int cw, int ls) {
        int availCols = Math.max(1, nativeW / cw);
        int availRows = Math.max(1, nativeH / ls);
        String[] lines = {
                "READ THIS!",
                "",
                "DOOM's help pages are full-screen pictures,",
                "not something a terminal can show.",
                "",
                "CONTROLS:  arrows move/turn   CTRL fire   SPACE use   1-7 weapons",
                "",
                "(press a key to go back)",
        };
        int y = Math.max(1, (availRows - lines.length) / 2);
        for (int i = 0; i < lines.length; i++) {
            int colour = (i == 0) ? c.cGold() : c.cFg();
            int x = Math.max(0, (availCols - lines[i].length()) / 2) * cw;
            c.text(x, (y + i) * ls, lines[i], colour);
        }
    }
}
