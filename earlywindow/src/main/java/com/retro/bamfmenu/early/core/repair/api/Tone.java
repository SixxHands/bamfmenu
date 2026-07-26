package com.retro.bamfmenu.early.core.repair.api;

/* Semantic text style rather than a raw color. This layer is headless and never imports bios/gfx;
   the screen maps each tone onto the active theme (which switches to AMI blue under "You're Blue Now"). */
public enum Tone {
    /** Standard gray body text. */
    NORMAL,
    /** Highlighted text for selected rows or status lines. */
    BRIGHT,
    /** Older / unavailable, dimmed. */
    DIM,
    /** Gold highlight for newly displayed status notices. */
    NOTICE,
    /** Something is wrong or dangerous, drawn red. */
    ALERT
}
