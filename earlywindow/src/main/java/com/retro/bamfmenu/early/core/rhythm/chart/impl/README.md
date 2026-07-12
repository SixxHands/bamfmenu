# early/core/rhythm/chart/impl, the `Charter` builders
## Features
The implementation side of the `Charter` seam: the parent `chart/` declares `Charter` and `ChartRequest`,
this folder keeps the builders. The seam exists so no consumer ever inlines the generate chain: pass a
request, get `List<Note>` back, the chart source swaps in one place. Gameplay never DSP-charts
at play, so the DSP here is a production tool: `DspCharter` is the exact former runtime cascade, today run
only by the dev `genCharts` bootstrap (`tools/gen/ChartBootstrap`) to commit the `.chart` files gameplay
loads. `ChartGenerator` is the pure DSP-to-notes auto-charter it drives, `FileCharter` is the classpath
committed-chart impl, retained but currently uncalled.

## Components
- **`DspCharter`** is the DSP chart source. `build(ChartRequest)` reads the feature bank off `req.analysis()`
  and falls down the cascade, each stage tried only if the prior returned empty.
  - cascade: `ChartGenerator.generateMelodic` -> `generateV2` -> `generate` -> `RhythmEngine.demoChart()`.
  - null `analysis` (`SongAnalysis.analyze` failed): short-circuit straight to `demoChart()`, honoring the
    never-empty-highway rule. `ChartBootstrap` skips such a song instead.
  - callers: `ChartBootstrap` today; a future editor "Generate" would reuse it.
- **`FileCharter`** is the classpath committed-chart charter. `build` reads the resource at `req.chartPath()`
  and parses via `ChartIO.read`; any failure (null/blank path, missing file, garbled) returns `List.of()` =
  "uncharted".
  - statics: `chartPath(songId, Difficulty)`, `hasChart` (existence memoized in `EXISTS`), `approachMs`
    (reads a chart's `approach` header without a full build).
  - status: no live callers. The head loads chart text via `ctx.loadChartText` (disk first, so editor saves
    and USER/osu songs win), and the jukebox difficulty list went data-driven off `SongDef.difficulties()`,
    which superseded `hasChart`. Kept as the classpath-only `Charter` impl.
- **`ChartGenerator`** is the pure auto-charter: arrays in, notes out, no GL or audio handles. All variants emit
  taps only (`hold = 0`), keeps come from hand-edited charts.
  - `generateMelodic(...)`: piano-priority, onsets AND lanes from the melodic band via `OnsetPicker`,
    light anti-jack.
  - its vetoes: `cdFloor` (complex-domain confidence) kills drone/reverb/steady-tone false onsets; `minF0Hz`
    gates bit-crushed songs, when active lane pitch comes from the HPS `melF0` and the percussion fill is
    skipped as pure hiss.
  - its refractory: same pitch within 240 ms (~1.5 quarter-tones) drops, so a reverberating note is not
    fragmented into repeats.
  - its grid: `BeatGrid.estimate` 16th-grid snap + one-note-per-slot dedupe when the beat is confident, NO
    off-grid rejection so expressive off-grid notes survive; `addPercussionFill` only in melodic rests
    ≥900 ms, grid-snapped too.
  - its lanes: adaptive p8..p92 log-pitch -> lane map so notes trace the melody across all lanes.
  - `generateV2(...)`: whole-mix SuperFlux ODF + `OnsetPicker`, lanes via `laneFromCentroid`
    (log map ~120-5000 Hz).
  - `generate(...)`: legacy RMS-flux local-peak pick, ZCR-bucket lanes.
  - `sensitivity` (0..1): the shared difficulty knob, higher = lower delta + smaller min gap = denser.

## How it works
A caller builds a request (today `ChartBootstrap` does `ChartRequest.fromAnalysis(analysis, det,
RhythmEngine.LANES, song.bpm())` after a headless `SongAnalysis.analyze(song.ogg(), det.withLeadInMs(0))`)
and calls `Charter.build(req)`. `DspCharter` feeds the feature bank + the `DetectorConfig` knobs
(`sensitivity`/`leadInMs`/`cdFloor`/`minF0Hz`) through the cascade until something non-empty (or the demo
chart) comes out. `FileCharter` ignores the analysis and parses the committed resource at `req.chartPath()`.
Either way `build` returns `List<Note>`, the type the engine plays, so consumers never change when the source
does. Gameplay does not pass through here: the head loads committed text via `ctx.loadChartText` +
`ChartIO.read`, and editor Test Play hands its in-memory notes straight to the engine.

## Dependencies
- Depends on sibling `core/` packages only: `chart` (`Charter`/`ChartRequest`/`Chart`/`ChartIO`), the rhythm
  root (`Note`, `Difficulty`), `engine` (`demoChart()`, `LANES`), `tools.audio` (`SongAnalysis`,
  `DetectorConfig`), and `tools.audio.dsp` (`BeatGrid`, `OnsetPicker`).
- Consumed by `tools/gen/ChartBootstrap`, the only live caller of the charters today.
- This is `core/`: it never imports `bios/` and touches no GL/OpenAL/Minecraft.
- `ChartGenerator` is a pure function keeping no state between calls, `FileCharter.EXISTS`
  is a `ConcurrentHashMap` so existence polling is thread-safe, and `DspCharter.build` tolerates a null
  `req.analysis()` (returns the demo chart).
