package com.retro.bamfmenu.early.core.repair.input;

/* Maps GLFW key codes to menu actions. Key codes are inlined as ints (like the rest of core/)
   so this layer never imports LWJGL and compiles standalone. */
public final class RepairKeys {

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_UP = 265;
    private static final int KEY_DOWN = 264;
    private static final int KEY_LEFT = 263;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_BACKSPACE = 259;

    private RepairKeys() {
    }

    /**
     * Translates a key code to a UI intent, or NONE if unused.
     * LEFT/RIGHT/ERASE map unconditionally here; RepairSession decides whether a text field is open.
     */
    public static RepairIntent of(int key) {
        return switch (key) {
            case KEY_UP -> RepairIntent.UP;
            case KEY_DOWN -> RepairIntent.DOWN;
            case KEY_ENTER, KEY_KP_ENTER -> RepairIntent.ACTIVATE;
            case KEY_ESCAPE -> RepairIntent.BACK;
            case KEY_LEFT -> RepairIntent.LEFT;
            case KEY_RIGHT -> RepairIntent.RIGHT;
            case KEY_BACKSPACE -> RepairIntent.ERASE;
            default -> RepairIntent.NONE;
        };
    }
}
