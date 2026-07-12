package com.retro.bamfmenu.early.core.rhythm.chart;

import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;

import java.util.List;

/* One difficulty's committed chart, the in-memory form of songs/<id>/<difficulty>.chart (as of runtime a
   song plays from its committed chart instead of being DSP-charted live). The notes are the same Note type
   the engine consumes, so the Charter seam keeps handing the head a List<Note>. Pure data, no GL/MC. */
public record Chart(
        String difficulty,                 // "normal" / "easy" / "hard" / "special" (also the filename)
        double bpm,                         // tempo hint copied from the song (0 = auto), informational
        int preRollMs,                      // silent lead-in before t=0 (Phase-4 scroll-in); 0 = none
        int approachMs,                     // note fall speed: ms of look-ahead (smaller = faster); 0 = use the default
        List<Note> notes       // sorted ascending by timeMs on load
) {
    // Bump only on an incompatible on-disk change; readers tolerate unknown/missing fields, so a minor addition needs no bump.
    public static final int VERSION = 1;

    public boolean isEmpty() {
        return notes == null || notes.isEmpty();
    }
}
