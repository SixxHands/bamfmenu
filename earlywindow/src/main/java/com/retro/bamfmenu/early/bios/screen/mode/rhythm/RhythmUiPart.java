package com.retro.bamfmenu.early.bios.screen.mode.rhythm;

/* The named, addressable UI parts of the rhythm highway (Phase B of the rhythm UI/FX plan). RhythmRenderer
   carves render() into one drawXxx per part and dispatches over these in order; the canonical set is compile-safe
   here, and each member carries a STABLE lowercase string id that a future data-driven FX chart can
   address a part by. The set is "locked in but open": members can be added/renamed without breaking callers,
   because id lookup is GRACEFUL -- an unknown/renamed id resolves to null, which RhythmRenderer treats as a
   no-op, never a crash. The declaration order IS the draw order (top of the highway down), so values() drives
   the render pass; reordering parts re-orders drawing (kept behavior-identical in Phase B by matching today's
   flow). Pure data -- no bios/core imports, no GL. */
public enum RhythmUiPart {
    WAVEFORM("waveform"),   // the song-name line + 3-line scrubbing waveform header
    BORDER("border"),       // the bordered box frame + the three column dividers
    LANES("lanes"),         // the per-lane hit-line dashes (the playfield floor)
    HITZONES("hitzones"),   // the [<<<] press indicators that light when a lane is held
    NOTES("notes"),         // the falling taps + hold capsules
    LEFTFRAME("leftframe"), // the reactive left panel (face / scripted clip)
    JUDGMENT("judgment"),   // the current note result (PERFECT/GOOD/...)
    SCORE("score"),         // the score odometer
    COMBO("combo"),         // the combo count + the x-mult / +bucket line
    HEALTH("health"),       // the HEALTH label + bar
    TALLY("tally");         // the Perfect/Good/Okay/Bad/Miss counters

    private final String id;

    RhythmUiPart(String id) {
        this.id = id;
    }

    /* The stable lowercase id the data FX chart addresses this part by (independent of the enum name). */
    public String id() {
        return id;
    }

    /* Graceful lookup by string id: an unknown/renamed/null id returns null (the caller treats it as a no-op),
       so a stale name in a future FX chart can never crash the renderer. Case-insensitive. */
    public static RhythmUiPart byId(String id) {
        if (id != null) {
            for (RhythmUiPart p : values()) {
                if (p.id.equalsIgnoreCase(id)) {
                    return p;
                }
            }
        }
        return null;
    }
}
