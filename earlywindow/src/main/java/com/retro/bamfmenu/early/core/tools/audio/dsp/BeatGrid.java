package com.retro.bamfmenu.early.core.tools.audio.dsp;

/* estimate the song's beat grid from the onset envelope so the charter can SNAP notes to musical
   positions and DROP off-grid onsets. Real notes land on beats; drone/reverb noise does not, so off-grid
   rejection is also a principled de-noiser. Tempo = autocorrelation of the onset envelope weighted by a
   log-Gaussian prior around 120 BPM, with a half/double octave re-check via a comb score; phase = the offset
   that best aligns a pulse train. If the beat is too weak (confidence below a floor) confident is false and
   the charter keeps raw onset times instead of forcing a wrong grid. Pure logic, no GL/audio. */
public final class BeatGrid {

    final double periodFrames; // frames per beat
    final double phaseFrames;  // offset of beat 0
    final double bpm;
    final double confidence;
    private final double stepFrames; // 16th-note grid
    private final double tolFrames;

    private BeatGrid(double periodFrames, double phaseFrames, double bpm, double confidence) {
        this.periodFrames = periodFrames;
        this.phaseFrames = phaseFrames;
        this.bpm = bpm;
        this.confidence = confidence;
        this.stepFrames = periodFrames / 4.0; // 4 sixteenths per beat
        this.tolFrames = Math.max(3.0, 0.25 * this.stepFrames);
    }

    public boolean confident() {
        return confidence >= 2.5 && periodFrames > 1.0;
    }

    /* The nearest 16th-grid frame to an onset. We QUANTIZE to this (for the on-beat feel) but do NOT reject
       off-grid onsets: an expressive/rubato song's real notes do not sit on a rigid grid, so positional
       rejection erases them. Dedupe (one note per slot) is the only thinning. */
    public int nearestFrame(int frame) {
        double g = phaseFrames + Math.round((frame - phaseFrames) / stepFrames) * stepFrames;
        return (int) Math.round(g);
    }

    /* Auto-detect tempo from the onset envelope. */
    public static BeatGrid estimate(float[] odf, double frameMs) {
        return estimate(odf, frameMs, 0.0);
    }

    /* If knownBpm > 0 (a per-song hint), use it for the grid period (eliminating tempo drift / octave errors)
       and only estimate the phase; otherwise auto-detect the tempo. */
    public static BeatGrid estimate(float[] odf, double frameMs, double knownBpm) {
        if (odf == null || odf.length < 16 || frameMs <= 0) {
            return new BeatGrid(0, 0, 0, 0);
        }
        if (knownBpm > 0) {
            int p = Math.max(1, (int) Math.round(60000.0 / (knownBpm * frameMs)));
            int phase = bestPhase(odf, p);
            return new BeatGrid(p, phase, knownBpm, 100.0); // trusted -> high confidence
        }
        int n = odf.length;
        double mean = 0;
        for (float v : odf) {
            mean += v;
        }
        mean /= n;
        double[] o = new double[n];
        for (int i = 0; i < n; i++) {
            double d = odf[i] - mean;
            o[i] = d > 0 ? d : 0; // mean-subtract + half-wave rectify
        }
        int minLag = Math.max(1, (int) Math.round(60000.0 / (200.0 * frameMs))); // up to 200 BPM
        int maxLag = Math.min(n - 1, (int) Math.round(60000.0 / (50.0 * frameMs))); // down to 50 BPM
        if (maxLag <= minLag) {
            return new BeatGrid(0, 0, 0, 0);
        }
        double[] acw = new double[maxLag + 1];
        double sumw = 0;
        double peakVal = -1;
        int peakLag = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double ac = 0;
            for (int i = lag; i < n; i++) {
                ac += o[i] * o[i - lag];
            }
            ac /= (n - lag); // unbiased
            double bpm = 60000.0 / (lag * frameMs);
            double lg = Math.log(bpm / 120.0) / Math.log(2.0);
            double w = Math.exp(-0.5 * (lg / 0.9) * (lg / 0.9)); // log-Gaussian prior around 120 BPM
            acw[lag] = ac * w;
            sumw += acw[lag];
            if (acw[lag] > peakVal) {
                peakVal = acw[lag];
                peakLag = lag;
            }
        }
        double meanAcw = sumw / (maxLag - minLag + 1);
        double conf = peakVal / (meanAcw + 1e-9);

        /* Octave re-check: prefer the half/double of the prior-weighted peak if its comb of onset strengths
           is stronger (kills the classic half/double-tempo error). */
        int best = peakLag;
        double bestComb = comb(o, peakLag);
        int half = peakLag / 2;
        int dbl = peakLag * 2;
        if (half >= minLag && half <= maxLag) {
            double c = comb(o, half);
            if (c > bestComb) {
                bestComb = c;
                best = half;
            }
        }
        if (dbl >= minLag && dbl <= maxLag) {
            double c = comb(o, dbl);
            if (c > bestComb) {
                bestComb = c;
                best = dbl;
            }
        }

        int p = best;
        int phase = bestPhase(odf, p);
        return new BeatGrid(p, phase, 60000.0 / (p * frameMs), conf);
    }

    /* The beat-0 offset in [0,p): the phase whose pulse train collects the most onset energy. */
    private static int bestPhase(float[] odf, int p) {
        double best = -1;
        int phase = 0;
        for (int off = 0; off < p; off++) {
            double s = 0;
            for (int i = off; i < odf.length; i += p) {
                s += odf[i];
            }
            if (s > best) {
                best = s;
                phase = off;
            }
        }
        return phase;
    }

    private static double comb(double[] o, int p) {
        if (p < 1) {
            return -1;
        }
        double s = 0;
        int terms = 0;
        for (int k = 1; k * p < o.length; k++) {
            s += o[k * p];
            terms++;
        }
        return terms >= 1 ? s / terms : 0; // true per-pulse mean (dividing by k biased toward faster tempo)
    }
}
