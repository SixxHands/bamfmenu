# early/core/dsp — the auto-charter's signal-processing primitives (audio → onset/beat features)

## Intent
This layer turns decoded PCM into the musical features the auto-charter needs to place rhythm-game notes:
an onset-strength envelope, a per-frame pitch estimate, and a tempo/beat grid. It exists because the early
window is pure Java + LWJGL with no DSP library, so the FFT and all analysis are hand-rolled here. The code's
own comments describe a deliberate evolution away from an older "spam + misses" charter: single-number RMS
flux and a global threshold were replaced with a SuperFlux log-band flux, a complex-domain phase confidence,
HPS pitch with octave correction, and an adaptive 3-condition peak-picker — each addressing a specific failure
(soft melodic onsets buried, vibrato/drone phantom onsets, bit-crush aliasing picking the wrong pitch, and a
global threshold that cannot fit a song's dynamics). `BeatGrid` carries a comment marking it as the addition. Per the plan doc these classes live under `early.dsp` (the charter / signal-processing package); the
folder is now nested as `early.core.dsp` so it sits in the headless `core/` half that never imports `bios/`.

## Components
- `Fft` — package-private, final. A minimal radix-2 iterative Cooley-Tukey FFT (`N` must be a power of two).
  Precomputes bit-reversal indices and twiddle factors once in the constructor; `transform(float[] re, float[] im)`
  runs in place on parallel real/imag arrays. `size()` returns `n`. No threading concerns of its own — it is a
  pure helper called by `SpectralAnalyzer`. Constructor and methods are package-private (only `SpectralAnalyzer`
  uses it).
- `SpectralAnalyzer` — public, final, all-static utility (private ctor). The heart of the layer. `analyze(ShortBuffer pcm,
  int channels, int frames, int sampleRate, DetectorConfig cfg)` does the full STFT pipeline (mono-downmix →
  centered Hann-windowed frames, `N=2048`, `HOP=441` ≈ 100 fps → log-spaced quarter-tone bands → log
  compression → SuperFlux flux against a 3-bin frequency max-filtered previous frame) and returns the
  `Result` record. `Result(float[] odf, float[] centroid, float[] melodicOdf, float[] melodicPitch, float[] melodicF0,
  float[] percussionOdf, float[] cdConf, int hopMs)` carries: whole-mix flux (`odf`), the melodic/percussion
  band fluxes, the dominant-melodic-band pitch (`melodicPitch`), the HPS+octave-corrected fundamental
  (`melodicF0`), the complex-domain phase confidence (`cdConf`), brightness `centroid`, and frame spacing
  `hopMs`. Public constant `HOP`; package-private `N`. Runs once at decode time (per the comment, inside
  `AudioPlayer.play` before the PCM is freed) — no GL, no Minecraft.
- `BeatGrid` — public, final. Estimates the song's tempo/beat grid from an onset envelope so the charter can
  snap notes and de-noise. `estimate(float[] odf, double frameMs)` and `estimate(float[] odf, double frameMs,
  double knownBpm)` build the grid (autocorrelation with a log-Gaussian 120 BPM prior + a half/double comb
  re-check; `knownBpm > 0` skips tempo detection and only finds phase). Fields `periodFrames`, `phaseFrames`,
  `bpm`, `confidence` (package-private). `confident()` gates use (confidence ≥ 2.5); `nearestFrame(int frame)`
  quantizes an onset to the nearest 16th-note slot (quantize-only — it does not reject off-grid onsets). Pure
  logic, no GL/audio.
- `OnsetPicker` — public, final, all-static (private ctor). The adaptive 3-condition peak-picker
  (Dixon / librosa-style). `pick(float[] odf, float delta, float absFloor, int preMax, int postMax, int preAvg,
  int postAvg, int waitFrames)` returns onset *frame* indices for frames that are (1) a local max over
  `[i-preMax, i+postMax]`, (2) above a moving local-mean baseline by `delta` (and above `absFloor`), and (3)
  at least `waitFrames` after the previous accepted onset. `delta` is the difficulty/density knob. Pure logic.

## How it works
`SpectralAnalyzer.analyze` is the entry point and the only consumer of `Fft`: it builds one `Fft(N)`, runs a
centered Hann-windowed STFT over the PCM, accumulates log-compressed quarter-tone band magnitudes, and from
those produces every array in `Result` (whole-mix/melodic/percussion SuperFlux envelopes, argmax melodic pitch,
HPS+octave-corrected fundamental, complex-domain phase confidence, centroid). The band split into melodic vs
percussion groups is driven per-song by the passed-in `DetectorConfig` (the layer's one external dependency).
Downstream (outside this folder), the auto-charter feeds a chosen envelope from `Result` into `OnsetPicker.pick`
to get onset frames and into `BeatGrid.estimate` to get tempo/phase; it then uses `BeatGrid.nearestFrame` to
quantize onsets, `BeatGrid.confident()` to decide whether to trust the grid at all, and `melodicPitch`/`melodicF0`
to assign lanes. This layer only *produces* the features; it does not build the chart itself.

## Boundaries
Depends on the JDK (`java.nio.ShortBuffer`, `java.util`, `java.lang.Math`) and on
`com.retro.bamfmenu.early.core.tuning.DetectorConfig` (the per-song band-edge tuning carrier). It imports
nothing from `bios/` and nothing from Minecraft or GL — consistent with the invariant that `core/` is headless,
editor-reusable, and never imports `bios/`. `Fft` is internal to this package (package-private). Per its comment,
`SpectralAnalyzer.analyze` is called from the audio layer (`AudioPlayer.play`) at decode time; the charter /
chart-generation code (`ChartGenerator` / `DspCharter`, per the plan doc) is the main consumer of its outputs
plus `OnsetPicker` and `BeatGrid`. All work here is synchronous, on whatever thread invokes it; the classes hold
no shared mutable state across calls.
