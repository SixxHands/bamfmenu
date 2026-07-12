package com.retro.bamfmenu.early.bios.overlay;

import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Overlay;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

import java.util.Queue;

/* The universal in-game pause overlay + the key-remap sub-screen, a
   host-composited LAYER over the frozen game. Resume/Options/Quit (Options = key-remap, rhythm only);
   Quit leaves the active mode via ctx.exitCurrentGame (which routes to the screen's onExit). Extracted
   verbatim from the provider, behavior preserved. The input queue stays provider-
   owned (onKey enqueues on the main thread; this drains on the render thread); the lane keybinds stay
   provider-owned too (the remap mutates the live int[] from ctx, then ctx.save persists). Its `active`
   flag is what renderFrame gating, the onKey priority chain, and the hand-off hold loop read.

   F2/volume is intentionally still global here (provider-side); the "volume as a pause sub-item +
   F2-only-in-BIOS" rework is a deferred behavioral change, not part of this class. */
public final class PauseOverlay implements Overlay {

    /* GLFW key codes, duplicated so this class stays free of the LWJGL import (TetrisGame convention). */
    private static final int KEY_UP = 265;
    private static final int KEY_DOWN = 264;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_ESCAPE = 256;

    private static final String[] LANE_GLYPHS = {"<", "v", "^", ">"};

    private final Queue<Integer> input;
    private volatile boolean active;
    private int pauseSel;        // 0 Resume, 1 Options, 2 Quit
    private boolean optionsMenu; // the key-remap sub-screen
    private int optionsSel;      // which lane row is highlighted
    private int remapLane = -1;  // lane awaiting a new key, or -1

    public PauseOverlay(Queue<Integer> input) {
        this.input = input;
    }

    @Override
    public boolean active() {
        return active;
    }

    /* Open from any game mode: freeze + pause audio + drop any game presses queued while opening. */
    public void open(BiosContext ctx) {
        active = true;
        pauseSel = 0;
        optionsMenu = false;
        remapLane = -1;
        input.clear();
        ctx.clearGameInputQueues();
        AudioPlayer a = ctx.audio();
        if (a != null) {
            a.pause();
        }
    }

    private void close(BiosContext ctx) {
        active = false;
        optionsMenu = false;
        remapLane = -1;
        input.clear();
        ctx.clearGameInputQueues();
        AudioPlayer a = ctx.audio();
        if (a != null) {
            a.resume();
        }
    }

    /* Items by mode: Options (key-remap) is rhythm-specific, so other modes get just Resume/Quit. */
    private String[] items(BiosContext ctx) {
        return ctx.mode() == Mode.RHYTHM
                ? new String[]{"Resume", "Options", "Quit"}
                : new String[]{"Resume", "Quit"};
    }

    @Override
    public void step(BiosContext ctx) {
        Integer k;
        while ((k = input.poll()) != null) {
            boolean left = optionsMenu ? handleRemap(ctx, k) : handlePause(ctx, k);
            if (left) {
                return; // the menu was closed / handed off; stop draining
            }
        }
    }

    /* Returns true when the pause menu is left (Resume/Quit/Esc). */
    private boolean handlePause(BiosContext ctx, int k) {
        String[] it = items(ctx);
        int n = it.length;
        switch (k) {
            case KEY_UP -> pauseSel = (pauseSel + n - 1) % n;
            case KEY_DOWN -> pauseSel = (pauseSel + 1) % n;
            case KEY_ENTER, KEY_KP_ENTER -> {
                String sel = it[Math.min(pauseSel, n - 1)];
                if (sel.equals("Resume")) {
                    close(ctx);
                    return true;
                } else if (sel.equals("Options")) {
                    optionsMenu = true; // the key-remap sub-screen (rhythm only)
                    optionsSel = 0;
                    remapLane = -1;
                } else { // Quit
                    active = false;
                    optionsMenu = false;
                    input.clear();
                    ctx.clearGameInputQueues();
                    ctx.exitCurrentGame(); // Quit -> leave the active mode (closes the audio)
                    return true;
                }
            }
            case KEY_ESCAPE -> {
                close(ctx);
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    /* The key-remap sub-screen. While remapLane >= 0 the NEXT key press becomes that lane's key (Esc cancels);
       otherwise arrows navigate, Enter starts a capture, Esc returns to the pause menu. */
    private boolean handleRemap(BiosContext ctx, int k) {
        int[] laneKeys = ctx.laneKeys();
        int lanes = laneKeys.length;
        if (remapLane >= 0) {
            if (k == KEY_ESCAPE) {
                remapLane = -1;
            } else if (remapLane < lanes) {
                /* If another lane already uses this key, swap so all lanes stay on distinct keys. */
                for (int j = 0; j < lanes; j++) {
                    if (j != remapLane && laneKeys[j] == k) {
                        laneKeys[j] = laneKeys[remapLane];
                    }
                }
                laneKeys[remapLane] = k;
                remapLane = -1;
                ctx.save(); // persist the new binding
            }
            return false;
        }
        switch (k) {
            case KEY_UP -> optionsSel = (optionsSel + lanes - 1) % lanes;
            case KEY_DOWN -> optionsSel = (optionsSel + 1) % lanes;
            case KEY_ENTER, KEY_KP_ENTER -> remapLane = optionsSel;
            case KEY_ESCAPE -> optionsMenu = false; // back to the pause menu
            default -> {
            }
        }
        return false;
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        c.beginFrameShader(logicalW, logicalH);
        if (optionsMenu) {
            int[] laneKeys = ctx.laneKeys();
            int lanes = laneKeys.length;
            String[] lines = new String[lanes + 3];
            int[] cols = new int[lines.length];
            lines[0] = "REMAP KEYS";
            cols[0] = c.cBright();
            lines[1] = "";
            cols[1] = c.cFg();
            for (int i = 0; i < lanes; i++) {
                boolean sel = i == optionsSel;
                String name = (sel && remapLane == i) ? "press a key..." : ctx.keyName(laneKeys[i]);
                lines[2 + i] = (sel ? "> " : "  ") + "Lane " + LANE_GLYPHS[i % LANE_GLYPHS.length] + "    " + name;
                cols[2 + i] = sel ? c.cBright() : c.cFg();
            }
            lines[lines.length - 1] = "ENTER rebind    ESC back";
            cols[cols.length - 1] = c.cDim();
            c.popup(logicalW, logicalH, lines, cols);
        } else {
            String[] it = items(ctx);
            String[] lines = new String[it.length + 3];
            int[] cols = new int[lines.length];
            lines[0] = "PAUSED";
            cols[0] = c.cBright();
            lines[1] = "";
            cols[1] = c.cFg();
            for (int i = 0; i < it.length; i++) {
                lines[2 + i] = (i == pauseSel ? "> " : "  ") + it[i];
                cols[2 + i] = i == pauseSel ? c.cBright() : c.cFg();
            }
            lines[lines.length - 1] = "UP/DOWN    ENTER select";
            cols[cols.length - 1] = c.cDim();
            c.popup(logicalW, logicalH, lines, cols);
        }
    }
}
