# early/core/editor — the chart editor's headless core: editable chart model + timing-grid / view-map math

## Intent
This layer holds the editor's three pure-logic pieces with NO UI: the mutable chart being authored (`EditChart`),
the musical snapping grid (`TimingGrid`), and the time↔screen viewport math (`TimeMap`). It is deliberately split
from the editor's `bios/` host so the model and math are reusable by a future standalone tool and unit-testable
without GL. The cardinal correctness goal lives here: an `EditChart` stores the *same* `RhythmEngine.Note` type the
game plays and round-trips through `ChartIO`, so **author → `toText()` → `ChartIO.read` → identical play** is
guaranteed. Everything here is pure stdlib + the core `rhythm`/`rhythm.chart` types — no GL, no Minecraft, no `bios/`.

## Components
- **`EditChart`** — the in-memory chart for one song-difficulty. Holds `songId`, `difficultyToken` (e.g. `"normal"`,
  also the filename), editable `bpm` (drives the grid + saved into the `Chart`), pass-through `preRollMs`, `approachMs`
  (note fall-speed look-ahead; 0 = default), a mutable `List<RhythmEngine.Note> notes`, and a private `dirty` flag.
  `fromText(songId, token, text, bpmHint)` builds from a `.chart` blob via `ChartIO.read` (empty chart if text is
  null/blank; `bpmHint` seeds tempo when the chart omits it) and `sort()`s. Edit ops: `toggle(lane, timeMs, tolMs)`
  removes an existing note within tolerance else adds a fresh tap (returns the added note, or null on removal);
  `move(n, newLane, newTimeMs)` is remove+re-add (Note fields are `final`) clamped to lanes 0–3, carrying `holdMs`;
  `removeNearest(timeMs, tolMs)` deletes the closest note in ANY lane; `remove(n)` by identity. Queries:
  `indexAt(lane, timeMs, tolMs)` (nearest in one lane, −1 if none), `count()`, `lastTimeMs()` (max `endMs()`, the
  chart extent). `sort()` keeps notes ascending by `timeMs` then `lane`. `dirty()`/`markDirty()`/`markClean()` track
  unsaved state; every mutator calls `markDirty()`. `toText()` sorts then `ChartIO.write`s a fresh `Chart`.
- **`TimingGrid`** — BPM + offset → beat/measure/subdivision times + snapping. Fields: editable `bpm`, `offsetMs`
  (where beat 0 lands), `snapOn`, `divisorIndex` (default 3). 4/4 is assumed in v1 (`BEATS_PER_MEASURE = 4`).
  `DIVISORS = {1,2,3,4,6,8,12,16}` are the selectable subdivisions-per-beat; `divisor()` reads the current one
  (clamped), `divisorLabel()` formats osu-style `"1/N"`, `cycleDivisor(dir)` steps the index. Math: `msPerBeat()`
  = `60000/bpm`, `snapStepMs()` = `msPerBeat()/divisor()`. `snap(ms)` rounds to the nearest grid line off `offsetMs`
  (pass-through when `snapOn` is false), clamped ≥0; `step(ms, dir)` is the snapped slot one step later/earlier;
  `subdivIndex(ms)` is the rounded subdivision index, and `isBeat`/`isMeasure(subdiv)` classify a line for ruler
  styling (via `Math.floorMod`). Ctor defaults a non-positive bpm to 120.
- **`TimeMap`** — the editor viewport: song time (ms) ↔ logical screen x with pan + zoom. Time increases right.
  Fields: `pxPerMs` (zoom, clamped to `MIN_PX_PER_MS`=0.01 … `MAX_PX_PER_MS`=2.0), `viewStartMs` (leftmost visible
  time, MAY be negative for a lead-in pad), `originX` (logical x where `viewStartMs` sits, set per-frame by the
  renderer). `xForMs(ms)` / `msForX(x)` convert; `zoomAt(factor, anchorX)` scales while pinning the time under
  `anchorX`; `panPixels(px)` scrolls (positive = later). The lane↔y mapping intentionally lives in the renderer
  (band height depends on the live framebuffer), not here.

## How it works
The `bios/` editor host owns one `EditChart`, one `TimingGrid`, and one `TimeMap`. On open it calls
`EditChart.fromText(...)`; the renderer sets `TimeMap.originX` per-frame and uses `xForMs`/`msForX` to place notes
and translate clicks, with `zoomAt`/`panPixels` driving scroll. A placement click's raw time is run through
`TimingGrid.snap(...)` (and `step` for keyboard nudges) before it reaches `EditChart.toggle`/`move`, so notes land on
the grid; `TimingGrid` also tells the ruler which lines are beats/measures via `subdivIndex` + `isBeat`/`isMeasure`.
Every edit sets the dirty flag for the host's save-guard. On save the host calls `toText()`, which sorts and hands a
freshly-built `Chart` to `ChartIO.write`. Because the notes never leave the `RhythmEngine.Note` type, the saved file
plays back byte-for-meaning identically to what was authored.

## Boundaries
Depends on `early.core.rhythm` (`RhythmEngine.Note`) and `early.core.rhythm.chart` (`Chart`, `ChartIO`) — sibling
`core` packages — plus pure stdlib. `TimingGrid` and `TimeMap` depend on nothing but stdlib (no cross-package
imports). This layer imports nothing from `bios/`, holding the core invariant that `core/` never depends on the
presentation host: all GL/UI — the framebuffer-dependent lane↔y mapping, input handling, rendering — lives in the
`bios/` editor, which depends on this layer and not the reverse. No Minecraft, no GL, so the model + math are
testable headless and reusable by a standalone tool. Scope is intentionally v1-small (taps + moves; `holdMs` is
carried but not yet authored here); richer ops (undo history, hold authoring) layer on in later sub-phases.
