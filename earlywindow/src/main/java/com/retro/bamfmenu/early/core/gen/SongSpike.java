package com.retro.bamfmenu.early.core.gen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/* SPIKE for "A Song Is Born" (see docs/A_SONG_IS_BORN.md): proving the music can sound PRODUCED, not
   just correct, before any engine wiring. Pure Java (no LWJGL). Original work, standard public DSP only.

   Architecture is RHYTHMICALLY STRATIFIED, grounded in a study of "A Mind Is Born"'s technique (its note data /
   code are NOT used -- principle only): one master sample-clock (idx), every rate an integer division of it, so
   all layers phase-lock for free. The FAST layer is a 16th-note generative LEAD (~16x the kick) drawn from a
   curated semitone-free A/C/E table (index 0 = REST so it breathes); a 4-step motif is regenerated per 8-bar
   phrase then repeated-with-variation -- wrong notes are impossible and it never just noodles. The SLOW
   foundation moves an order of magnitude less often: chords turn over every 2 bars, the bass holds its root per
   chord, and a sub-tonic drone + 3-voice saw PAD bed the harmony. A beat-locked sidechain DUCK pumps the slow
   voices (not the lead). One SONG-LONG one-shot filter sweep (Chamberlin SVF, high resonance) + a ducked
   feedback delay carry the long-form motion; a four-on-floor KICK and deep PWM keep the surface alive.
   A DEEP rich detuned-saw synth layer (+ sub octave) swells in around 0:30 and takes over the middle while the
   lead softens, then the lead returns; the song-long filter opens then CLOSES over the final section so the
   ending eases out the way the intro eased in. Harmony is an A/B toggle: "prog" = Am-F-C-G half-time, "static"
   = a single A-minor pedal (A-Mind-Is-Born style, all motion from the surface) -- same seed => same melody.
   A minor, 132 BPM, 64 bars (~1:56), 8-bar sections. Master = tanh soft-clip + edge fades.
   Run: gradlew :earlywindow:genSong [-Pharmony=prog|static]. refactors this into SongGenerator/ToneGen. */
public final class SongSpike {

    private static final int SR = 44100;
    private static final int BPM = 132;          // 132 BPM (was 90): faster, driving surface
    private static final int STEPS_PER_BAR = 16;
    private static final int BARS = 64;          // 64 bars (was 8): ~1:56 at 132 BPM, eight 8-bar sections
    private static final int SECTION_BARS = 8;   // novelty rationed: one variable per 8-bar block
    private static final int BARS_PER_CHORD = 2; // half-time harmonic rhythm: hides the per-bar loop point
    private static final long DEFAULT_SEED = 0x1A2B3C4DL;

    /* Curated A-minor lead table (OUR values; tune by ear). index 0 = REST. Only pitch-classes A/C/E
       (chord tones of the static A anchor), NO semitone neighbors, so a random walk can never clash.
       A4..E6; a per-section register band (see render loop) lifts the whole line for wide-range depth. */
    private static final int[] LEAD_TABLE = { -1, 69, 72, 76, 81, 84, 88 }; // REST,A4,C5,E5,A5,C6,E6
    private static final int LEAD_HI = 103;      // E7 ceiling for the octave-band lift (fold back down)

    /* ===== LEAD MELODY MODEL (lead-only) ============================================================
       A per-section MODE (parallel to the octave band) gives each 8-bar section its own rhythm + timbre, so the
       line is NOT a uniform 16th arpeggio. DURATION = INTER-ONSET INTERVAL: a note holds (ADSR sustains) until the
       next onset, so mixed note lengths fall out of onset SPACING with no extra oscillator. Rests live in the
       onset MASK (not LEAD_TABLE), so every fired note is a pure A/C/E chord tone. Tune by ear across seeds. */
    private static final int MODE_THEME = 0, MODE_DEVELOP = 1, MODE_LIFT = 2,
            MODE_PULLBACK = 3, MODE_CLIMAX = 4, MODE_ENDING = 5;
    // S0..S7, parallel to octBySection {0,0,12,12,24,12,24,0}: S6 (+24, 2nd-to-last) = the fast HERO climax;
    // S5 = PULLBACK -- a lighter, exposed fast line where the pack's "sudden clarity" breakdown lands.
    private static final int[] MODE_BY_SECTION = {
            MODE_THEME, MODE_THEME, MODE_DEVELOP, MODE_DEVELOP,
            MODE_LIFT, MODE_PULLBACK, MODE_CLIMAX, MODE_ENDING };

