package com.retro.bamfmenu.early.core.rhythm.chart;

import com.retro.bamfmenu.early.core.tools.audio.DetectorConfig;
import com.retro.bamfmenu.early.core.tools.audio.SongAnalysis;

/* The immutable inputs a Charter needs, so the head hands a chart REQUEST to the seam instead of inlining the
   generate chain. Carries the song's ANALYSIS feature bank (SongAnalysis, the detached tool; playback is no
   longer the analysis source) + the difficulty's detector + lane count + the song's bpm hint; chartPath feeds
   the FileCharter. Forward-compat: a builder that does not need a field ignores it (FileCharter ignores the
   analysis; DspCharter ignores chartPath). */
public final class ChartRequest {

    private final SongAnalysis analysis; // the song's analyzed feature bank; null for a file-backed build
    private final DetectorConfig det;    // the difficulty's tuned detector (sensitivity/leadIn/cdFloor/minF0)
    private final int lanes;
    private final double bpm;            // grid tempo hint (SongDef.bpm; 0 = auto)
    private final String chartPath;      // committed-chart path; null today

    public ChartRequest(SongAnalysis analysis, DetectorConfig det, int lanes, double bpm, String chartPath) {
        this.analysis = analysis;
        this.det = det;
        this.lanes = lanes;
        this.bpm = bpm;
        this.chartPath = chartPath;
    }

    /* The analysis path: chart from an analyzed feature bank. */
    public static ChartRequest fromAnalysis(SongAnalysis analysis, DetectorConfig det, int lanes, double bpm) {
        return new ChartRequest(analysis, det, lanes, bpm, null);
    }

    public SongAnalysis analysis() {
        return analysis;
    }

    public DetectorConfig det() {
        return det;
    }

    public int lanes() {
        return lanes;
    }

    public double bpm() {
        return bpm;
    }

    public String chartPath() {
        return chartPath;
    }
}
