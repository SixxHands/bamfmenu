package com.retro.bamfmenu.early.bios.screen;

import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

/* One BIOS screen per Mode. The provider hosts the render thread, owns GL + the `state` field, and drives
   the active screen each frame. Its renderFrame switch dispatches on mode(); a full Map of Mode to Screen
   was intentionally not built, the switch already delegates cleanly. */
public interface Screen {

    Mode mode();

    default void onEnter(BiosContext ctx) {
    }

    default void onExit(BiosContext ctx) {
    }

    /* One frame on the render thread. Takes NO canvas by design, so a screen cannot issue GL outside render(). */
    void step(BiosContext ctx);

    /* Render thread, inside the provider's bound FBO. */
    void render(TerminalCanvas canvas, BiosContext ctx);

    /* Text for the shared bottom control bar, "" = none. */
    default String controlHints(BiosContext ctx) {
        return "";
    }
}
