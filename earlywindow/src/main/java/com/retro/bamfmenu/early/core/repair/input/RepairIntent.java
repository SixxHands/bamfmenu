package com.retro.bamfmenu.early.core.repair.input;

/* High-level input actions mapped from raw key codes so the session doesn't deal with GLFW constants.
   The first four work everywhere (BACK closes a field, then a prompt, then a page, then the window).
   The last three only apply while editing a text field and map to NONE otherwise. */
public enum RepairIntent {
    /** Move the selection (or the scroll) up one. */
    UP,
    /** Move the selection (or the scroll) down one. */
    DOWN,
    /** ENTER: answer a prompt, pick a row, open a page, commit a field. */
    ACTIVATE,
    /** ESC: one step back out, field to prompt to page to window. */
    BACK,
    /** Caret one character left, only while a field is open. */
    LEFT,
    /** Caret one character right, only while a field is open. */
    RIGHT,
    /** BACKSPACE: delete the character before the caret while a field is open. */
    ERASE,
    /** A key this window has no meaning for. */
    NONE
}
