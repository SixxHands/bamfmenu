package com.retro.bamfmenu.early.bios.modes.repair;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the SETUP repair entry with and without the updater, pinning the fallback text. */
class RepairEntryTest {

    private static final String JOKE = "This is the first version,\nsilly!";
    private static final String OTHER_ROW = "Reset Cache";
    private static final String OTHER_HELP = "Reset the BAMFOS to\nfactory defaults.";

    @Test
    void withNoUpdaterTheRowIsExactlyTheJokeItAlwaysWas() {
        assertEquals(RepairEntry.ROW_KEY, RepairEntry.label(RepairEntry.ROW_KEY, false));
        assertEquals(JOKE, RepairEntry.help(RepairEntry.ROW_KEY, JOKE, false));
        assertEquals("", RepairEntry.state(RepairEntry.ROW_KEY, "", false));
    }

    @Test
    void onceTheUpdaterBindsTheSameRowBecomesTheWindow() {
        assertEquals("Update and Repair", RepairEntry.label(RepairEntry.ROW_KEY, true));
        assertEquals("Press ENTER to open.", RepairEntry.state(RepairEntry.ROW_KEY, "", true));
        String help = RepairEntry.help(RepairEntry.ROW_KEY, JOKE, true);
        assertFalse(help.equals(JOKE));
        assertTrue(help.contains("verify"), help);
        assertTrue(help.contains("roll"), help);
    }

    @Test
    void everyOtherSetupRowIsUntouchedInBothWorlds() {
        for (boolean present : new boolean[] {false, true}) {
            assertEquals(OTHER_ROW, RepairEntry.label(OTHER_ROW, present));
            assertEquals(OTHER_HELP, RepairEntry.help(OTHER_ROW, OTHER_HELP, present));
            assertEquals("[ Disabled ]", RepairEntry.state(OTHER_ROW, "[ Disabled ]", present));
            assertFalse(RepairEntry.owns(OTHER_ROW));
        }
        assertTrue(RepairEntry.owns(RepairEntry.ROW_KEY));
    }

    @Test
    void withNoShimOnTheClasspathNothingArmsAndNothingThrows() {
        // The test JVM has no updater shim (same as a player install without the updater jar),
        // so the reflective lookup should return "absent" instead of crashing the BIOS.
        assertFalse(RepairEntry.armed());
        assertEquals(RepairEntry.ROW_KEY, RepairEntry.label(RepairEntry.ROW_KEY));
        assertEquals(JOKE, RepairEntry.help(RepairEntry.ROW_KEY, JOKE));
        assertEquals("", RepairEntry.state(RepairEntry.ROW_KEY, ""));
    }
}
