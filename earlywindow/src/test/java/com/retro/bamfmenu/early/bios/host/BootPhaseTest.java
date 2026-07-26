package com.retro.bamfmenu.early.bios.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The boot-phase register, tested against FML's REAL labels: every input string below is a literal that
 * ImmediateWindowHandler.updateProgress pushes in FML 4.0.42 (the loader NeoForge 21.1.235 ships), so this
 * suite doubles as the record of where each phase comes from. The load-bearing case is the last one: an
 * unknown label has to fall through to itself, because the alternative is a loader bump silently renaming a
 * phase we no longer observe.
 */
class BootPhaseTest {

    @Test
    void everyRealFmlLabel_rendersInTheOsRegister() {
        assertEquals("Loading NeoForge", BootPhase.render("Bootstrapping Minecraft"));
        assertEquals("Scanning kernel modules", BootPhase.render("Discovering mod files"));
        assertEquals("Loading module loaders", BootPhase.render("Loading language providers"));
        assertEquals("Linking kernel modules", BootPhase.render("Scanning mod candidates"));
        assertEquals("Starting Minecraft", BootPhase.render("Launching minecraft"));
    }

    /** The count is FML's own number and the whole point of the row, so it survives the re-wording. */
    @Test
    void candidateCount_keepsTheNumberFmlCounted() {
        assertEquals("Kernel modules found: 271", BootPhase.render("Found 271 mod candidates"));
        assertEquals("Module loader ready: javafml 4.0", BootPhase.render("Loaded language provider javafml 4.0"));
    }

    /** A NeoForge bump can retire or reword any label in here. Falling through to the label itself keeps the
     *  terminal honest on that day; a default phase name would print a step that is not the one running. */
    @Test
    void unknownLabel_fallsThroughToItself() {
        assertEquals("Reticulating splines", BootPhase.render("Reticulating splines"));
        assertEquals("Found some mod candidates but not really",
                BootPhase.render("Found some mod candidates but not really"));
        assertEquals("Loaded language provider", BootPhase.render("Loaded language provider"));
        assertEquals("", BootPhase.render(null));
        assertEquals("Starting Minecraft", BootPhase.render("  Launching minecraft  "));
    }

    /** The hand-off is the one phase FML pushes no label for, so the flag decides it, and the flag OUTRANKS the
     *  label: drop that branch and the log's last row sits on a stale "Starting Minecraft" for the whole BIOS
     *  hold and the POST exit grace, which is the state this phase exists to end. */
    @Test
    void handingOff_outranksWhateverLabelIsStillStanding() {
        assertEquals("Handing off to game", BootPhase.phase(true, "Launching minecraft"));
        assertEquals("Handing off to game", BootPhase.phase(true, ""));
        assertEquals("Starting Minecraft", BootPhase.phase(false, "Launching minecraft"));
        assertEquals("", BootPhase.phase(false, null));
    }
}
