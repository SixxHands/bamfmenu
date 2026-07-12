package com.retro.bamfmenu.early.core.rhythm.chart.impl;

import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.tools.audio.SongAnalysis;
import com.retro.bamfmenu.early.core.tools.audio.DetectorConfig;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.Charter;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartRequest;

import java.util.List;

/* The ANALYSIS chart source (dev genCharts today; the editor's future "Generate"): the Phase-3 piano-priority
   charter with the Phase-1 whole-mix + legacy onset/ZCR fallbacks, ending in the hand-authored demo chart so a
   failed analysis never leaves an empty highway. Gameplay itself loads COMMITTED charts, runtime
   made that swap; this impl remains the one place the detector chain turns features into notes. */
public final class DspCharter implements Charter {

    @Override
    public List<Note> build(ChartRequest req) {
        SongAnalysis p = req.analysis();
        if (p == null) {
            // SongAnalysis.analyze returns null on failure (the fused player exposed empty arrays instead),
            // so honor the never-empty-highway contract here rather than NPEing before the fallback chain.
            return RhythmEngine.demoChart();
        }
        DetectorConfig det = req.det();
        int lanes = req.lanes();
        /* runtime piano-priority charter (onsets + lanes from the melodic band); falls back to the runtime
           whole-mix SuperFlux charter, then the legacy onset/ZCR charter, then the demo chart, so a failed
           analysis never leaves an empty highway. */
        List<Note> chart = ChartGenerator.generateMelodic(
                p.melodicOdf(), p.melodicPitch(), p.percussionOdf(), p.cdConf(), p.superfluxFrameMs(),
                det.sensitivity(), lanes, det.leadInMs(), req.bpm(), det.cdFloor(),
                p.melodicF0(), (float) det.minF0Hz());
        if (chart.isEmpty()) {
            chart = ChartGenerator.generateV2(
                    p.superflux(), p.centroid(), p.superfluxFrameMs(), det.sensitivity(), lanes, det.leadInMs());
        }
        if (chart.isEmpty()) {
            chart = ChartGenerator.generate(
                    p.onsets(), p.zcr(), p.hopMs(), det.sensitivity(), lanes, det.leadInMs());
        }
        if (chart.isEmpty()) {
            chart = RhythmEngine.demoChart(); // last resort if analysis came back empty (e.g. decode failed)
        }
        return chart;
    }
}