    // Curated 16-step onset masks (bit s set => an onset at step s; LSB = step 0). ALL FAST: 16th/8th runs with
    // rests for phrasing (the pack host wants a fast melodic texture -- variety comes from rest placement + per-section
    // timbre/octave + the arrangement arc, NOT long held notes). Longest note anywhere is a dotted-8th (M_SYNCO).
    private static final int M_RUN1    = 0b0111_0111_0111_0111; // {0,1,2,4,5,6,...}  16th gallop, a breath per beat
    private static final int M_RUN2    = 0b1101_1101_1101_1101; // {0,2,3,4,6,7,...}  syncopated 16th run
    private static final int M_DENSE   = 0b0111_1111_0111_1111; // {0..6,8..14}       near-continuous 16ths (2 breaths)
    private static final int M_EIGHTHS = 0b0101_0101_0101_0101; // {0,2,..,14}         straight 8ths (clear, fast)
    private static final int M_SYNCO   = 0b0100_1001_0100_1001; // {0,3,6,8,11,14}     syncopated (a little air)
    private static final int[] RUN_MASKS  = { M_RUN1, M_RUN2, M_DENSE }; // fastest 16th pool (develop / climax / runs)
    private static final int[] LITE_MASKS = { M_EIGHTHS, M_SYNCO };      // lighter fast pool (8ths) for breakdown/end

    /* A minor i-VI-III-VII = Am, F, C, G as pitch-class sets; index 0 is the root. */
    private static final int[][] CHORDS = {
            {9, 0, 4},   // Am (A C E)
            {5, 9, 0},   // F  (F A C)
            {0, 4, 7},   // C  (C E G)
            {7, 11, 2},  // G  (G B D)
    };
    /* Bass plays a tresillo within each bar: E(3,8) over 8 steps, twice. */
    private static final boolean[] BASS_ONSET = {
            true, false, false, true, false, false, true, false,
            true, false, false, true, false, false, true, false,
    };

    private SongSpike() {
    }

