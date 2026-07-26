package com.retro.bamfmenu.early.core.updater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the no-updater fallback path (no shim jar on the test classpath).
 * Live calls require the FML service layer and an actual engine, so this verifies safe fallback behavior.
 */
class OpsBridgeTest {

    @Test
    void withNoShimEverythingAnswersAbsentAndNothingThrows() {
        assertFalse(OpsBridge.present());
        assertEquals(OpsBridge.NO_ANSWER, OpsBridge.code("repair"));
        assertFalse(OpsBridge.flag("setDisabled", "mods/x.jar", true));
        assertEquals("", OpsBridge.text("releaseNotes"));
        assertTrue(OpsBridge.map("status").isEmpty());
        assertTrue(OpsBridge.rows("listOptional").isEmpty());
    }

    @Test
    void anErrorStringIsNotAnAnswer() {
        // OpsGateway returns "error: ..." instead of throwing across classloaders, so the text
        // accessor must filter those out instead of showing them in the BIOS as release notes.
        assertEquals("", OpsBridge.text("releaseNotes"));
        assertTrue(OpsBridge.rowsIn(java.util.Map.of("releases", "not a list"), "releases").isEmpty());
        assertTrue(OpsBridge.rowsIn(java.util.Map.of(), "releases").isEmpty());
        assertEquals(1, OpsBridge.rowsIn(java.util.Map.of("releases",
                java.util.List.of(java.util.Map.of("tag", "v1"), "junk")), "releases").size());
    }
}
