package com.retro.bamfmenu.early.core.rhythm.chart.impl;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.tuning.DetectorConfig;

/* The immutable inputs a Charter needs, so the head hands a chart REQUEST to the seam instead of inlining the
   generate chain. Today it carries the decoded song (the DSP analysis source) + the difficulty's detector +
   lane count + the song's bpm hint; chartPath is reserved (null today) for the Phase-4 FileCharter so that
   unhook is a true one-place swap, not a carrier churn. Forward-compat: a builder that does not need a field
   ignores it (FileCharter ignores audio; DspCharter ignores chartPath). */
public final class ChartRequest {

    private final AudioPlayer audio;     // decoded song = the runtime DSP analysis source; null for a file-backed build
    private final DetectorConfig det;    // the difficulty's tuned detector (sensitivity/leadIn/cdFloor/minF0)
    private final int lanes;
    private final double bpm;            // grid tempo hint (SongDef.bpm; 0 = auto)
    private final String chartPath;      // committed-chart path; null today

    public ChartRequest(AudioPlayer audio, DetectorConfig det, int lanes, double bpm, String chartPath) {
        this.audio = audio;
        this.det = det;
        this.lanes = lanes;
        this.bpm = bpm;
        this.chartPath = chartPath;
    }

    /* The runtime path: analyse the freshly-decoded song. */
    public static ChartRequest fromAudio(AudioPlayer audio, DetectorConfig det, int lanes, double bpm) {
        return new ChartRequest(audio, det, lanes, bpm, null);
    }

    public AudioPlayer audio() {
        return audio;
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
