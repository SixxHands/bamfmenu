package com.retro.bamfmenu.early.core.dsp;

import java.util.ArrayList;
import java.util.List;

/* The adaptive 3-condition peak-picker (Dixon / librosa.util.peak_pick) for a SuperFlux onset envelope. A
   frame is an onset iff: (1) it is a local maximum over [i-preMax, i+postMax]; (2) it exceeds a MOVING
   local-mean baseline over [i-preAvg, i+postAvg] by delta; (3) it is at least waitFrames after the previous
   accepted onset. Condition (2) is the core fix the old charter lacked: a single global threshold cannot fit
   a song's dynamics (too high misses quiet sections, too low spams loud ones), but a moving baseline rises
   and falls with the music. delta is the difficulty/density knob (lower => more notes). Pure logic, returns
   the onset FRAME indices (caller multiplies by hopMs for time). */
public final class OnsetPicker {

    private OnsetPicker() {}

    public static int[] pick(float[] odf, float delta, float absFloor, int preMax, int postMax, int preAvg, int postAvg, int waitFrames) {
        if (odf == null || odf.length < 3) {
            return new int[0];
        }
        int n = odf.length;
        List<Integer> out = new ArrayList<>();
        int last = -waitFrames - 1;
        for (int i = 0; i < n; i++) {
            float v = odf[i];
            if (v < absFloor) {
                continue; // absolute floor: ignore tiny onsets regardless of the local (possibly quiet) mean
            }
            int a = Math.max(0, i - preMax);
            int b = Math.min(n - 1, i + postMax);
            boolean isMax = true;
            for (int j = a; j <= b; j++) {
                if (odf[j] > v) {
                    isMax = false;
                    break;
                }
            }
            if (!isMax) {
                continue;
            }
            int aa = Math.max(0, i - preAvg);
            int bb = Math.min(n - 1, i + postAvg);
            float sum = 0f;
            int cnt = 0;
            for (int j = aa; j <= bb; j++) {
                sum += odf[j];
                cnt++;
            }
            float mean = cnt > 0 ? sum / cnt : 0f;
            if (v < mean + delta) {
                continue;
            }
            if (i - last <= waitFrames) {
                continue;
            }
            out.add(i);
            last = i;
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }
}
