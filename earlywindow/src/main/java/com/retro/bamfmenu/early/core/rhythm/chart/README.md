# early/core/rhythm/chart, the chart seam
## Features
The one seam between where a chart comes from and everything that consumes charts, plus the in-memory model
and text format for a committed chart. Gameplay loads committed charts, no DSP at play:
`RhythmScreen.onEnter` fetches chart text via `ctx.loadChartText(songId, token)` (on-disk `BAMFOS/songs`
first, then bundled `/songs/<id>/<token>.chart`) and parses it with `ChartIO.read`, so editor saves and
user/osu-imported songs load off-disk too. `Charter` stays the seam for generated charts; its one live caller
is the dev `genCharts` bootstrap (`tools/gen/ChartBootstrap` -> `impl/DspCharter`), which commits the files
gameplay then loads. `ChartRequest` was lifted here from `impl/`, fixing the inversion where the
interface's own parameter type lived below its implementations; it carries a `SongAnalysis` feature bank,
not the old fused `AudioPlayer`. `ChartBootstrap` itself was evicted to `tools/gen/`.

## Components
- **`Charter`** is the one generate seam: `List<Note> build(ChartRequest req)`, never null, an empty
  list means "no chart" and the caller decides the fallback. Implementations live in `impl/`.
- **`ChartRequest`** is the unchangeable inputs handed to a `Charter`. Forward-compat by design: a builder ignores
  fields it does not need (`FileCharter` ignores the analysis, `DspCharter` ignores `chartPath`).
  - `analysis`: the song's `SongAnalysis` feature bank, null for a file-backed build.
  - `det`: the difficulty's tuned `DetectorConfig`.
  - `bpm`: grid tempo hint, 0 = auto.
  - `chartPath`: committed-chart resource path, only `FileCharter` reads it.
- **`Chart`** is a record, one committed difficulty's chart, the in-memory form of a `.chart` file.
  `VERSION` is bumped only on incompatible format changes, readers tolerate unknown/missing fields.
  - `difficulty`: also the filename token.
  - `bpm`: informational tempo hint, 0 = auto.
  - `preRollMs`: silent scroll-in lead-in, 0 today.
  - `approachMs`: per-chart note fall speed = ms of look-ahead, 0 = engine default.
  - `notes`: sorted ascending by `timeMs` on load.
- **`ChartIO`** is read/write for the hand-rolled, dependency-free `.chart` text format. `read` parses
  tolerantly: unknown header keys ignored, missing ones defaulted, malformed note rows skipped, never throws,
  worst case = empty notes.
  - `write(Chart)`: header `key value` lines + `timeMs lane holdMs` note rows in time order; the `approach`
    header is omitted when 0 so untouched charts stay clean.
  - format leniency: the `notes N` header is informational, the rows are trusted; `holdMs` is optional so a
    hand-author can write just `timeMs lane`.

## How it works
Three flows through this layer. Play: `RhythmScreen` resolves the `SongDifficulty`, loads text via
`ctx.loadChartText`, parses with `ChartIO.read`, hands `notes()` to the engine and applies `approachMs()`
when > 0; an empty result = "Uncharted" and the screen bails rather than start an empty highway (editor Test
Play skips files entirely, the chart arrives via `ctx.consumeTestChart()`). Author: `ChartBootstrap.main`
walks `SongRegistry.SONGS`, runs `SongAnalysis.analyze` headless, calls `new DspCharter().build(...)`, and
writes through `ChartIO.write` to `<out>/<id>/normal.chart`. The editor (`EditChart`) and the osu importer
write charts through `ChartIO` too, and `SongLoader` writes an empty chart when scaffolding a user song.
Generate: any `Charter` impl consumes a `ChartRequest` and returns `List<Note>`, the same type the engine
plays, so chart sources swap without consumers changing.

## Dependencies
- Depends on the rhythm root's `Note` and, via `ChartRequest`, `tools/audio` (`SongAnalysis`,
  `DetectorConfig`); the `impl/` children add `RhythmEngine` (`demoChart`), `tools.audio.dsp`, and
  `Difficulty`, all `early.core.*`, headless and editor-reusable.
- Depended on by the rhythm and editor screens in `bios/`, `rhythm/editor`'s `EditChart`, `SongLoader` (the
  USER-song scaffold chart), `ChartBootstrap`, and `rhythm/registry/OsuManiaImport`.
- Core never imports `bios/`, nothing here touches GL/OpenAL/Minecraft. `ChartIO.read` never throws,
  `Charter.build` never returns null, `Chart` notes sort ascending by `timeMs` after `read`/`write`.
- Gameplay never analyzes audio: `SongAnalysis` is produced only by dev tools and the Recovery cutscene's
  stab-sync, never in the play path.

## Child layers
- `impl/`, the `Charter` implementations: `DspCharter` (the DSP cascade off `req.analysis()`), `FileCharter`
  (classpath committed-chart loader, currently no live callers), `ChartGenerator` (the peak-picking note
  generators).
