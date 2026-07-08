package com.retro.bamfmenu.early.core.editor;

import com.retro.bamfmenu.early.core.rhythm.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.Chart;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartIO;

import java.util.ArrayList;
import java.util.List;

/* The in-memory chart being edited: one song-difficulty's notes plus the metadata ChartIO round-trips (bpm,
   preRollMs). Headless -- NO bios/GL imports (core/editor is reusable by a future standalone tool). The notes
   are RhythmEngine.Note, the SAME type the game plays, so author -> ChartIO.write -> ChartIO.read -> identical
   play is guaranteed. Deliberately small for v1; richer ops (snapping, history) layer on in later sub-phases. */
public final class EditChart {

    public final String songId;
    public final String difficultyToken;   // "normal" etc. -- also the filename
    public double bpm;                      // editable (drives the grid + saved into the Chart)
    public int preRollMs;                   // passed through unchanged in v1
    public int approachMs;                  // note fall speed (ms look-ahead, smaller = faster); 0 = use the default
    public final List<RhythmEngine.Note> notes = new ArrayList<>();
    private boolean dirty;

    public EditChart(String songId, String difficultyToken, double bpm, int preRollMs) {
        this.songId = songId;
        this.difficultyToken = difficultyToken;
        this.bpm = bpm;
        this.preRollMs = preRollMs;
    }

    /* Build from a loaded .chart text, or an empty chart if the text is null/blank. bpmHint seeds the tempo
       when the chart omits it (the song's bpm). */
    public static EditChart fromText(String songId, String token, String text, double bpmHint) {
        if (text == null || text.isBlank()) {
            return new EditChart(songId, token, bpmHint, 0);
        }
        Chart c = ChartIO.read(text, token);
        double bpm = c.bpm() > 0 ? c.bpm() : bpmHint;
        EditChart ec = new EditChart(songId, token, bpm, c.preRollMs());
        ec.approachMs = c.approachMs();
        ec.notes.addAll(c.notes());
        ec.sort();
        return ec;
    }

    /* Keep the list in play order (ascending time, then lane) -- the runtime expects sorted notes and it keeps
       rendering/iteration predictable. */
    public void sort() {
        notes.sort((a, b) -> a.timeMs != b.timeMs ? Integer.compare(a.timeMs, b.timeMs) : Integer.compare(a.lane, b.lane));
    }

    public boolean dirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public void markClean() {
        dirty = false;
    }

    public int count() {
        return notes.size();
    }

    /* The index of the note in `lane` nearest `timeMs` within tolMs, or -1 if none. */
    public int indexAt(int lane, int timeMs, int tolMs) {
        int best = -1;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < notes.size(); i++) {
            RhythmEngine.Note n = notes.get(i);
            if (n.lane != lane) {
                continue;
            }
            int d = Math.abs(n.timeMs - timeMs);
            if (d <= tolMs && d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /* Toggle a note at (lane, timeMs): remove an existing one within tol, else add a fresh tap. Returns the
       added note, or null if one was removed (so the caller can select what it just placed). */
    public RhythmEngine.Note toggle(int lane, int timeMs, int tolMs) {
        int idx = indexAt(lane, timeMs, tolMs);
        if (idx >= 0) {
            notes.remove(idx);
            markDirty();
            return null;
        }
        RhythmEngine.Note n = new RhythmEngine.Note(lane, Math.max(0, timeMs), 0);
        notes.add(n);
        sort();
        markDirty();
        return n;
    }

    /* Remove the note nearest `timeMs` in ANY lane within tol; returns true if one was removed. */
    public boolean removeNearest(int timeMs, int tolMs) {
        int best = -1;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < notes.size(); i++) {
            int d = Math.abs(notes.get(i).timeMs - timeMs);
            if (d <= tolMs && d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best >= 0) {
            notes.remove(best);
            markDirty();
            return true;
        }
        return false;
    }

    public boolean remove(RhythmEngine.Note n) {
        boolean r = notes.remove(n);
        if (r) {
            markDirty();
        }
        return r;
    }

    /* Move a note to a new lane + time. RhythmEngine.Note's fields are final, so a move is remove + re-add a
       fresh note (carrying the hold length); returns the new note so the caller can re-point its selection. */
    public RhythmEngine.Note move(RhythmEngine.Note n, int newLane, int newTimeMs) {
        notes.remove(n);
        RhythmEngine.Note m = new RhythmEngine.Note(Math.max(0, Math.min(3, newLane)), Math.max(0, newTimeMs), n.holdMs);
        notes.add(m);
        sort();
        markDirty();
        return m;
    }

    /* The time of the last note (for End / extent), or 0 if empty. */
    public int lastTimeMs() {
        int t = 0;
        for (RhythmEngine.Note n : notes) {
            t = Math.max(t, n.endMs());
        }
        return t;
    }

    /* Serialize to the committed .chart text for saving (notes sorted; bpm/preRoll carried through). */
    public String toText() {
        sort();
        return ChartIO.write(new Chart(difficultyToken, bpm, preRollMs, approachMs, new ArrayList<>(notes)));
    }
}
