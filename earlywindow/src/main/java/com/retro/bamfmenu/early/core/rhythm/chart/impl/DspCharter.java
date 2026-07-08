package com.retro.bamfmenu.early.core.rhythm.chart.impl;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.chart.impl.ChartGenerator;
import com.retro.bamfmenu.early.core.tuning.DetectorConfig;
import com.retro.bamfmenu.early.core.rhythm.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.Charter;
import com.retro.bamfmenu.early.core.rhythm.chart.impl.ChartRequest;

import java.util.List;

/* The runtime chart source: the Phase-3 piano-priority charter with the Phase-1 whole-mix + legacy onset/ZCR
   fallbacks, ending in the hand-authored demo chart so a failed analysis never leaves an empty highway. This
   is the EXACT chain that used to be inlined in RhythmScreen.onEnter, moved VERBATIM behind the Charter seam so the runtime DSP is removable in one place -- swaps this impl for a
   FileCharter that reads req.chartPath() and ignores the audio. */
public final class DspCharter implements Charter {

    @Override
    public List<RhythmEngine.Note> build(ChartRequest req) {
        AudioPlayer p = req.audio();
        DetectorConfig det = req.det();
        int lanes = req.lanes();
        /* runtime piano-priority charter (onsets + lanes from the melodic band); falls back to the runtime
           whole-mix SuperFlux charter, then the legacy onset/ZCR charter, then the demo chart, so a failed
           analysis never leaves an empty highway. */
        List<RhythmEngine.Note> chart = ChartGenerator.generateMelodic(
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