    public static void main(String[] args) throws IOException {
        String out = args.length > 0 && !args[0].isBlank() ? args[0] : "song_spike.wav";
        long seed = args.length > 1 && !args[1].isBlank() ? Long.parseLong(args[1].trim()) : DEFAULT_SEED;
        // harmony A/B: "prog" (default) keeps Am-F-C-G half-time; "static" collapses to one A-minor pedal
        // (A-Mind-Is-Born style) so ALL motion comes from the surface -- same seed => same melody either way.
        boolean staticHarmony = args.length > 2 && args[2].trim().equalsIgnoreCase("static");

        int samplesPerStep = (int) Math.round((60.0 / BPM / 4.0) * SR);
        int total = BARS * STEPS_PER_BAR * samplesPerStep;
        short[] pcm = new short[total];

        // ONE master clock: every musical + timbral rate is an integer division of the sample index.
        // (idx below is that clock; the bar/step for-loops are only the render scaffold.) Per-sample we
        // derive: step = idx/samplesPerStep, beatPhase = idx%beatSamples, songProgress = idx/(double)total.
        int beatSamples = 4 * samplesPerStep;
        int leadNoteLen = (int) (0.92 * samplesPerStep); // crisp 16ths with a small gap (was 2*samplesPerStep)

        Xorshift64 rng = new Xorshift64(seed);

        // --- voice state ---
        double subPhase = 0;
        double subInc = midiToFreq(33) / SR; // A1 sub drone

        double[] padPhase = new double[3];
        double[] padFreq = new double[3];
        double[] padTarget = new double[3];
        for (int k = 0; k < 3; k++) {
            padTarget[k] = midiToFreq(48 + CHORDS[0][k]);
            padFreq[k] = padTarget[k];
        }

        double leadPhase = 0, leadInc = midiToFreq(69) / SR;
        int leadNS = 1 << 30;
        // generative-lead state: a 4-cell PITCH motif (indices into LEAD_TABLE, 1..6 -- rests live in the per-bar
        // onset MASK, not the table). Per-section MODE picks the rhythm mask + timbre; duration = inter-onset gap.
        int[] motif = { 3, 4, 5, 4, 3, 2, 3, 4 }; // an 8-cell phrase (not a 4-note arpeggio); regenerated per phrase
        int leadOct = 0;                 // per-section register band (semitone lift), set in the bar loop
        // ADSR floor: a note must outlast attack+decay+release (~4145 smp) or the envelope segments overlap (click).
        // Min onset spacing is 2 steps (~10022 smp) so this never actually fires -- it is a defensive floor.
        final int LEAD_MIN_LEN = (int) (0.004 * SR) + (int) (0.05 * SR) + (int) (0.04 * SR);
        Xorshift64 leadRng = rng.spawn(0x1EAD7L); // all NEW lead-rhythm draws (the motif's rng draw order is unchanged)
        int leadP = 0;                   // pitch-on-onset pointer (advances only when a note actually fires)
        int leadWalk = 4;                // bounded-Markov index for DEVELOP runs (LOCAL -> deterministic)
        boolean[] leadOnset = new boolean[STEPS_PER_BAR]; // this bar's onset mask
        int[] leadDur = new int[STEPS_PER_BAR];           // steps-to-next-onset per onset step (= note length)
        int leadLastOnset = 0;           // index of the bar's final onset (for cadence forcing)
        double leadGate = 0.95;          // articulation multiplier, set per bar by mode

        double bassPhase = 0, bassInc = midiToFreq(45) / SR;
        int bassNS = 1 << 30, bassNoteLen = (int) (0.22 * SR);

        double kickPhase = 0;
        int kickNS = 1 << 30, kickNoteLen = (int) (0.18 * SR);

        // deep rich synth layer (enters ~30s, takes over the middle): two detuned saws (A3) + a sub octave,
        // plus deepPhaseSub2 (a 2nd octave-down sub that only joins AFTER the clarity break for "more depth").
        double deepPhaseA = 0, deepPhaseB = 0, deepPhaseSub = 0, deepPhaseSub2 = 0;
        double deepIncA = midiToFreq(57) / SR, deepIncB = midiToFreq(57) * 1.005 / SR;
        int deepNS = 1 << 30, deepNoteLen = (int) (0.22 * SR);

        double pwmPhase = 0; // slow LFO for pulse-width

        // --- effects state ---
        double svfLow = 0, svfBand = 0;
        int delayLen = 3 * samplesPerStep;          // dotted-8th echo
        double[] delayBuf = new double[delayLen];
        int delayIdx = 0;

        double fadeIn = 0.15 * SR, fadeOut = 2.5 * SR; // longer outro: ease out the way the intro eases in
        int idx = 0;
        for (int bar = 0; bar < BARS; bar++) {
            int section = bar / SECTION_BARS;
            int[] chord = staticHarmony ? CHORDS[0]                          // collapsed: static A-minor pedal
                    : CHORDS[(bar / BARS_PER_CHORD) % CHORDS.length];        // half-time turnover (every 2 bars)
            for (int k = 0; k < 3; k++) {
                padTarget[k] = midiToFreq(48 + chord[k]); // pad glides toward the (slower) chord (no click)
            }
            // ration novelty per 8-bar block: a register band lifts the lead by octaves for wide-range depth.
            int[] octBySection = { 0, 0, 12, 12, 24, 12, 24, 0 };
            leadOct = octBySection[section % octBySection.length];
            if (bar % SECTION_BARS == 0) {               // regenerate the motif once per 8-bar phrase
                int mp = 1 + rng.nextInt(LEAD_TABLE.length - 1); // VOICE-LED phrase: smooth +/-1 steps, not jumps
                for (int m = 0; m < motif.length; m++) {
                    mp += rng.nextInt(3) - 1;            // -1 / 0 / +1 through the A/C/E table
                    if (mp < 1) mp = 1;
                    if (mp > 6) mp = 6;
                    motif[m] = mp;                       // PITCH index 1..6 (rest lives in the mask)
                }
                leadP = 0;                               // restate the head from the top each phrase
            } else if (bar % 2 == 0) {                   // ... then nudge ONE cell (repeat + vary)
                motif[rng.nextInt(motif.length)] = 1 + rng.nextInt(LEAD_TABLE.length - 1);
            }
            for (int step = 0; step < STEPS_PER_BAR; step++) {
                int mode = MODE_BY_SECTION[section % MODE_BY_SECTION.length]; // this section's melodic treatment
                if (step == 0) {                          // build this bar's FAST rhythm (onset mask + durations) once
                    int mask;
                    switch (mode) {
                        case MODE_DEVELOP:
                        case MODE_CLIMAX:
                            mask = RUN_MASKS[leadRng.nextInt(RUN_MASKS.length)]; break;   // fastest 16th runs
                        case MODE_PULLBACK:
                        case MODE_ENDING:
                            mask = LITE_MASKS[leadRng.nextInt(LITE_MASKS.length)]; break; // lighter fast (8ths)
                        default:                                                          // THEME / LIFT
                            mask = (leadRng.nextInt(2) == 0) ? M_RUN1 : M_RUN2;           // dense 16ths
                    }
                    for (int s = 0; s < STEPS_PER_BAR; s++) leadOnset[s] = ((mask >> s) & 1) != 0;
                    leadOnset[0] = true;                  // always anchor the downbeat (never a dead bar)
                    leadLastOnset = 0;
                    for (int s = 0; s < STEPS_PER_BAR; s++) {
                        if (!leadOnset[s]) { leadDur[s] = 0; continue; }
                        int n = s + 1; while (n < STEPS_PER_BAR && !leadOnset[n]) n++;
                        leadDur[s] = n - s;               // steps to next onset (n==STEPS_PER_BAR => holds to bar end)
                        leadLastOnset = s;
                    }
                    leadGate = (mode == MODE_DEVELOP || mode == MODE_CLIMAX) ? 0.88 // crisp, dense runs
                             : 0.92;                                                // crisp fast notes
                }
                if (leadOnset[step]) {                     // fire ONLY on onset steps; the gap becomes note length
                    int durSteps = leadDur[step];
                    leadNoteLen = (int) (durSteps * samplesPerStep * leadGate);
                    if (leadNoteLen < LEAD_MIN_LEN) leadNoteLen = LEAD_MIN_LEN; // never click

                    int li;                               // pitch index into LEAD_TABLE (1..6 => always a chord tone)
                    if (mode == MODE_CLIMAX) {
                        final int[] CONTOUR = { 3, 1, 2 };            // descending pitch-class contour E -> A -> C
                        li = CONTOUR[leadP % CONTOUR.length];
                    } else if (mode == MODE_DEVELOP) {
                        leadWalk += leadRng.nextInt(3) - 1;           // bounded +/-1 Markov walk (smooth, anti-garble)
                        if (leadWalk < 1) leadWalk = 1;
                        if (leadWalk > 6) leadWalk = 6;
                        li = leadWalk;
                    } else {
                        li = motif[leadP];                            // pitch-on-onset pointer (no step%4 cycle)
                    }
                    int stage = bar % SECTION_BARS;                   // question/answer cadence forcing (lead-only)
                    if (step == leadLastOnset) {
                        if (stage == SECTION_BARS - 2) {
                            li = (leadRng.nextInt(2) == 0) ? 3 : 6;   // antecedent: open on a non-tonic (E)
                        } else if (stage == SECTION_BARS - 1) {
                            li = 1;                                   // consequent: resolve to tonic A (normal length)
                        }
                    }
                    leadP = (leadP + 1) % motif.length;               // advance pointer only on a fired note

                    int note = LEAD_TABLE[li] + leadOct;              // li is 1..6 => always an A/C/E chord tone
                    while (note > LEAD_HI) note -= 12;                // EXISTING bounds-safe fold (unchanged)
                    leadInc = midiToFreq(note) / SR;
                    leadNS = 0;                            // retrigger envelope only; leadPhase NOT reset (no click)
                }
                // (a non-onset step lets the current note sustain/decay via the running ADSR -- a tie or a rest.)
                if (BASS_ONSET[step]) {
                    bassInc = midiToFreq(36 + chord[0]) / SR; // chord root, low octave (held per 2-bar chord)
                    bassNS = 0;
                }
                if (step % 4 == 0) { // kick on the quarter
                    kickNS = 0;
                }
                if (step % 2 == 0) { // deep synth-bass: driving 8ths, root with a fifth lift mid-bar
                    int deepPc = (step % 8 == 4) ? chord[2] : chord[0];
                    double deepF = midiToFreq(48 + deepPc); // A3 region; the sub plays an octave below
                    deepIncA = deepF / SR;
                    deepIncB = deepF * 1.005 / SR;
                    deepNS = 0;
                }
                for (int i = 0; i < samplesPerStep; i++) {
                    // ===== ARRANGEMENT ARC (off the master clock): start -> fade in -> drowned out ->
                    //       sudden clarity (sharp brief breakdown) -> everything together (deep returns
                    //       BOOSTED) -> fade out to the opening texture. All in continuous bar units. =====
                    double barPos = idx / (double) (samplesPerStep * STEPS_PER_BAR); // continuous bar index 0..BARS
                    double notch = 1.0 - ramp(barPos, 40.0, 40.15) * (1.0 - ramp(barPos, 42.0, 42.15)); // clarity cut
                    double outro = 1.0 - ramp(barPos, 57.0, 63.0);     // strip deep+groove back to the intro at the end
                    double deepGain = ramp(barPos, 16.0, 20.0) * notch * outro;  // in ~0:30, cut at break, boosted back
                    double deepBoost = 1.0 + 0.5 * ramp(barPos, 42.0, 44.0);     // deeper than ever after the break
                    double grooveGate = ramp(barPos, 7.0, 8.0) * notch * outro;  // kick+bass arrive at the fade-in
                    double leadGainEnv;                                // melody: full -> drowned -> clarity -> settle
                    if (barPos < 18.0) {
                        leadGainEnv = 1.0;                             // start: the melody is the focus
                    } else if (barPos < 40.0) {
                        leadGainEnv = 1.0 - 0.7 * ramp(barPos, 18.0, 22.0);       // buried under the deep layer
                    } else if (barPos < 42.0) {
                        leadGainEnv = 0.3 + 0.7 * ramp(barPos, 40.0, 40.2);       // SNAP to full: exposed clarity
                    } else {
                        leadGainEnv = 1.0 - 0.2 * ramp(barPos, 42.0, 44.0);       // settle ~0.8 for the climax + outro
                    }

                    // sub drone
                    double sub = triangle(subPhase) * 0.10;
                    subPhase = wrap(subPhase + subInc);

                    // pad: 3 glided saws (the harmony bed)
                    double pad = 0;
                    for (int k = 0; k < 3; k++) {
                        padFreq[k] += (padTarget[k] - padFreq[k]) * 0.0009; // glide ~ a few ms
                        double inc = padFreq[k] / SR;
                        pad += saw(padPhase[k], inc) * 0.05;
                        padPhase[k] = wrap(padPhase[k] + inc);
                    }

                    // lead: PWM pulse, chord-locked
                    double duty = 0.5 + 0.45 * Math.sin(2 * Math.PI * pwmPhase); // deeper shimmer (was 0.18)
                    pwmPhase = wrap(pwmPhase + 0.1 / SR);                        // ~0.1 Hz one-way drift
                    double leadEnv = adsr(leadNS, leadNoteLen, 0.004 * SR, 0.05 * SR, 0.4, 0.04 * SR);
                    // timbre rotation: same pitch, different "instrument" per section (the AMIB waveform-rotation
                    // idea) -- so the ear hears voice changes and the clarity breakdown steps forward distinctly.
                    double leadOsc;
                    if (mode == MODE_CLIMAX) {
                        leadOsc = saw(leadPhase, leadInc);             // rich, soaring hero line
                    } else if (mode == MODE_PULLBACK) {
                        leadOsc = triangle(leadPhase);                 // pure/flute-like: the exposed clarity voice
                    } else {
                        leadOsc = pulse(leadPhase, leadInc, duty);     // the main PWM lead
                    }
                    double lead = leadOsc * leadEnv * 0.18;
                    leadPhase = wrap(leadPhase + leadInc);
                    leadNS++;

                    // bass pluck (dry)
                    double bassEnv = adsr(bassNS, bassNoteLen, 0.003 * SR, 0.10 * SR, 0.25, 0.06 * SR);
                    double bass = triangle(bassPhase) * bassEnv * 0.22 * grooveGate; // arrives at fade-in, cut at break
                    bassPhase = wrap(bassPhase + bassInc);
                    bassNS++;

                    // kick (dry): sine with a fast pitch drop + exp amp decay
                    double kickAmp = kickNS < kickNoteLen ? Math.exp(-kickNS / (0.09 * SR)) : 0.0;
                    if (kickNS < 0.001 * SR) {
                        kickAmp *= kickNS / (0.001 * SR); // tiny attack, no click
                    }
                    double kFreq = 50.0 + 120.0 * Math.exp(-kickNS / (0.015 * SR));
                    double kick = Math.sin(2 * Math.PI * kickPhase) * kickAmp * 0.55 * grooveGate; // in at fade-in, cut
                    kickPhase = wrap(kickPhase + kFreq / SR);
                    kickNS++;

                    // deep rich layer: detuned saws + sub octave; its gain/depth come from the arrangement arc above.
                    double deepEnv = adsr(deepNS, deepNoteLen, 0.005 * SR, 0.12 * SR, 0.6, 0.06 * SR);
                    double deepSaws = saw(deepPhaseA, deepIncA) + saw(deepPhaseB, deepIncB);
                    double deepSub = Math.sin(2 * Math.PI * deepPhaseSub);
                    double deepSub2 = Math.sin(2 * Math.PI * deepPhaseSub2) * ramp(barPos, 42.0, 44.0); // post-break depth
                    double deep = (deepSaws * 0.085 + deepSub * 0.10 + deepSub2 * 0.09) * deepEnv * deepGain * deepBoost;
                    deepPhaseA = wrap(deepPhaseA + deepIncA);
                    deepPhaseB = wrap(deepPhaseB + deepIncB);
                    deepPhaseSub = wrap(deepPhaseSub + deepIncA * 0.5);
                    deepPhaseSub2 = wrap(deepPhaseSub2 + deepIncA * 0.25);  // two octaves below the A3 saws
                    deepNS++;

                    // beat-locked sidechain duck (off the master clock): pump the SLOW voices, never the lead.
                    int inBeat = idx % beatSamples;
                    double duck = 0.35 + 0.65 * (1.0 - Math.exp(-inBeat / (0.16 * SR))); // dip on the beat, recover
                    pad *= duck;
                    sub *= duck;

                    // harmonic bus -> swept resonant low-pass -> ducked feedback delay (space)
                    double harmonic = pad + lead * leadGainEnv + sub + deep; // lead+deep un-ducked; pad/sub ducked
                    double songProgress = idx / (double) total;          // one-shot master-clock ramp
                    double sweep = songProgress < 0.86                   // open over the first ~86% of the song...
                            ? songProgress / 0.86
                            : Math.max(0.10, 1.0 - (songProgress - 0.86) / 0.14); // ...then close over the ending
                    double cutoff = 300 + 3000 * sweep;                  // mirrors the dark->bright intro at the end
                    double fc = 2.0 * Math.sin(Math.PI * cutoff / SR);
                    double hp = harmonic - svfLow - 0.6 * svfBand;       // resonance pinned high (unchanged)
                    svfBand += fc * hp;
                    svfLow += fc * svfBand;
                    double filtered = svfLow;

                    double delayed = delayBuf[delayIdx];
                    double spaced = filtered + 0.28 * delayed * duck;    // echo return pumps with the kick
                    delayBuf[delayIdx] = filtered + 0.33 * delayed;
                    delayIdx = delayIdx + 1 == delayLen ? 0 : delayIdx + 1;

                    double mix = Math.tanh(spaced + bass + kick);
                    double g = 1.0;
                    if (idx < fadeIn) {
                        g = idx / fadeIn;
                    } else if (idx > total - fadeOut) {
                        g = Math.max(0.0, (total - idx) / fadeOut);
                    }
                    mix *= g;
                    if (mix > 1.0) {
                        mix = 1.0;
                    } else if (mix < -1.0) {
                        mix = -1.0;
                    }
                    pcm[idx++] = (short) (mix * 32767.0);
                }
            }
        }

        writeWav(out, pcm, SR);
        System.out.println("[songspike] wrote " + out + "  (" + total + " samples, "
                + String.format("%.1f", total / (double) SR) + "s, seed=" + Long.toHexString(seed) + ")");
    }

