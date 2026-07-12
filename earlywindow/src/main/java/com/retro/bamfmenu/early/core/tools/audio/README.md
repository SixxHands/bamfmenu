# early/core/tools/audio, song analysis
## Features
This layer gives each song its own auto-charter settings (`DetectorConfig`) and owns the analysis run itself
(`SongAnalysis`), the half detached from `AudioPlayer`. `AudioPlayer` is now a pure playback function, prior it was 
one class made to do two jobs and dragged `DetectorConfig` into every playback call site.
`DetectorConfig` replaces a scatter of literals (the provider's sensitivity, the chart lead-in, the analyzer
band edges, the engine tail) collapsed into one charter.
`defaults()` should reproduce the OLD global values, so any song that overrides nothing charts identically
to pre-refactor behavior. `song.json`'s optional `detector` block is just 8 fields, each independently
defaulting. The same object is the editor's "Generate" options.

## Components
- **`DetectorConfig`** is a Java `record` carrying the 8 tuning fields and nothing else. Pure data,
  immutable, freely shared. Care for a slice?
  - `sensitivity`: onset density / threshold.
  - `leadInMs`: the chart lead-in gate, onsets before it are dropped.
  - `cdFloor`: complex-domain phase-confidence veto floor, drone/reverb suppression. 0 disables.
  - `melLoHz` / `melHiHz` / `percHiHz`: melodic + percussion band edges.
  - `tailMs`: grace past chart end before the song counts as finished.
  - `minF0Hz`: HPS fundamental-pitch gate. Drops onsets below it, re-routes lane pitch to the HPS
    fundamental, and disables percussion fill. 0 = off.
  - `defaults()`: the historical globals. Copy-with helpers (`withSensitivity`, `withMelodicBand`,
    `withLeadInMs`, `withMinF0Hz`) each return a new record with one field changed.
  - BPM is deliberately not here, it lives on the `SongDef`.
- **`SongAnalysis`** is the detached analyzer, and has one entry point. `static analyze(String resource,
  DetectorConfig cfg)` -> an unchangeable feature bank, or `null` on any decode/analysis failure (callers
  degrade).
  - reads the OGG via `AudioPlayer.readResource` (classpath or `"file:"` disk songs), so analysis sees the
    same thing playback does.
  - runs the same chain from the old `AudioPlayer`, and in the same order. It does the legacy onset/ZCR
    pass first, then `SpectralAnalyzer.analyze(..., cfg)`.
  - legacy getters exist here `onsets()`/`zcr()`/`hopMs()`; SuperFlux `superflux()`, `centroid()`,
    `superfluxFrameMs()`, should be exact drift-free frame spacing. The charter times notes with these, not the
    integer `hopMs`, then melodic/percussion via `melodicOdf()`, `melodicPitch()`, `melodicF0()`,
    `percussionOdf()`, `cdConf()`.
  - completely headless

## How it works
A song produces one `DetectorConfig` (`song.json`'s `detector` block falling back per-field to `defaults()`).
`SongAnalysis.analyze(resource, cfg)` turns song + config into the feature bank.
`ChartRequest.fromAnalysis(analysis, det, lanes, bpm)` carries both into `DspCharter`/`ChartGenerator`, which
use `sensitivity`, `cdFloor`, `leadInMs`, `minF0Hz` for the onset/veto/lead-in stage, while `tailMs`
governs when the engine treats the song as finished. Rhythm gameplay plays committed charts and never
analyzes. The `with*` helpers exist so a song can clone `defaults()` and dial a single axis.

## Dependencies
- `DetectorConfig` depends on nothing but the JDK (no imports at all). `SongAnalysis` depends on
  `core.audio.AudioPlayer` (the shared `readResource` only), `dsp.SpectralAnalyzer`, and LWJGL STB/memory.
  No GL, no Minecraft, no `bios/`.
- Depended on by `core/rhythm` (`SongDef`/`SongLoader` hold/build the config; `chart/ChartRequest` +
  `chart/impl` consume config + analysis; `RhythmEngine` reads `tailMs`), by `tools/gen/ChartBootstrap`, and
  by `bios/modes/cutscene/CutsceneScreen` (stab-sync).
- `core/` never imports `bios/`. Both classes are unchangeable, thread-safe to share across the main and render
  threads without coordination.
- `defaults()` must keep reproducing the historical globals exactly, or unspecified songs would silently
  re-chart.
- The analyze chain must stay verbatim (legacy pass, then `SpectralAnalyzer`) or committed charts stop being
  reproducible.

## Child layers
- **`dsp/`**: `Fft`, `SpectralAnalyzer`, `OnsetPicker`, `BeatGrid`, signal-processing internals
  `SongAnalysis` and the charter run.
