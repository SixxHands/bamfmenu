package com.retro.bamfmenu.early.core.repair.input;

/* Maps GLFW key codes to menu actions. Key codes are inlined as ints (like the rest of core/)
   so this layer never imports LWJGL and compiles standalone. */
public final class RepairKeys {

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_UP = 265;
    private static final int KEY_DOWN = 264;

    private RepairKeys() {
    }

    /** What this key means here, NONE for everything the window does not use. */
    public static RepairIntent of(int key) {
        return switch (key) {
            case KEY_UP -> RepairIntent.UP;
            case KEY_DOWN -> RepairIntent.DOWN;
            case KEY_ENTER, KEY_KP_ENTER -> RepairIntent.ACTIVATE;
            case KEY_ESCAPE -> RepairIntent.BACK;
            default -> RepairIntent.NONE;
        };
    }
}
