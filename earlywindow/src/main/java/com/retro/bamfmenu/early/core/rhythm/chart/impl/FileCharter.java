package com.retro.bamfmenu.early.core.rhythm.chart.impl;

import com.retro.bamfmenu.early.core.rhythm.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.Chart;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartIO;
import com.retro.bamfmenu.early.core.rhythm.chart.Charter;
import com.retro.bamfmenu.early.core.song.Difficulty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* Phase-4 runtime charter: the game is FED a committed chart instead of DSP-charting at play. build() reads the
   bundled songs/<id>/<difficulty>.chart named by req.chartPath() and returns its notes; the audio is ignored
   (it still plays, but no analysis drives the chart). An empty result -- no path, missing file, or garbled --
   means "uncharted": the caller marks the song/difficulty unplayable rather than starting an empty highway.
   This is the one-line swap the Charter seam was built for; DspCharter stays as the editor's "Generate".

   chartPath()/hasChart() are the jukebox's source of truth for WHICH difficulties a song offers (the plan's
   "the jukebox offers only the difficulties whose charts exist"). Existence is memoized -- built-in charts are
   static, and this is polled per-frame while the difficulty menu is open. Classpath-only today; (the
   editor / external songs) extends it to the on-disk BAMFOS/songs folder. */
public final class FileCharter implements Charter {

    private static final Map<String, Boolean> EXISTS = new ConcurrentHashMap<>();

    /* The bundled resource path of a song-difficulty's chart: /songs/<id>/<token>.chart (token e.g. "normal"). */
    public static String chartPath(String songId, Difficulty diff) {
        return "/songs/" + songId + "/" + diff.token + ".chart";
    }

    /* Whether a committed chart exists for this song-difficulty (memoized classpath lookup). */
    public static boolean hasChart(String songId, Difficulty diff) {
        return EXISTS.computeIfAbsent(chartPath(songId, diff),
                p -> FileCharter.class.getResource(p) != null);
    }

    /* The authored note fall speed of a committed chart (its `approach` header), or `def` if absent/unreadable.
       Read separately from build() so the host can set the engine's per-chart scroll speed; a small text read,
       once at song start. */
    public static int approachMs(String path, int def) {
        if (path == null || path.isBlank()) {
            return def;
        }
        try (InputStream in = FileCharter.class.getResourceAsStream(path)) {
            if (in == null) {
                return def;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int ms = ChartIO.read(text, "normal").approachMs();
            return ms > 0 ? ms : def;
        } catch (Throwable t) {
            return def;
        }
    }

    @Override
    public List<RhythmEngine.Note> build(ChartRequest req) {
        String path = req.chartPath();
        if (path == null || path.isBlank()) {
            return List.of();
        }
        try (InputStream in = FileCharter.class.getResourceAsStream(path)) {
            if (in == null) {
                return List.of();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Chart chart = ChartIO.read(text, "normal");
            return chart.notes();
        } catch (Throwable t) {
            System.err.println("[bamfmenu] chart load failed for " + path + ": " + t);
            return List.of();
        }
    }
}
