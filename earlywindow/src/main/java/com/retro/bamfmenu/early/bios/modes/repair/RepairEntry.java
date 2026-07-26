package com.retro.bamfmenu.early.bios.modes.repair;

import com.retro.bamfmenu.early.core.updater.OpsBridge;

// swaps setup row, stays first version easter egg joke if nothing exists
public final class RepairEntry {

    /** SETUP_OPTIONS[0] itself, and the row's identity in every provider equals check. */
    public static final String ROW_KEY = "Roll it back to BAMFOS v1"; // the provider reads this constant, so a rename cannot orphan the window
    /** The host's words for the row once there is an updater behind it. */
    public static final String LABEL = "UPDATE AND REPAIR";
    private static final String HELP =
            "Check for updates, verify the install, and roll BAMFOS back if you have to.";
    private static final String STATE = "Press ENTER to open.";

    private RepairEntry() {
    }

    /** True for the one SETUP row this window owns. */
    public static boolean owns(String row) {
        return ROW_KEY.equals(row);
    }

    /** True when pressing ENTER on the row should open the window. */
    public static boolean armed() {
        return OpsBridge.present();
    }

    /** The row's drawn label, unchanged for every other SETUP row. */
    public static String label(String row) {
        return label(row, OpsBridge.present());
    }

    /** The row's help text, or the SETUP fallback for every other row. */
    public static String help(String row, String fallback) {
        return help(row, fallback, OpsBridge.present());
    }

    /** The row's state line, or the SETUP fallback for every other row. */
    public static String state(String row, String fallback) {
        return state(row, fallback, OpsBridge.present());
    }

    /* The pure halves. Presence is a parameter here so a test can pose both worlds without an engine,
       and so the offline renderer can draw both. */

    /** The label for a posed presence, the testable half. */
    public static String label(String row, boolean present) {
        return owns(row) && present ? LABEL : row;
    }

    /** The help for a posed presence, the testable half. */
    public static String help(String row, String fallback, boolean present) {
        return owns(row) && present ? HELP : fallback;
    }

    /** The state line for a posed presence, the testable half. */
    public static String state(String row, String fallback, boolean present) {
        return owns(row) && present ? STATE : fallback;
    }
}
