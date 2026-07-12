package com.retro.bamfmenu.early.bios.overlay;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Overlay;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

import java.util.Queue;

/* The F2 volume overlay, a host-composited LAYER over the active screen (not a Mode). Toggling it pauses
   the current song; arrows adjust, Enter/Esc closes (resuming + persisting). Blocked outright during a
   special event (F2 just flashes a red ERROR). Extracted verbatim from the provider.
   The volume VALUE + the input queue stay provider-owned (the value is persisted + read by the audio
   launch; the queue is filled by onKey on the main thread); this overlay reaches them through BiosContext.
   Its `active` flag is what the provider's renderFrame gating, onKey priority chain, and the hand-off
   hold loop read. */
public final class VolumeOverlay implements Overlay {

    /* GLFW key codes, duplicated so this class stays free of the LWJGL import (TetrisGame convention). */
    private static final int KEY_LEFT = 263;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_UP = 265;
    private static final int KEY_DOWN = 264;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_ESCAPE = 256;

    private final Queue<Integer> input;
    private volatile boolean active;

    public VolumeOverlay(Queue<Integer> input) {
        this.input = input;
    }

    @Override
    public boolean active() {
        return active;
    }

    /* Open (pausing audio) or close (resuming). Blocked during a special event: F2 flashes a red ERROR. */
    public void toggle(BiosContext ctx) {
        if (ctx.inSpecialEvent()) {
            ctx.flashError();
            return;
        }
        if (!active) {
            active = true;
            AudioPlayer a = ctx.audio();
            if (a != null) {
                a.pause();
            }
        } else {
            close(ctx);
        }
    }

    private void close(BiosContext ctx) {
        active = false;
        input.clear();
        AudioPlayer a = ctx.audio();
        if (a != null) {
            a.resume();
        }
        ctx.save(); // persist the chosen volume
    }

    /* Apply the queued keys (render thread): arrows adjust, Enter/Esc close. */
    @Override
    public void step(BiosContext ctx) {
        Integer k;
        while ((k = input.poll()) != null) {
            switch (k) {
                case KEY_LEFT, KEY_DOWN -> ctx.setVolume(ctx.volume() - 5);
                case KEY_RIGHT, KEY_UP -> ctx.setVolume(ctx.volume() + 5);
                case KEY_ENTER, KEY_KP_ENTER, KEY_ESCAPE -> {
                    close(ctx);
                    return;
                }
                default -> {
                }
            }
        }
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        int logicalW = c.logicalW();
        int logicalH = c.logicalH();
        c.beginFrameShader(logicalW, logicalH);
        int vol = ctx.volume();
        c.popup(logicalW, logicalH,
                new String[]{"VOLUME", "", TerminalCanvas.asciiBar(vol, 20) + "  " + vol + "%", "", "<- ->  adjust    ENTER  back"},
                new int[]{c.cBright(), c.cFg(), c.cGold(), c.cFg(), c.cDim()});
    }
}
