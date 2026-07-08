package com.retro.bamfmenu.early.core.rhythm.chart;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.impl.ChartRequest;
import com.retro.bamfmenu.early.core.rhythm.chart.impl.DspCharter;
import com.retro.bamfmenu.early.core.song.SongDef;
import com.retro.bamfmenu.early.core.song.SongRegistry;
import com.retro.bamfmenu.early.core.tuning.DetectorConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/* DEV-ONLY Phase-4 bootstrap: emit a committed normal.chart for every bundled song using the EXACT runtime
   charter (DspCharter) + each song's own DetectorConfig, so the committed charts reproduce precisely what the
   live game charts today -- the whole point being that after the runtime switches to file-loaded charts, the
   songs play identically, and the pack host can then hand-tweak these files in the editor.

   Runs HEADLESS via the :earlywindow:genCharts Gradle task: decode (STB Vorbis) + analysis (SpectralAnalyzer)
   + charting (ChartGenerator) need no OpenAL and no GL, so AudioPlayer.analyzeOnly() feeds DspCharter without
   an audio device or window. Never invoked at runtime.

   Usage: java ... ChartBootstrap <outputDir>   (writes <outputDir>/<songId>/normal.chart) */
public final class ChartBootstrap {

    private ChartBootstrap() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("[chartgen] usage: ChartBootstrap <outputDir>");
            System.exit(2);
            return;
        }
        Path outRoot = Path.of(args[0]);
        int ok = 0;
        int fail = 0;
        SongDef[] songs = SongRegistry.SONGS;
        System.out.println("[chartgen] discovered " + songs.length + " song(s); writing to " + outRoot.toAbsolutePath());
        for (SongDef song : songs) {
            try {
                // True-time chart: leadInMs=0 keeps the song's OPENING notes (the pre-roll gives the silent
                // scroll-in gap instead of dropping the first ~1.2s). Everything else = the song's tuned detector.
                DetectorConfig det = song.detector().withLeadInMs(0);
                AudioPlayer p = new AudioPlayer();
                if (!p.analyzeOnly(song.ogg(), det)) {
                    System.err.println("[chartgen] SKIP " + song.id() + " (" + p.status() + ")");
                    fail++;
                    continue;
                }
                List<RhythmEngine.Note> notes = new DspCharter().build(
                        ChartRequest.fromAudio(p, det, RhythmEngine.LANES, song.bpm()));
                Chart chart = new Chart("normal", song.bpm(), 0, 0, notes);
                Path dir = outRoot.resolve(song.id());
                Files.createDirectories(dir);
                Path file = dir.resolve("normal.chart");
                Files.writeString(file, ChartIO.write(chart), StandardCharsets.UTF_8);
                System.out.println("[chartgen] " + song.id() + "/normal.chart  " + notes.size() + " notes  -> " + file);
                ok++;
            } catch (Throwable t) {
                System.err.println("[chartgen] FAIL " + song.id() + ": " + t);
                t.printStackTrace();
                fail++;
            }
        }
        System.out.println("[chartgen] done: " + ok + " written, " + fail + " failed");
        if (ok == 0) {
            System.exit(1);
        }
    }
}
