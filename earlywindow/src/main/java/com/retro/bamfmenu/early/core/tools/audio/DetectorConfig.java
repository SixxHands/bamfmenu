package com.retro.bamfmenu.early.core.tools.audio;

/* Per-song tuning for the auto-charter, replacing the old global detection constants so each SongDef can
   dial its own. Consumed by SongAnalysis (decode-time band analysis) and ChartGenerator (onset/veto/lead-in).
   Pure data, no GL/MC, safe in the service jar. */
public record DetectorConfig(
        float sensitivity,  // onset density: higher = lower threshold = denser/harder chart
        int leadInMs,       // chart lead-in before the first note scrolls in
        float cdFloor,      // complex-domain phase-confidence veto floor (0 disables), drone/reverb suppression
        double melLoHz,     // melodic (lead) band low edge
        double melHiHz,     // melodic band high edge / percussion band low edge
        double percHiHz,    // percussion band high edge
        int tailMs,         // grace past the chart end before the song counts as finished
        double minF0Hz      // fundamental-pitch gate: drop onsets whose HPS fundamental is below this (0 = off)
) {
    /* Exactly the values the old globals held (provider RHYTHM_SENSITIVITY, ChartGenerator CD_FLOOR, SpectralAnalyzer bands, RhythmEngine tail), so a song that overrides nothing charts identically to before the refactor. */
    public static DetectorConfig defaults() {
        return new DetectorConfig(0.88f, 1200, 0.40f, 120.0, 5000.0, 6000.0, 1800, 0.0);
    }

    public DetectorConfig withSensitivity(float s) {
        return new DetectorConfig(s, leadInMs, cdFloor, melLoHz, melHiHz, percHiHz, tailMs, minF0Hz);
    }

    /* Raising the low edge skips a song's low end (e.g. Recovery's bit-crushed sub-band) so notes + lanes track the higher melody, not the low fundamental. */
    public DetectorConfig withMelodicBand(double lo, double hi) {
        return new DetectorConfig(sensitivity, leadInMs, cdFloor, lo, hi, percHiHz, tailMs, minF0Hz);
    }

    /* A time gate, onsets before this many ms are dropped; the intro still plays, just with no notes. */
    public DetectorConfig withLeadInMs(int ms) {
        return new DetectorConfig(sensitivity, ms, cdFloor, melLoHz, melHiHz, percHiHz, tailMs, minF0Hz);
    }

    /* Not just the onset gate: setting this also routes lane pitch to the HPS fundamental (better than the bit-crush argmax) and disables the percussion fill. */
    public DetectorConfig withMinF0Hz(double hz) {
        return new DetectorConfig(sensitivity, leadInMs, cdFloor, melLoHz, melHiHz, percHiHz, tailMs, hz);
    }

    /* A copy with a different beat-grid is NOT here, bpm lives on the SongDef, not the detector. */
}
