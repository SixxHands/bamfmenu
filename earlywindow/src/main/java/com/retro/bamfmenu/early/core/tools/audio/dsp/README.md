# early/core/tools/audio/dsp, the auto-charter's signal processing
## Features
This layer turns decoded PCM into the musical features the auto-charter needs to place rhythm-game notes: an
onset-strength envelope, a per-frame pitch estimate, and a tempo/beat grid. It exists because the early
window is pure Java + LWJGL with no DSP library, so the FFT and all analysis are hand-rolled here. This is a
deliberate evolution away from an older "spam + misses" charter. Single-number RMS flux and a global
threshold were replaced with SuperFlux log-band flux, a complex-domain phase confidence, HPS pitch with
octave correction, and an adaptive 3-condition peak-picker. Each fixes a specific failure: soft melodic
onsets getting buried, vibrato/drone phantom onsets, bit-crush aliasing picking the wrong pitch, and a global
threshold that can't fit a song's dynamics.

## Components
- **`Fft`** is a minimal radix-2 iterative Cooley-Tukey FFT, `N` must be a power of two. Precomputes
  bit-reversal indices and twiddle factors once in the constructor. `transform(float[] re, float[] im)` runs
  in place on parallel real/imag arrays. A pure helper, only `SpectralAnalyzer` calls it.
- **`SpectralAnalyzer`** is the heart of the layer. `analyze(pcm, channels, frames, sampleRate, cfg)` runs
  the full STFT pipeline: mono downmix, centered Hann-windowed frames (`N=2048`, `HOP=441`, ~100 fps),
  log-spaced quarter-tone bands, log compression, then SuperFlux flux against a 3-bin frequency max-filtered
  previous frame. Runs once per song from `SongAnalysis.analyze`, before the decoded PCM is freed. Returns
  the `Result` record:
  - `odf`: whole-mix flux. `melodicOdf` / `percussionOdf`: the band fluxes.
  - `melodicPitch`: the dominant-melodic-band pitch. `melodicF0`: the HPS+octave-corrected fundamental.
  - `cdConf`: the complex-domain phase confidence.
  - `centroid`: brightness. `hopMs`: frame spacing.
- **`BeatGrid`** estimates the song's tempo/beat grid from an onset envelope so the charter can snap notes
  and de-noise. `estimate(odf, frameMs[, knownBpm])` builds the grid, autocorrelation with a log-Gaussian
  120 BPM prior + a half/double comb re-check; `knownBpm > 0` skips tempo detection and only finds phase.
  `confident()` gates use (confidence >= 2.5). `nearestFrame(frame)` quantizes an onset to the nearest
  16th-note slot, quantize-only, it does not reject off-grid onsets. Pure logic, no GL/audio.
- **`OnsetPicker`** is the adaptive 3-condition peak-picker (Dixon / librosa-style). `pick(...)` returns
  onset frame indices for frames that are (1) a local max over `[i-preMax, i+postMax]`, (2) above a moving
  local-mean baseline by `delta` (and above `absFloor`), and (3) at least `waitFrames` after the previous
  accepted onset. `delta` is the difficulty/density knob. Pure logic.

## How it works
`SpectralAnalyzer.analyze` is the entry point and the only consumer of `Fft`: one STFT pass accumulates the
band magnitudes and produces every array in `Result`. The melodic vs percussion band split is driven per-song
by the passed-in `DetectorConfig`, the layer's one external dependency. The auto-charter then feeds a chosen
envelope into `OnsetPicker.pick` for onset frames and `BeatGrid.estimate` for tempo/phase, quantizes with
`nearestFrame`, and assigns lanes off `melodicPitch`/`melodicF0`. This layer only produces the features, it
does not build the chart.

## Dependencies
- Depends on the JDK and the parent `tools/audio` `DetectorConfig` (the per-song band-edge tuning carrier).
  It imports nothing from `bios/`, Minecraft, or GL.
- `SpectralAnalyzer.analyze` is called from `SongAnalysis.analyze`. The charter
  (`ChartGenerator`/`DspCharter`) is the main consumer of its outputs plus `OnsetPicker` and `BeatGrid`.
- All work is synchronous, on whatever thread invokes it. The classes keep no state between calls.
