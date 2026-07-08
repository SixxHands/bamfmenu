package com.retro.bamfmenu.early.bios;

/* The early window's active base screen -- the single source of truth that replaced the old bag of
   volatile boolean mode flags (setupMode / tetrisMode / rhythmMode / cutsceneMode). Exactly one base
   screen is up at a time, so one volatile field holds it; writing that field is atomic, which is what
   makes the Minecraft hand-off hold loop unable to ever observe "no screen up" mid-transition (the old
   all-false race the boolean bag avoided by hand). The two menu overlays (volume, pause) float over any
   base screen and stay as their own booleans. POST is the plain loading terminal that hands off; every
   other value holds the hand-off until it returns to POST. RESULTS and SPECIAL are declared now so the
   enum is stable across the refactor; they are inert until their phase wires an entry path. */
public enum Mode {
    POST,      /* the loading terminal (the only state that does NOT hold the hand-off) */
    SETUP,     /* BIOS SETUP menu + jukebox */
    CUTSCENE,  /* the Recovery intro cutscene */
    RHYTHM,    /* the BAMFBEAT rhythm game */
    TETRIS,    /* the "Enable Mechanics" Tetris */
    RESULTS,   /* post-song score screen (wired later) */
    SPECIAL,   /* a per-song alternate gameplay session (scaffolded later) */
    EDITOR,    /* the chart editor; holds the hand-off like any non-POST screen */
    SNAKE,     /* the hidden Snake game (revealed by the "snake" secret) */
    GALAGA,    /* the hidden Galaga game (revealed by the "galaga" secret) */
    DOOM;      /* the hidden DOOM -- the real engine (revealed by the "doom" secret) */

    /* Any screen other than the plain loading terminal delays the Minecraft hand-off until the user
       leaves it. */
    public boolean holdsHandoff() {
        return this != POST;
    }
}
