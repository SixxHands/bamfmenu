package com.retro.bamfmenu.early.core.editor;

/* The editor's musical grid: BPM + offset -> beat / measure / subdivision times + snapping. Pure math, headless
   (no bios/GL). offsetMs is where beat 0 lands; snapDivisor is subdivisions per beat (1 = beat, 2 = 8th, 4 =
   16th...). 4/4 is assumed in v1 (BEATS_PER_MEASURE = 4; time-signature control is a later add). bpm + offset
   are editable LIVE so the ruler lines up with the music, and snap is toggleable so you are never locked in. */
public final class TimingGrid {

    public static final int BEATS_PER_MEASURE = 4;
    public static final int[] DIVISORS = {1, 2, 3, 4, 6, 8, 12, 16};

    public double bpm;
    public double offsetMs;
    public boolean snapOn = true;
    public int divisorIndex = 3; // DIVISORS[3] = 4 -> 16th notes by default

    public TimingGrid(double bpm, double offsetMs) {
        this.bpm = bpm > 0 ? bpm : 120;
        this.offsetMs = offsetMs;
    }

    public int divisor() {
        return DIVISORS[Math.max(0, Math.min(DIVISORS.length - 1, divisorIndex))];
    }

    public String divisorLabel() {
        return "1/" + divisor(); // osu-style: subdivisions per beat (1/4 = sixteenths in 4/4)
    }

    public double msPerBeat() {
        return 60000.0 / (bpm > 0 ? bpm : 120);
    }

    public double snapStepMs() {
        return msPerBeat() / divisor();
    }

    public void cycleDivisor(int dir) {
        divisorIndex = Math.max(0, Math.min(DIVISORS.length - 1, divisorIndex + dir));
    }

    /* Snap a time to the nearest grid subdivision (or pass-through, clamped >=0, when snap is off). */
    public int snap(double ms) {
        if (!snapOn) {
            return (int) Math.round(Math.max(0, ms));
        }
        double step = snapStepMs();
        double snapped = offsetMs + Math.round((ms - offsetMs) / step) * step;
        return (int) Math.round(Math.max(0, snapped));
    }

    /* The grid slot one step in dir (+1 later / -1 earlier) from a time. */
    public int step(double ms, int dir) {
        double step = snapStepMs();
        return (int) Math.round(Math.max(0, snap(ms) + dir * step));
    }

    /* The subdivision index of a time relative to the offset (rounded). */
    public long subdivIndex(double ms) {
        return Math.round((ms - offsetMs) / snapStepMs());
    }

    public boolean isBeat(long subdiv) {
        return Math.floorMod(subdiv, (long) divisor()) == 0;
    }

    public boolean isMeasure(long subdiv) {
        return Math.floorMod(subdiv, (long) divisor() * BEATS_PER_MEASURE) == 0;
    }
}
