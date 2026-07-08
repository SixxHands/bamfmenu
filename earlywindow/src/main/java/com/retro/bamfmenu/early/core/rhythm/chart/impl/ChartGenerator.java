package com.retro.bamfmenu.early.core.rhythm.chart.impl;

import com.retro.bamfmenu.early.core.dsp.BeatGrid;
import com.retro.bamfmenu.early.core.dsp.OnsetPicker;
import com.retro.bamfmenu.early.core.rhythm.RhythmEngine;

import java.util.ArrayList;
import java.util.List;

/* Turns a song's onset envelope (from AudioPlayer's decode-time analysis) into a playable note chart by
   peak-picking. This is the runtime auto-charter; a song with no hand-authored .chart gets one of these.
   No GL, no audio -- pure arrays in, notes out.

   sensitivity (0..1) is the difficulty knob: higher = lower threshold + smaller minimum gap = denser,
   harder charts. Lanes are assigned from the per-hop zero-crossing rate (a cheap brightness proxy: bass
   low, treble high), and back-to-back same-lane notes are nudged apart because the engine's per-lane hold
   flag does not like two notes overlapping in one lane. v1 emits taps only (holds come from hand-edited
   charts later). */
public final class ChartGenerator {

    private ChartGenerator() {}

    /* The new charter: a SuperFlux onset envelope picked by the adaptive
       OnsetPicker, with lanes bucketed from the per-frame spectral centroid (brightness): low -> left, high
       -> right. This replaces the legacy RMS-flux + global-threshold + ZCR path (kept below as a fallback).
       Taps only for now; beat-snapping, proper pitch/contour lanes, and holds are later phases. */
    public static List<RhythmEngine.Note> generateV2(float[] odf, float[] centroid, double frameMs, float sensitivity,
                                              int lanes, int leadInMs) {
        List<RhythmEngine.Note> notes = new ArrayList<>();
        if (odf == null || odf.length < 3 || frameMs <= 0 || lanes < 1) {
            return notes;
        }
        float s = Math.max(0f, Math.min(1f, sensitivity));
        /* delta is the density knob: higher sensitivity -> lower delta -> more notes. waitFrames is the
           minimum gap (raised above the academic 30ms so adjacent arrows stay humanly hittable). frameMs is
           the exact analyzer frame spacing, so note times carry no sample-rate rounding drift. */
        float delta = 0.02f + (1f - s) * 0.18f;
        int waitFrames = Math.max(1, (int) Math.round((90.0 + (1.0 - s) * 80.0) / frameMs));
        int leadInFrames = Math.max(0, (int) Math.round(leadInMs / frameMs));
        int[] frames = OnsetPicker.pick(odf, delta, 0f, 3, 3, 3, 5, waitFrames);
        int prevLane = -1;
        for (int f : frames) {
            if (f < leadInFrames) {
                continue;
            }
            int lane = laneFromCentroid(centroid, f, lanes);
            if (lane == prevLane) {
                lane = (lane + 1) % lanes; // light anti-jack; proper lane logic is a later phase
            }
            notes.add(new RhythmEngine.Note(lane, (int) Math.round(f * frameMs), 0));
            prevLane = lane;
        }
        return notes;
    }

    /* Spectral centroid (Hz) -> lane: log-map ~120..5000 Hz to [0,1] and bucket. Low/bass on the left, bright
       on the right. A temporary stand-in for the proper pitch/contour assignment in a later phase. */
    private static int laneFromCentroid(float[] centroid, int f, int lanes) {
        if (centroid == null || f >= centroid.length || lanes < 2) {
            return 0;
        }
        float c = centroid[f];
        if (c <= 0f) {
            return 0;
        }
        double lo = Math.log(120.0);
        double hi = Math.log(5000.0);
        double cl = Math.log(Math.max(120.0, Math.min(5000.0, c)));
        int lane = (int) Math.floor((cl - lo) / (hi - lo) * lanes);
        return Math.max(0, Math.min(lanes - 1, lane));
    }

