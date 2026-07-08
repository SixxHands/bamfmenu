package com.retro.bamfmenu.early.core.tuning;

/* Per-song tuning for the auto-charter, so each SongDef can dial its OWN detection instead of the old
   global constants this replaces: the provider's RHYTHM_SENSITIVITY + the 1200 chart lead-in literal,
   ChartGenerator's CD_FLOOR, SpectralAnalyzer's MEL_LO_HZ / MEL_HI_HZ / PERC_HI_HZ bands, and the
   RhythmEngine +1800 tail. defaults() reproduces EXACTLY the values those globals held, so a song that
   overrides nothing charts identically to before this refactor. Pure data, no GL/MC -- safe in the
   service jar. A song passes its DetectorConfig into AudioPlayer (for the decode-time band analysis) and
   into ChartGenerator (for the onset/veto/lead-in). */
public record DetectorConfig(
        float sensitivity,  // onset density: higher = lower threshold = denser/harder chart
        int leadInMs,       // chart lead-in before the first note scrolls in
        float cdFloor,      // complex-domain phase-confidence veto floor (0 disables) -- drone/reverb suppression
        double melLoHz,     // melodic (lead) band low edge
        double melHiHz,     // melodic band high edge / percussion band low edge
        double percHiHz,    // percussion band high edge
        int tailMs,         // grace past the chart end before the song counts as finished
        double minF0Hz      // fundamental-pitch gate: drop onsets whose HPS fundamental is below this (0 = off)
) {
    /* The historical global values, so an unspecified song is unchanged from pre-refactor behavior:
       RHYTHM_SENSITIVITY=0.88, lead-in=1200, CD_FLOOR=0.40, MEL_LO=120, MEL_HI=5000, PERC_HI=6000,
       RhythmEngine tail=1800, no fundamental gate. */
    public static DetectorConfig defaults() {
        return new DetectorConfig(0.88f, 1200, 0.40f, 120.0, 5000.0, 6000.0, 1800, 0.0);
    }

    /* A copy with a different sensitivity (the most common per-song tweak), keeping everything else. */
    public DetectorConfig withSensitivity(float s) {
        return new DetectorConfig(s, leadInMs, cdFloor, melLoHz, melHiHz, percHiHz, tailMs, minF0Hz);
    }

    /* A copy with a different melodic (lead-instrument) band -- the onset/pitch focus. Raising the low edge
       skips a song's low-end (e.g. Recovery's bit-crushed sub-band) so the chart's notes + lanes track the
       higher melody instead of the low fundamental. */
    public DetectorConfig withMelodicBand(double lo, double hi) {
        return new DetectorConfig(sensitivity, leadInMs, cdFloor, lo, hi, percHiHz, tailMs, minF0Hz);
    }

    /* A copy with a different chart lead-in = the time gate: onsets before this many ms are dropped, so the
       chart starts here. Used to skip a song's uncharted intro -- the intro still plays, no notes. */
    public DetectorConfig withLeadInMs(int ms) {
        return new DetectorConfig(sensitivity, ms, cdFloor, melLoHz, melHiHz, percHiHz, tailMs, minF0Hz);
    }

    /* A copy with a fundamental-pitch gate (Hz): the charter drops any onset whose HPS fundamental is below
       this, so a song whose unwanted notes are LOWER-pitched than its melody (e.g. Recovery's bit-crushed
       bass/background vs its higher melody) charts only the melody. Also routes lane pitch to the HPS
       fundamental (better than the bit-crush argmax) and disables the percussion fill. 0 = off. */
    public DetectorConfig withMinF0Hz(double hz) {
        return new DetectorConfig(sensitivity, leadInMs, cdFloor, melLoHz, melHiHz, percHiHz, tailMs, hz);
    }

    /* A copy with a different beat-grid is NOT here -- bpm lives on the SongDef, not the detector. */
}
