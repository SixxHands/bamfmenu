package com.retro.bamfmenu.early.core.rhythm.chart;

import com.retro.bamfmenu.early.core.rhythm.RhythmEngine;

import java.util.List;

/* One difficulty's committed chart: the notes plus the per-difficulty metadata the playback needs. As of
   a song is played from a committed chart (songs/<id>/<difficulty>.chart) instead of being DSP-charted
   live, so this is the in-memory form of that file -- written by the bootstrap/editor (ChartIO.write) and read
   by the runtime (ChartIO.read -> FileCharter). The notes are RhythmEngine.Note (lane/timeMs/holdMs), the same
   type the engine consumes, so the Charter seam keeps handing the head a List<Note> and nothing downstream
   changes. preRollMs is reserved for the Phase-4 song-start protocol (notes at TRUE times + a silent lead-in);
   0 today. Pure data, no GL/MC. */
public record Chart(
        String difficulty,                 // "normal" / "easy" / "hard" / "special" (also the filename)
        double bpm,                         // tempo hint copied from the song (0 = auto), informational
        int preRollMs,                      // silent lead-in before t=0 (Phase-4 scroll-in); 0 = none
        int approachMs,                     // note fall speed: ms of look-ahead (smaller = faster); 0 = use the default
        List<RhythmEngine.Note> notes       // sorted ascending by timeMs on load
) {
    /* Bumped only if the on-disk format changes incompatibly; readers tolerate unknown/missing fields, so a
       minor addition does NOT need a bump. */
    public static final int VERSION = 1;

    public boolean isEmpty() {
        return notes == null || notes.isEmpty();
    }
}
