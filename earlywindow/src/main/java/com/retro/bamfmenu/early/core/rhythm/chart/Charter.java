package com.retro.bamfmenu.early.core.rhythm.chart;

import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartRequest;

import java.util.List;

/* The ONE seam between "where the note chart comes from" and the rhythm game. Today the only implementation
   is DspCharter (runtime onset analysis). runtime swaps in a FileCharter that loads a committed
   songs/<id>/<diff>.chart, making the runtime DSP removable in ONE place. The head (RhythmScreen.onEnter)
   only ever calls build(req), it is "fed a chart" and just reads it, so the editor (which authors charts)
   and a future file-backed world both plug in here without the head changing. */
public interface Charter {

    /* Build the note chart for a request, never null. An empty list means "no chart"; the caller decides the
       fallback (DspCharter falls back to the demo chart; a future FileCharter returns empty so the jukebox
       can mark the song unplayable, one coherent behavior per world). */
    List<Note> build(ChartRequest req);
}
