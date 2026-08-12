package com.retro.bamfmenu.early.bios.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FML progress labels must pass through untouched so boot logs match latest.log.
 * Inputs are literal strings ImmediateWindowHandler.updateProgress sends in FML 4.0.42.
 */
class BootPhaseTest {

    /** Verifies the five standard FML status labels pass through unchanged. */
    @Test
    void fmlsOwnLabels_reachTheScreenWordForWord() {
        assertEquals("Bootstrapping Minecraft", BootPhase.render("Bootstrapping Minecraft"));
        assertEquals("Discovering mod files", BootPhase.render("Discovering mod files"));
        assertEquals("Loading language providers", BootPhase.render("Loading language providers"));
        assertEquals("Scanning mod candidates", BootPhase.render("Scanning mod candidates"));
        assertEquals("Launching minecraft", BootPhase.render("Launching minecraft"));
    }

    /** Verifies FML labels with dynamic counts or provider IDs pass through verbatim. */
    @Test
    void labelsCarryingFmlsOwnData_areNotReworded() {
        assertEquals("Found 271 mod candidates", BootPhase.render("Found 271 mod candidates"));
        assertEquals("Loaded language provider javafml 4.0",
                BootPhase.render("Loaded language provider javafml 4.0"));
        assertEquals("Loaded language provider minecraft 4.0.42",
                BootPhase.render("Loaded language provider minecraft 4.0.42"));
    }

    /** Unknown labels pass through unchanged so future NeoForge label changes don't break anything. */
    @Test
    void anUnknownLabel_isJustTheLabel() {
        assertEquals("Reticulating splines", BootPhase.render("Reticulating splines"));
        assertEquals("", BootPhase.render(null));
        assertEquals("Launching minecraft", BootPhase.render("  Launching minecraft  "));
    }

    /**
     * Hand-off has no FML label, so the hand-off flag must outrank any stale
     * "Launching minecraft" label during BIOS wait and POST exit grace.
     */
    @Test
    void handingOff_outranksWhateverLabelIsStillStanding() {
        // Match FML's verbatim last label (lowercase "m") rather than our internal "Handing off" phrase.
        assertEquals("Launching minecraft", BootPhase.phase(true, "Launching minecraft"));
        assertEquals("Launching minecraft", BootPhase.phase(true, ""));
        assertEquals("Launching minecraft", BootPhase.phase(false, "Launching minecraft"));
        assertEquals("", BootPhase.phase(false, null));
    }

    /**
     * When the BIOS requests a quit, show that on the status row while startup finishes
     * so the wait doesn't look like a freeze.
     */
    @Test
    void aQueuedQuit_outranksBothOfThem() {
        assertEquals("Closing minecraft", BootPhase.phase(true, true, "Loading mods"));
        assertEquals("Closing minecraft", BootPhase.phase(true, false, "Loading mods"));
        assertEquals("Closing minecraft", BootPhase.phase(true, false, null));
        assertEquals("Loading mods", BootPhase.phase(false, false, "Loading mods"));
    }
}