    // (pickChordTone deleted -- the cramped single-octave nearest-tone walk that produced the "twinkle-
    //  twinkle" feel is replaced by LEAD_TABLE + the per-phrase motif generator in the render loop.)

    private static double adsr(int n, int noteLen, double aS, double dS, double sustain, double rS) {
        double relStart = noteLen - rS;
        double e;
        if (n < aS) {
            e = n / aS;
        } else if (n < aS + dS) {
            e = 1.0 - (1.0 - sustain) * ((n - aS) / dS);
        } else if (n < relStart) {
            e = sustain;
        } else if (n < noteLen) {
            e = sustain * Math.max(0.0, (noteLen - n) / rS);
        } else {
            e = 0.0;
        }
        return Math.max(0.0, Math.min(1.0, e));
    }

    private static double midiToFreq(int midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    private static double wrap(double phase) {
        return phase >= 1.0 ? phase - 1.0 : phase;
    }

    /* A clamped 0->1 linear ramp over [a,b] seconds; used for the deep-layer swell and the lead-softening. */
    private static double ramp(double t, double a, double b) {
        if (t <= a) {
            return 0.0;
        }
        if (t >= b) {
            return 1.0;
        }
        return (t - a) / (b - a);
    }

    private static double triangle(double phase) {
        return 2.0 * Math.abs(2.0 * phase - 1.0) - 1.0;
    }

    private static double saw(double phase, double dt) {
        return (2.0 * phase - 1.0) - polyBlep(phase, dt);
    }

    private static double pulse(double phase, double dt, double duty) {
        double v = phase < duty ? 1.0 : -1.0;
        v += polyBlep(phase, dt);
        double p2 = phase + (1.0 - duty);
        if (p2 >= 1.0) {
            p2 -= 1.0;
        }
        v -= polyBlep(p2, dt);
        return v;
    }

    private static double polyBlep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.0;
        } else if (t > 1.0 - dt) {
            t = (t - 1.0) / dt;
            return t * t + t + t + 1.0;
        }
        return 0.0;
    }

    private static void writeWav(String path, short[] pcm, int sampleRate) throws IOException {
        int dataBytes = pcm.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + dataBytes);
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) 1);
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * 2);
        bb.putShort((short) 2);
        bb.putShort((short) 16);
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(dataBytes);
        for (short s : pcm) {
            bb.putShort(s);
        }
        Files.write(Path.of(path), bb.array());
    }
}