    /* PIANO-PRIORITY charter. Onsets AND lanes both come from the MELODIC band, so the arrows follow
       the lead instrument (the piano) regardless of how loud the percussion is. Lane = the lead pitch mapped
       into the song's OWN pitch range (adaptive p8..p92), so notes spread across all lanes and trace the
       melody up/down. Percussion only fills LONG melodic gaps, so a busy melodic song stays piano-only. Taps
       for now (beat-snap + holds are later phases). */
    /* cdFloor (a per-song DetectorConfig value): onsets whose complex-domain phase confidence is below it are
       vetoed as steady-tone / reverb / drone false positives (phase-stable events, not real attacks). 0
       disables the veto. DetectorConfig.defaults() uses ~0.40 -- gentle on crisp songs (their onsets sit well
       above it), aggressive on reverb-soaked ones. */
    public static List<RhythmEngine.Note> generateMelodic(float[] melOdf, float[] melPitch, float[] percOdf,
                                                   float[] cdConf, double frameMs, float sensitivity,
                                                   int lanes, int leadInMs, double knownBpm, float cdFloor,
                                                   float[] melF0, float minF0Hz) {
        List<RhythmEngine.Note> notes = new ArrayList<>();
        if (melOdf == null || melOdf.length < 3 || frameMs <= 0 || lanes < 1) {
            return notes;
        }
        float s = Math.max(0f, Math.min(1f, sensitivity));
        float delta = 0.02f + (1f - s) * 0.18f;
        int waitFrames = Math.max(1, (int) Math.round((90.0 + (1.0 - s) * 80.0) / frameMs));
        int leadInFrames = Math.max(0, (int) Math.round(leadInMs / frameMs));
        int[] frames = OnsetPicker.pick(melOdf, delta, 0.05f, 3, 3, 3, 5, waitFrames);

        /* Gather post-lead-in onsets + their lead pitch (NaN where the frame had no melodic energy), DROPPING
           same-pitch re-triggers within REFRACT_MS: a held / reverberating piano note keeps producing flux at
           its own pitch, which the detector would otherwise fragment into several notes ("the echo turned into
           individual notes"). Different-pitch notes (a real new note) are kept even if close in time. */
        /* beat grid: if the song has a clear enough beat, SNAP each onset to the 16th grid and DROP
           off-grid onsets -- real notes land on beats, drone/reverb noise does not, so this both quantizes
           the chart (curated feel) and removes the dreamy-reverb false notes. Weak beat -> raw onset times. */
        BeatGrid grid = BeatGrid.estimate(melOdf, frameMs, knownBpm);
        boolean snapping = grid.confident();
        final double samePitchLog = 0.045; // ~1.5 quarter-tones: treats near-identical pitch as the same note
        final int refractMs = 240;
        int[] onF = new int[frames.length];
        double[] onLogP = new double[frames.length];
        int cnt = 0;
        double lastKeptLogP = Double.NaN;
        int lastKeptMs = -100000;
        java.util.HashSet<Integer> usedSlots = new java.util.HashSet<>();
        for (int f : frames) {
            if (f < leadInFrames) {
                continue;
            }
            if (cdFloor > 0f && cdConf != null && f < cdConf.length && cdConf[f] < cdFloor) {
                continue; // phase-stable (drone/reverb/steady tone), not a real attack -> veto
            }
            if (minF0Hz > 0f) {
                float f0 = (melF0 != null && f < melF0.length) ? melF0[f] : 0f;
                if (f0 < minF0Hz) {
                    continue; // fundamental too low (bit-crushed bass/background, not the high melody) -> veto
                }
            }
            /* Lane pitch: the HPS fundamental when the song uses the f0 gate (the bit-crush argmax is just
               hiss there), else the legacy melodic-band argmax. */
            float p;
            if (minF0Hz > 0f && melF0 != null && f < melF0.length) {
                p = melF0[f];
            } else {
                p = (melPitch != null && f < melPitch.length) ? melPitch[f] : 0f;
            }
            double lp = p > 0f ? Math.log(p) : Double.NaN;
            int t = (int) Math.round(f * frameMs);
            if (!Double.isNaN(lp) && !Double.isNaN(lastKeptLogP)
                    && Math.abs(lp - lastKeptLogP) < samePitchLog && (t - lastKeptMs) < refractMs) {
                continue; // same-pitch reverb/sustain re-trigger -> not a new note
            }
            int gf = f;
            if (snapping) {
                gf = grid.nearestFrame(f); // quantize for the on-beat feel; NO positional rejection so an
                                           // expressive song's off-grid real notes are never erased
                if (!usedSlots.add(gf)) {
                    continue; // dedupe: one note per slot (thins clustered onsets only)
                }
            }
            onF[cnt] = gf;     // snapped frame -> note TIME
            onLogP[cnt] = lp;  // pitch from the ORIGINAL onset frame
            cnt++;
            if (!Double.isNaN(lp)) {
                lastKeptLogP = lp;
                lastKeptMs = t; // refractory tracks original onset timing
            }
        }
        if (cnt == 0) {
            return notes;
        }

        /* Adaptive pitch range (p8..p92 of valid pitches) so the lane mapping spans the song's actual melody. */
        double[] valid = new double[cnt];
        int vn = 0;
        for (int i = 0; i < cnt; i++) {
            if (!Double.isNaN(onLogP[i])) {
                valid[vn++] = onLogP[i];
            }
        }
        double pLo;
        double pHi;
        if (vn >= 4) {
            java.util.Arrays.sort(valid, 0, vn);
            pLo = valid[Math.max(0, (int) Math.round(0.08 * (vn - 1)))];
            pHi = valid[Math.min(vn - 1, (int) Math.round(0.92 * (vn - 1)))];
        } else {
            pLo = Math.log(150.0);
            pHi = Math.log(1300.0);
        }
        if (pHi - pLo < 1e-3) {
            pLo -= 0.5;
            pHi += 0.5;
        }

        int prevLane = -1;
        for (int i = 0; i < cnt; i++) {
            int lane;
            if (Double.isNaN(onLogP[i])) {
                lane = prevLane >= 0 ? prevLane : 0;
            } else {
                double t = (onLogP[i] - pLo) / (pHi - pLo);
                lane = (int) Math.floor(t * lanes);
                lane = Math.max(0, Math.min(lanes - 1, lane));
            }
            if (lane == prevLane) {
                lane = (lane + 1) % lanes; // light anti-jack so a held pitch does not stack one lane
            }
            notes.add(new RhythmEngine.Note(lane, (int) Math.round(onF[i] * frameMs), 0));
            prevLane = lane;
        }

        if (minF0Hz <= 0f) {
            /* The percussion fill pulls from the 5-6kHz band, which for a fundamental-gated bit-crushed song
               (Recovery) is pure hiss -- it would add back exactly the junk the gate removed. Skip it there. */
            addPercussionFill(notes, percOdf, frameMs, s, lanes, leadInFrames, snapping ? grid : null);
        }
        notes.sort((a, b) -> Integer.compare(a.timeMs, b.timeMs));
        return notes;
    }

