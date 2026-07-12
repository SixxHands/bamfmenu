# early/core, headless editor-reusable game/audio/charting logic
## Features
`core/` is the half of the early window that is pure logic: the rhythm game and its song/chart machinery,
audio playback, terminal Tetris, the data-driven secrets, the shared asset loaders, and the creation/analysis
tools. Everything here can run without a window, so the chart bootstrap charts songs headless and the editor's
test/import paths reuse the exact classes the live boot screen does. The layout is the secrets model: `core/`
is seven category folders, each root holds its definitions + machinery, and data-driven members live as
subfolders. `AudioPlayer` is pure playback, the analysis half lives in `tools/audio/SongAnalysis`, so plain
playback carries no detector machinery. The load-bearing rule: `core/` never imports `bios/`. All OpenGL lives
in the presentation half, `core/` only ever returns data (lines to draw, notes, colors, pixels) for `bios/` to
render.

## Components
This folder has no direct `.java` files, it is a pure container. Every class lives in one of the seven
categories below, each with its own README. Treat this as the overview layer: read Features here, then the
child that owns the subsystem you need.

## How it works
The control flow that threads through `core/` at boot:

1. `rhythm/SongRegistry.SONGS` fills at class-init via `rhythm/SongLoader.discover()`: enumerate the bundled
   `/songs/<id>/song.json` descriptors, parse each with `asset/Json`, merge any bespoke code from `BUILTINS`,
   and fall back to known ids so the list is never empty.
2. When a song plays, `audio/AudioPlayer.play(resource)` decodes its OGG and plays it through its own OpenAL
   device. `positionSeconds()` is the master clock the engine syncs notes to, `waveform()` is the amplitude
   profile the header/editor draw.
3. Charting is the tools' job. `tools/audio/SongAnalysis.analyze(resource, cfg)` decodes the OGG separately
   and runs the full detector chain into an unchangeable feature bank. The `rhythm/chart/Charter` seam turns a
   `ChartRequest` into notes: `impl/DspCharter` generates from the feature bank, `impl/FileCharter` reads a
   committed chart through `rhythm/chart/ChartIO`. Both hand back a `List<Note>`, the category's shared note
   definition.
4. `rhythm/engine/RhythmEngine` consumes those notes and the song clock, grading each press through the
   uniform `rhythm/score/ScoringEngine`. The host reads results back through the read-only
   `rhythm/score/ScoreView`. The engine also emits the react signals that `rhythm/panel` animations turn into
   the left-panel art, and `rhythm/engine/RhythmClock` recovers true song-time for queued input. `tetris/` and
   the `secret/` games are parallel self-contained game logics on the same render-thread,
   draw-by-returning-lines rule.

## Dependencies
- **Depends on:** the Java stdlib and LWJGL only. `audio/AudioPlayer` (OpenAL + STB Vorbis) and
  `tools/audio/SongAnalysis` (STB Vorbis decode, no audio device) are the only importers of `org.lwjgl.*`,
  everything else is pure Java. No Minecraft classes anywhere.
- **Depended on by:** the `bios/` presentation half and the headless Gradle tools (`tools/gen/ChartBootstrap`
  = `genCharts`, `tools/convert/OsuManiaImport` = `importOsu`, the `tools/probe/*` mains).
- `core/` never imports `bios/`.
- All of this runs on the single render thread, unsynchronized. Input arrives via the provider's concurrent
  queues, never by touching these classes from another thread.
- Every loader/parser here is total: a bad descriptor/chart/resource degrades to a default rather than
  throwing, so boot never breaks.

## Child layers
- `asset/` is the shared asset loading: ASCII art + lane colors, the glitch primitive, the JSON reader,
  picture/video decode.
- `audio/` is pure OGG decode + OpenAL playback. Analysis lives in `tools/audio`.
- `rhythm/` is the BAMF! category: definitions + discovery at the root, songs as the data-driven members,
  with `engine/`, `score/`, `chart/`, `panel/`, `special/`, and `editor/` underneath.
- `secret/` is the data-driven secrets: machinery at the root + one folder per secret; the hidden games live
  here.
- `tetris/` is `TetrisGame`, self-contained terminal Tetris logic.
- `tools/` is the tools container (creation/analysis, not gameplay): `audio/`, `convert/` (osu import),
  `gen/` (`ChartBootstrap` + the song spike), `probe/` (the five dev probes).
- `util/` is the shared utility functions: `ResourceScan` (the classpath enumeration both discoveries ride)
  and `Figlet` (the FIGlet text renderer). Grows slowly as util stuff gets moved in.
