package com.retro.bamfmenu.early.core.tools.audio.dsp;

import com.retro.bamfmenu.early.core.tools.audio.DetectorConfig;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/* Decode-time spectral analysis for the auto-charter (runs once per song from SongAnalysis.analyze, before
   the decoded PCM is freed; pure Java, no GL/MC). Produces a SuperFlux onset-strength envelope and a
   per-frame spectral centroid.

   Pipeline (Bello/Dixon/Boeck): mono-downmix -> framed STFT (N=2048, Hann, 10ms hop) -> log-spaced band
   magnitudes with log compression -> SuperFlux flux = sum over bands of the half-wave-rectified increase
   versus a 3-bin frequency MAXIMUM-FILTERED previous frame. The log compression surfaces soft melodic
   onsets that single-number RMS-energy flux buried, and the frequency max-filter suppresses the phantom
   re-onsets that vibrato / sustained notes produce, the two root causes of the old "spam + misses". The
   centroid (brightness) is a cheap pitch proxy the chart generator buckets into lanes for now. */
public final class SpectralAnalyzer {

    static final int N = 2048;            // FFT window
    public static final int HOP = 441;           // 10ms at 44.1kHz -> a clean 100 fps grid
    private static final double FMIN = 40.0;
    private static final double FMAX = 16000.0;
    private static final double BANDS_PER_OCT = 24.0; // quarter-tone log spacing
    /* The lead-melody ("piano") band vs the percussion ("marimba"/hats) band: onsets + pitch for the chart
       are taken from the MELODIC band so the arrows follow the lead instrument (not whatever is loudest), and
       the percussion envelope is only used for sparse gap-fill. The band edges are now PER-SONG, supplied via
       DetectorConfig (melLoHz / melHiHz / percHiHz); DetectorConfig.defaults() holds the historical WIDE band
       (120-5000Hz lead, up to 6000Hz percussion): wide so a narrow band does not miss clear notes (e.g. a late
       synth), letting per-onset pitch put low and high instruments on different lanes. */

    private SpectralAnalyzer() {}

    /* All arrays are parallel (one per frame). odf = whole-mix SuperFlux (legacy/fallback path); melodicOdf
       / percussionOdf = flux restricted to the melodic / percussion band groups; melodicPitch = Hz of the
       strongest melodic band that frame (a lead-instrument pitch proxy for lane assignment); centroid =
       whole-mix brightness (legacy); cdConf = rectified complex-domain PHASE confidence over the melodic
       band (0..~1): high at a real attack (phase-incoherent), low for a steady tone / reverb tail / drone
       (phase-predictable), which magnitude flux cannot tell apart; the charter vetoes low-cdConf onsets.
       hopMs = frame spacing in ms. */
    public record Result(float[] odf, float[] centroid, float[] melodicOdf, float[] melodicPitch, float[] melodicF0,
                  float[] percussionOdf, float[] cdConf, int hopMs) {}

    private static Result empty(int hopMs) {
        float[] e = new float[0];
        return new Result(e, e, e, e, e, e, e, hopMs);
    }

