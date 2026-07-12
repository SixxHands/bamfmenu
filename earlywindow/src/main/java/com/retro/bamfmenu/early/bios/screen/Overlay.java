package com.retro.bamfmenu.early.bios.screen;

import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

/* A host-composited LAYER drawn OVER the active screen, NOT a Mode (the F2 volume slider, the pause/remap
   menu). While one is active the provider freezes the base screen's step and composites the overlay after
   the screen + before the control bar. */
public interface Overlay {

    boolean active();

    /* One frame on the render thread. */
    void step(BiosContext ctx);

    void render(TerminalCanvas canvas, BiosContext ctx);
}
