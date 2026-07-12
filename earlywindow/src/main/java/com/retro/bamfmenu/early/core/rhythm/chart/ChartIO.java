package com.retro.bamfmenu.early.core.rhythm.chart;

import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;

import java.util.ArrayList;
import java.util.List;

/* Read/write the committed .chart text format. Hand-rolled + dependency-free (the service jar ships no libs),
   human-readable + hand-editable, and TOLERANT on read the same way Json is: unknown header keys are ignored,
   missing ones fall back to defaults, and a malformed note row is skipped rather than throwing, so a partial
   or future-extended chart never crashes the runtime (a truly empty result just makes the song unplayable).

   Format (one song-difficulty per file):

       # BAMFBEAT chart
       version 1
       difficulty normal
       bpm 140
       preroll 1200
       notes 123
       # timeMs lane holdMs
       0 0 0
       512 2 0
       1024 1 250
       ...

   Header lines are "key value" (key is non-numeric); note rows are three integers "timeMs lane holdMs";
   '#' begins a comment; blank lines are ignored. Times are absolute ms from the song's first sample. */
public final class ChartIO {

    private ChartIO() {
    }

    /* Serialize a chart to the text form above. Notes are written in time order. */
    public static String write(Chart c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# BAMFBEAT chart\n");
        sb.append("version ").append(Chart.VERSION).append('\n');
        sb.append("difficulty ").append(c.difficulty() == null ? "normal" : c.difficulty()).append('\n');
        sb.append("bpm ").append(trimNum(c.bpm())).append('\n');
        sb.append("preroll ").append(c.preRollMs()).append('\n');
        if (c.approachMs() > 0) { // omit when default so untouched charts stay clean (and keep the default feel)
            sb.append("approach ").append(c.approachMs()).append('\n');
        }
        List<Note> notes = c.notes() == null ? List.of() : new ArrayList<>(c.notes());
        notes.sort((a, b) -> Integer.compare(a.timeMs, b.timeMs));
        sb.append("notes ").append(notes.size()).append('\n');
        sb.append("# timeMs lane holdMs\n");
        for (Note n : notes) {
            sb.append(n.timeMs).append(' ').append(n.lane).append(' ').append(n.holdMs).append('\n');
        }
        return sb.toString();
    }

    /* Parse the text form into a Chart. Tolerant: bad/unknown lines are skipped, missing header fields default
       (difficulty -> the supplied fallback, bpm/preroll -> 0). Never throws; worst case the notes list is
       empty. The declared "notes N" count is informational, we trust the actual rows, not the header. */
    public static Chart read(String text, String fallbackDifficulty) {
        String difficulty = fallbackDifficulty;
        double bpm = 0;
        int preRoll = 0;
        int approach = 0;
        List<Note> notes = new ArrayList<>();
        if (text != null) {
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (Character.isDigit(line.charAt(0))) {
                    Note note = parseNote(line);
                    if (note != null) {
                        notes.add(note);
                    }
                } else {
                    int sp = line.indexOf(' ');
                    if (sp <= 0) {
                        continue;
                    }
                    String key = line.substring(0, sp);
                    String val = line.substring(sp + 1).trim();
                    switch (key) {
                        case "difficulty" -> difficulty = val;
                        case "bpm" -> bpm = parseDouble(val, bpm);
                        case "preroll" -> preRoll = (int) parseDouble(val, preRoll);
                        case "approach" -> approach = (int) parseDouble(val, approach);
                        default -> { /* version / notes / unknown -> ignored */ }
                    }
                }
            }
        }
        notes.sort((a, b) -> Integer.compare(a.timeMs, b.timeMs));
        return new Chart(difficulty, bpm, preRoll, approach, notes);
    }

    /* "timeMs lane holdMs" -> Note, or null if the row is malformed. holdMs is optional (defaults 0) so a
       hand-author can write just "timeMs lane". Negative lanes/times are rejected. */
    private static Note parseNote(String line) {
        String[] tok = line.split("\\s+");
        if (tok.length < 2) {
            return null;
        }
        try {
            int timeMs = Integer.parseInt(tok[0]);
            int lane = Integer.parseInt(tok[1]);
            int holdMs = tok.length >= 3 ? Integer.parseInt(tok[2]) : 0;
            if (timeMs < 0 || lane < 0 || holdMs < 0) {
                return null;
            }
            return new Note(lane, timeMs, holdMs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /* Whole numbers print without a trailing ".0" (so bpm 140 not 140.0); fractional values keep their form. */
    private static String trimNum(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
