# early/core/rhythm/editor headless chart editor core
## Features
The editor's three pure-logic pieces with no UI, the chart being authored (`EditChart`), the musical snapping
grid (`TimingGrid`), and the time <-> screen viewport math (`TimeMap`). It is split from the editor's `bios/`
host so the model and math are reusable by a future standalone tool and testable without GL. The load-bearing
guarantee lives here: an `EditChart` stores the SAME `Note` the game plays (`rhythm/model`'s `Note`) and
round-trips through `ChartIO`, so author -> `toText()` -> `ChartIO.read` -> identical play. Everything here
is pure stdlib + the core rhythm types: no GL, no Minecraft, no `bios/`.

## Components
- **`EditChart`** is the in-memory chart for one song-difficulty. Holds `songId`, `difficultyToken` (also the
  filename), editable `bpm`, pass-through `preRollMs` and `approachMs`, a changeable `List<Note> notes`, and
  a private `dirty` flag.
  - `fromText(songId, token, text, bpmHint)`: builds from a `.chart` blob via `ChartIO.read`. Empty chart if
    the text is null/blank, `bpmHint` seeds tempo when the chart omits it.
  - `toggle(lane, timeMs, tolMs)`: removes an existing note within tolerance, else adds a fresh tap.
  - `move(n, newLane, newTimeMs)`: remove + re-add (Note fields are `final`), clamped to lanes 0-3, carries
    `holdMs`.
  - `removeNearest(timeMs, tolMs)`: deletes the closest note in any lane. `remove(n)`: by identity.
  - queries: `indexAt(lane, timeMs, tolMs)`, `count()`, `lastTimeMs()` (the chart extent). `sort()` keeps
    notes ascending by `timeMs` then `lane`.
  - dirty tracking: every edit op calls `markDirty()`, the host's save-guard reads `dirty()`. `toText()`
    sorts then `ChartIO.write`s a fresh `Chart`.
- **`TimingGrid`** turns BPM + offset into beat/measure/subdivision times + snapping. 4/4 is assumed in v1.
  - fields: editable `bpm`, `offsetMs` (where beat 0 lands), `snapOn`, `divisorIndex`.
  - `DIVISORS = {1,2,3,4,6,8,12,16}`: the selectable subdivisions per beat. `divisorLabel()` formats
    osu-style `"1/N"`, `cycleDivisor(dir)` steps through them.
  - `snap(ms)`: rounds to the nearest grid line off `offsetMs`. Pass-through when snapping is off, clamped
    to >= 0.
  - `step(ms, dir)`: the snapped slot one step later or earlier, for keyboard nudges.
  - `subdivIndex(ms)` + `isBeat`/`isMeasure`: classify a grid line for ruler styling.
  - a non-positive bpm defaults to 120.
- **`TimeMap`** is the editor viewport: song time (ms) <-> logical screen x with pan + zoom. Time increases
  to the right.
  - `pxPerMs`: the zoom, clamped 0.01..2.0. `viewStartMs`: leftmost visible time, may be negative for a
    lead-in pad.
  - `originX`: the logical x where `viewStartMs` sits, set per-frame by the renderer.
  - `xForMs(ms)` / `msForX(x)`: convert. `zoomAt(factor, anchorX)`: scales while pinning the time under the
    anchor. `panPixels(px)`: scrolls, positive = later.
  - the lane<->y mapping intentionally lives in the renderer (band height depends on the live framebuffer),
    not here.

## How it works
The `bios/` editor host owns one `EditChart`, one `TimingGrid`, and one `TimeMap`. On open it calls
`EditChart.fromText(...)`. The renderer sets `TimeMap.originX` per-frame and uses `xForMs`/`msForX` to place
notes and translate clicks, with `zoomAt`/`panPixels` driving scroll. A placement click's raw time runs
through `TimingGrid.snap(...)` before it reaches `EditChart.toggle`/`move`, so notes land on the grid.
`TimingGrid` also tells the ruler which lines are beats and measures. Every edit sets the dirty flag for the
host's save-guard. On save the host calls `toText()`, which sorts and hands a freshly-built `Chart` to
`ChartIO.write`. The notes never leave the shared `Note` type, so the saved file plays back exactly what was
authored.

## Dependencies
- Depends on the rhythm `model/` (`Note`) and the sibling `chart/` (`Chart`, `ChartIO`), plus pure stdlib.
  `TimingGrid` and `TimeMap` depend on nothing but stdlib.
- `EditChart` still carries an unused `import` of `engine.RhythmEngine` left over from the Note extraction;
  nothing references it.
- This layer imports nothing from `bios/`. All GL/UI, the lane<->y mapping, input handling, and rendering
  live in the `bios/` editor, which depends on this layer and never the reverse.
- Scope is intentionally v1-small: taps + moves, `holdMs` is carried but not yet authored here. Undo history
  and hold authoring come later.
