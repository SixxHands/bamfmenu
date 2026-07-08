package com.retro.bamfmenu.early.bios.screen;

import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

/* A floating overlay (the F2 volume slider, the in-game pause/remap menu) -- a host-composited LAYER drawn
   OVER the active screen, NOT a Mode. While one is active the provider freezes the base screen's step (the
   existing !volumeMenu / !pauseMenu gates) and composites the overlay after the active screen + before the
   control bar, exactly as renderFrame does today.

   .75 scaffold: VolumeOverlay + PauseOverlay implement this in the overlay migration step. */
public interface Overlay {

    /* Is this overlay currently up (and therefore freezing the base screen + capturing input)? */
    boolean active();

    /* Advance the overlay on the render thread (drain its own input queue). */
    void step(BiosContext ctx);

    /* Draw the overlay through the canvas facade, composited over the active screen. */
    void render(TerminalCanvas canvas, BiosContext ctx);
}
