package com.retro.bamfmenu.early.core.repair.input;

/* What a key MEANS on this window, decided once so the session never sees a key code. Four intents
   cover every depth: the same BACK walks a prompt, then a page, then the window itself. */
public enum RepairIntent {
    /** Move the selection (or the scroll) up one. */
    UP,
    /** Move the selection (or the scroll) down one. */
    DOWN,
    /** ENTER: answer a prompt, pick a row, open a page. */
    ACTIVATE,
    /** ESC: one step back out, prompt to page to window. */
    BACK,
    /** A key this window has no meaning for. */
    NONE
}