    public static Result analyze(ShortBuffer pcm, int channels, int frames, int sampleRate, DetectorConfig cfg) {
        int hopMs = (int) Math.round(1000.0 * HOP / Math.max(1, sampleRate));
        if (pcm == null || frames < N || channels < 1 || sampleRate <= 0) {
            return empty(hopMs);
        }

        /* Log-spaced, contiguous, non-overlapping bins-per-band (skip zero-width low bands so the bass does
           not get over-weighted by many duplicate sub-bin bands). */
        int nyqBin = N / 2;
        double fmax = Math.min(FMAX, sampleRate / 2.0);
        List<int[]> bandList = new ArrayList<>();
        int prevBin = Math.max(1, binOf(FMIN, sampleRate));
        for (int m = 1; ; m++) {
            double fhi = FMIN * Math.pow(2.0, m / BANDS_PER_OCT);
            if (fhi > fmax) {
                break;
            }
            int hi = Math.min(nyqBin, binOf(fhi, sampleRate));
            if (hi > prevBin) {
                bandList.add(new int[]{prevBin, hi});
                prevBin = hi;
            }
        }
        if (bandList.isEmpty()) {
            return empty(hopMs);
        }
        int nb = bandList.size();
        int[] bStart = new int[nb];
        int[] bEnd = new int[nb];
        double[] bandHz = new double[nb];
        for (int b = 0; b < nb; b++) {
            bStart[b] = bandList.get(b)[0];
            bEnd[b] = bandList.get(b)[1];
            bandHz[b] = (bStart[b] + bEnd[b]) * 0.5 * sampleRate / N;
        }
        int melLo = bandIndexAtOrAbove(bandHz, cfg.melLoHz());
        int melHi = bandIndexAtOrAbove(bandHz, cfg.melHiHz());
        int perLo = melHi;
        int perHi = bandIndexAtOrAbove(bandHz, cfg.percHiHz());

        /* Hann window. */
        float[] window = new float[N];
        for (int i = 0; i < N; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (N - 1)));
        }

        int centLo = Math.max(1, binOf(80.0, sampleRate));
        int centHi = Math.min(nyqBin, binOf(6000.0, sampleRate));

        /* Center each frame on its time (window spans [f*HOP - N/2, f*HOP + N/2)) so an onset detected at
           frame f maps cleanly to time f*hopMs; a right-aligned window would bias every note ~N/2 early. */
        int numFrames = (frames + HOP - 1) / HOP;
        float[] odf = new float[numFrames];
        float[] centroid = new float[numFrames];
        float[] melodicOdf = new float[numFrames];
        float[] percussionOdf = new float[numFrames];
        float[] melodicPitch = new float[numFrames];
        float[] melodicF0 = new float[numFrames];
        float[] cdConf = new float[numFrames];

        /* Bin range of the melodic band for the per-bin complex-domain phase analysis, plus the rolling two
           previous frames of magnitude + phase it needs. */
        int melBinLo = Math.max(1, binOf(cfg.melLoHz(), sampleRate));
        int melBinHi = Math.min(nyqBin, binOf(cfg.melHiHz(), sampleRate));
        int mb = Math.max(0, melBinHi - melBinLo);
        int f0LoBin = Math.max(1, binOf(80.0, sampleRate));    // HPS fundamental search range (low edge)
        int f0HiBin = Math.min(nyqBin, binOf(2000.0, sampleRate)); // ...high edge (musical fundamentals)
        float[] curPh = new float[mb];
        float[] prevPh1 = new float[mb];
        float[] prevPh2 = new float[mb];
        float[] prevMag1 = new float[mb];
        int frameCount = 0;

        Fft fft = new Fft(N);
        float[] re = new float[N];
        float[] im = new float[N];
        float[] mag = new float[nyqBin + 1];
        float[] curY = new float[nb];
        float[] prevY = new float[nb];
        boolean havePrev = false;
        float maxOdf = 1e-9f;
        float maxMel = 1e-9f;
        float maxPer = 1e-9f;

        for (int f = 0; f < numFrames; f++) {
            int start = f * HOP;
            for (int i = 0; i < N; i++) {
                int s = start - N / 2 + i;
                float v = 0f;
                if (s >= 0 && s < frames) {
                    int base = s * channels;
                    if (channels > 1) {
                        v = (pcm.get(base) + pcm.get(base + 1)) * 0.5f;
                    } else {
                        v = pcm.get(base);
                    }
                    v /= 32768f;
                }
                re[i] = v * window[i];
                im[i] = 0f;
            }
            fft.transform(re, im);
            for (int k = 0; k <= nyqBin; k++) {
                mag[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            }

            for (int b = 0; b < nb; b++) {
                float sum = 0f;
                for (int k = bStart[b]; k < bEnd[b]; k++) {
                    sum += mag[k];
                }
                curY[b] = (float) Math.log10(sum + 1.0); // log compression
            }

            if (havePrev) {
                float sf = 0f;
                float sfMel = 0f;
                float sfPer = 0f;
                for (int b = 0; b < nb; b++) {
                    /* SuperFlux: compare against the 3-bin frequency max of the PREVIOUS frame. */
                    float ref = prevY[b];
                    if (b > 0 && prevY[b - 1] > ref) {
                        ref = prevY[b - 1];
                    }
                    if (b + 1 < nb && prevY[b + 1] > ref) {
                        ref = prevY[b + 1];
                    }
                    float d = curY[b] - ref;
                    if (d > 0f) {
                        sf += d; // half-wave rectified
                        if (b >= melLo && b < melHi) {
                            sfMel += d;
                        }
                        if (b >= perLo && b < perHi) {
                            sfPer += d;
                        }
                    }
                }
                odf[f] = sf;
                if (sf > maxOdf) {
                    maxOdf = sf;
                }
                melodicOdf[f] = sfMel;
                if (sfMel > maxMel) {
                    maxMel = sfMel;
                }
                percussionOdf[f] = sfPer;
                if (sfPer > maxPer) {
                    maxPer = sfPer;
                }
            }

            /* Dominant melodic-band pitch this frame (argmax of the current log-band magnitudes within the
               melodic range) -> the lead note we assign a lane from. curY still holds this frame's bands. */
            int bestB = -1;
            float bestV = 0f;
            for (int b = melLo; b < melHi; b++) {
                if (curY[b] > bestV) {
                    bestV = curY[b];
                    bestB = b;
                }
            }
            melodicPitch[f] = bestB >= 0 ? (float) bandHz[bestB] : 0f;

            /* Harmonic Product Spectrum fundamental over 80-1500Hz: multiply each candidate's magnitude by
               its harmonics so a note's TRUE pitch wins even when bit-crush aliasing makes the brightest bin
               a high hiss (which the argmax above picks). The charter can gate onsets on this, dropping
               low-fundamental notes (a bit-crushed bass/background) while keeping the higher melody. */
            float bestHps = 0f;
            int bestF0 = -1;
            for (int k = f0LoBin; k <= f0HiBin; k++) {
                float prod = mag[k];
                for (int h = 2; h <= 5; h++) {
                    int kk = k * h;
                    if (kk > nyqBin) {
                        break;
                    }
                    prod *= mag[kk];
                }
                if (prod > bestHps) {
                    bestHps = prod;
                    bestF0 = k;
                }
            }
            /* Octave correction: HPS reports a SUBHARMONIC (f0/2, f0/3) for bright / harmonic-rich notes,
               which bit-crush makes everything. Promote up an octave while the 2x-harmonic bin still carries
               >=70% of the current bin's magnitude, so a bright lead's TRUE high pitch is recovered (the
               dull bass, whose 2x is weak, stays low). This both recovers the real pitch AND separates the
               bright lead from the bass (the lead promotes, the bass does not). */
            if (bestF0 > 0) {
                int k = bestF0;
                for (int o = 0; o < 3; o++) {
                    int k2 = 2 * k;
                    if (k2 <= nyqBin && mag[k2] >= 0.70f * mag[k]) {
                        k = k2;
                    } else {
                        break;
                    }
                }
                bestF0 = k;
            }
            melodicF0[f] = bestF0 > 0 ? (float) ((double) bestF0 * sampleRate / N) : 0f;

            float num = 0f;
            float den = 0f;
            for (int k = centLo; k <= centHi; k++) {
                float fr = (float) k * sampleRate / N;
                num += fr * mag[k];
                den += mag[k];
            }
            centroid[f] = den > 0f ? num / den : 0f;

            /* Complex-domain phase confidence over the melodic band: predict each bin assuming a STEADY tone
               (constant magnitude, phase advancing at the rate seen across the last two frames) and measure
               the rectified deviation. A real attack breaks the prediction (high cdConf); a drone / reverb
               tail obeys it (low), the separator magnitude flux cannot provide. Needs two previous frames. */
            for (int i = 0; i < mb; i++) {
                int k = melBinLo + i;
                curPh[i] = (float) Math.atan2(im[k], re[k]);
            }
            if (frameCount >= 2) {
                double numC = 0;
                double denC = 0;
                for (int i = 0; i < mb; i++) {
                    int k = melBinLo + i;
                    double predPh = 2.0 * prevPh1[i] - prevPh2[i];
                    double tr = prevMag1[i] * Math.cos(predPh);
                    double ti = prevMag1[i] * Math.sin(predPh);
                    if (mag[k] >= prevMag1[i]) { // rectified: count only energy increases (ignore reverb decay)
                        double dr = re[k] - tr;
                        double di = im[k] - ti;
                        numC += Math.sqrt(dr * dr + di * di);
                    }
                    denC += mag[k];
                }
                cdConf[f] = denC > 0 ? (float) (numC / denC) : 0f;
            }
            float[] tmpPh = prevPh2;
            prevPh2 = prevPh1;
            prevPh1 = curPh;
            curPh = tmpPh;
            for (int i = 0; i < mb; i++) {
                prevMag1[i] = mag[melBinLo + i];
            }
            frameCount++;

            float[] tmp = prevY;
            prevY = curY;
            curY = tmp;
            havePrev = true;
        }

        for (int f = 0; f < numFrames; f++) {
            odf[f] /= maxOdf; // normalize each envelope to [0,1] so thresholds are scale-invariant
            melodicOdf[f] /= maxMel;
            percussionOdf[f] /= maxPer;
        }
        return new Result(odf, centroid, melodicOdf, melodicPitch, melodicF0, percussionOdf, cdConf, hopMs);
    }

    private static int binOf(double freq, int sampleRate) {
        return (int) Math.round(freq * N / sampleRate);
    }

    /* First band whose center frequency is >= freq (or the band count if none), used to slice the bands
       into the melodic vs percussion groups. */
    private static int bandIndexAtOrAbove(double[] bandHz, double freq) {
        for (int b = 0; b < bandHz.length; b++) {
            if (bandHz[b] >= freq) {
                return b;
            }
        }
        return bandHz.length;
    }
}