    /* Sparse fill: only where the melody RESTS (no note within FILL_GAP_MS/2) drop in a strong percussion
       onset, so a busy melodic song (e.g. FAR) stays piano-only but a sparse one gets a light backbone. When
       a beat grid is active the fill is snapped + off-grid-rejected too, so it stays on-beat with the rest. */
    private static void addPercussionFill(List<RhythmEngine.Note> notes, float[] percOdf, double frameMs,
                                          float s, int lanes, int leadInFrames, BeatGrid grid) {
        if (percOdf == null || percOdf.length < 3 || notes.isEmpty()) {
            return;
        }
        final int fillGapMs = 900;
        float delta = 0.10f + (1f - s) * 0.15f; // sparser than the melodic pass
        int waitFrames = Math.max(1, (int) Math.round(250.0 / frameMs));
        int[] pf = OnsetPicker.pick(percOdf, delta, 0f, 3, 3, 3, 5, waitFrames);
        int fillLane = 0;
        for (int f : pf) {
            if (f < leadInFrames) {
                continue;
            }
            int gf = (grid != null) ? grid.nearestFrame(f) : f; // quantize fill to the grid too
            int t = (int) Math.round(gf * frameMs);
            boolean near = false;
            for (RhythmEngine.Note n : notes) {
                if (Math.abs(n.timeMs - t) < fillGapMs / 2) {
                    near = true;
                    break;
                }
            }
            if (near) {
                continue;
            }
            notes.add(new RhythmEngine.Note(fillLane % lanes, t, 0));
            fillLane++;
        }
    }

    public static List<RhythmEngine.Note> generate(float[] onsets, int[] zcr, int hopMs, float sensitivity,
                                            int lanes, int leadInMs) {
        List<RhythmEngine.Note> notes = new ArrayList<>();
        if (onsets == null || onsets.length < 3 || hopMs <= 0 || lanes < 1) {
            return notes;
        }
        float s = Math.max(0f, Math.min(1f, sensitivity));
        float threshold = Math.max(0.02f, (1f - s) * 0.30f);
        int minGapHops = Math.max(1, (int) Math.round((90.0 + (1.0 - s) * 120.0) / hopMs));
        int leadInHops = Math.max(0, leadInMs / hopMs);
        int lastHop = -minGapHops;
        int prevLane = -1;

        for (int h = 1; h < onsets.length - 1; h++) {
            if (h < leadInHops) {
                continue;
            }
            float v = onsets[h];
            if (v < threshold) {
                continue;
            }
            if (v < onsets[h - 1] || v < onsets[h + 1]) {
                continue; // not a local peak
            }
            if (h - lastHop < minGapHops) {
                continue;
            }
            int lane = laneFor(zcr, h, lanes);
            if (lane == prevLane) {
                lane = (lane + 1) % lanes;
            }
            notes.add(new RhythmEngine.Note(lane, h * hopMs, 0));
            lastHop = h;
            prevLane = lane;
        }
        return notes;
    }

    /* Brightness (zero-crossing rate) -> lane: bass on the left, treble on the right. */
    private static int laneFor(int[] zcr, int h, int lanes) {
        if (zcr == null || h >= zcr.length || lanes < 2) {
            return h % Math.max(1, lanes);
        }
        int z = zcr[h];
        int lane;
        if (z < 8) {
            lane = 0;
        } else if (z < 20) {
            lane = 1;
        } else if (z < 45) {
            lane = 2;
        } else {
            lane = 3;
        }
        return Math.min(lane, lanes - 1);
    }
}
