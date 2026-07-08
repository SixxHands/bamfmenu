# early/core — the headless, editor-reusable game/audio/charting logic (no GL, no bios)
## Intent
`core/` is the half of the early window that is **pure logic**: the rhythm/scoring engine, the DSP auto-charter,
the audio decode/playback, the song registry + JSON descriptors, the left-panel animation framework, terminal
Tetris, and the SPECIAL-mode seam. It exists so every reusable subsystem can run **without a window** — the
chart-bootstrap tool charts songs headless, and the future editor "test my chart" / "Generate" paths reuse the
exact same classes the live boot screen does. The split came from the package restructure (plan Phases 1 →
1.75): the flat god-class was broken into purpose packages, shared utilities relocated to neutral public homes
(e.g. `Art`), and the scoring economy lifted verbatim into a uniform engine so a bespoke SPECIAL can drive the
same combo/health machine. The load-bearing rule: **`core/` never imports `bios/`** — all OpenGL lives in the
presentation half, and `core/` only ever *returns* data (lines to draw, notes, colours) for `bios/` to render.

## Components
This folder has **no direct `.java` files** — it is a pure container. Every class lives in one of the ten
subfolders below, each with its own README. Treat this as the OVERVIEW layer: read Intent here, then the child
that owns the subsystem you need. The 33 classes verified for this doc are inventoried one line per child.

## How it works
The control flow that threads through `core/` at boot:

1. `song/SongRegistry.SONGS` is populated at class-init by `song/SongLoader.discover()`, which enumerates the
   bundled `/songs/<id>/song.json` descriptors (via the dependency-free `json/Json` reader), merging in any
   bespoke code (`panel/` track + `special/` session) from its `BUILTINS` map, and falls back to known ids so
   the list is never empty.
2. When a song plays, `audio/AudioPlayer` decodes its OGG (STB Vorbis), and at decode time runs
   `dsp/SpectralAnalyzer` (which uses `dsp/Fft`) to produce onset envelopes + pitch/phase features, tuned by the
   song's `tuning/DetectorConfig`.
3. The `rhythm/chart/Charter` seam turns those features (or a committed `.chart`) into notes:
   `impl/DspCharter` peak-picks via `dsp/OnsetPicker` + snaps to `dsp/BeatGrid` in `impl/ChartGenerator`;
   `impl/FileCharter` instead reads a committed chart through `rhythm/chart/ChartIO`. Both hand back a
   `List<RhythmEngine.Note>`.
4. `rhythm/RhythmEngine` consumes those notes and the song clock, classifying each press's timing window into a
   `rhythm/score/Grade` and delegating to the uniform `rhythm/score/ScoringEngine`; the host reads results back
   through the read-only `rhythm/score/ScoreView`. The engine also emits chart-driven react signals that
   `panel/` animations turn into the reactive left-panel art, and `rhythm/RhythmClock` recovers true song-time
   for queued input. `tetris/` and `special/` are parallel self-contained game logics on the same render-thread,
   draw-by-returning-lines contract.

## Boundaries
**Depends on:** the Java stdlib and LWJGL only — `audio/AudioPlayer` is the sole importer of `org.lwjgl.*`
(OpenAL/STB Vorbis); `dsp/SpectralAnalyzer` and the rest are pure Java. **No Minecraft classes anywhere.**
**Depended on by:** the `bios/` presentation half (screens/canvas/overlays import `core` to drive and render it)
and the headless `rhythm/chart/ChartBootstrap` tool (the `genCharts` Gradle task).
**Invariants:** `core/` never imports `bios/` (one-directional dependency); all of this logic runs on the single
render thread and is unsynchronized (input arrives via the provider's concurrent queues, never by touching these
classes from another thread); every loader/parser here is total — a bad descriptor/chart/resource degrades to a
default rather than throwing, so boot never breaks.

## Child layers
- `asset/` — `Art`: shared ASCII-art loading + the four lane colours; pure stdlib, no GL.
- `audio/` — `AudioPlayer`: OGG decode + OpenAL playback + decode-time analysis; the only LWJGL importer.
- `dsp/` — `Fft`, `SpectralAnalyzer`, `OnsetPicker`, `BeatGrid`: the signal-processing front end of the charter.
- `json/` — `Json`: a dependency-free recursive-descent JSON reader with a defaulting `Obj` view for descriptors.
- `panel/` — `LeftPanelAnimation`/`LeftPanelTrack` + the `FaceFrameAnimation`/`ScriptedFrameAnimation` types: the pluggable reactive left-panel framework.
- `rhythm/` — `RhythmEngine`, `RhythmClock`, the `score/` economy, and the `chart/` Charter seam (+ `impl/`): the BAMFBEAT game logic and its note-chart sourcing.
- `song/` — `SongDef`, `SongRegistry`, `SongLoader`, `Difficulty`: data-driven song discovery + per-song definitions.
- `special/` — `SpecialSession` (iface) + `PlaceholderSpecial`: the optional bespoke-gameplay seam.
- `tetris/` — `TetrisGame`: self-contained terminal Tetris logic.
- `tuning/` — `DetectorConfig`: the per-song auto-charter tuning record.
