package com.retro.bamfmenu.early.bios.host;

/* The early window's active base screen, one volatile field instead of the old bag of boolean mode flags:
   exactly one base screen is up at a time and the field write is atomic, so the Minecraft hand-off hold
   loop can never observe "no screen up" mid-transition (the old all-false race the boolean bag avoided by
   hand). The two menu overlays (volume, pause) float over any base screen and stay their own booleans. */
public enum Mode {
    POST,      // the loading terminal
    SETUP,     // BIOS SETUP menu + jukebox
    REPAIR,    // the UPDATE AND REPAIR window (only reachable when the updater bound)
    CUTSCENE,  // the Recovery intro cutscene
    RHYTHM,    // the BAMFBEAT rhythm game
    TETRIS,    // the "Enable Mechanics" Tetris
    RESULTS,   // post-song score screen
    SPECIAL,   // per-song alternate gameplay session
    EDITOR,    // the chart editor
    SNAKE,     // hidden ("snake" secret)
    GALAGA,    // hidden ("galaga" secret)
    DOOM;      // hidden ("doom" secret), the real engine

    // Every screen but the loading terminal delays the Minecraft hand-off until the user leaves it.
    public boolean holdsHandoff() {
        return this != POST;
    }
}
