# early/core/panel — the rhythm highway's reactive left-panel animation framework
## Intent
This layer is the **left-panel ("reactive face") animation system** for the BAMFBEAT rhythm game: the cell beside
the note highway that reacts to play. It exists so a song can drive an expressive, swappable visual — a face that
emotes per lane / on miss, or a scripted clip — *without* the renderer ever knowing what is being drawn. The whole
design is text-out, not draw-here: an animation returns the monospace lines, a colour, and a small shake offset,
and the host (`bios/`, the GL provider) draws them. That keeps **all GL in the provider** and lets new animation
TYPES plug in without touching the renderer. It is pure `core/` — headless, editor-reusable, no `bios/` imports and
no Minecraft classes — which is why the same classes can run at game time and in the future authoring editor.
Per the plan doc, this is the `early.panel` set; will rewrite it into a data-driven, on-disk asset system,
so today's classes are deliberately the minimal framework that proves the cell is permissive.

## Components
- **`LeftPanelAnimation`** — the SPI interface every panel animation implements. Defines the per-frame contract:
  `String[] frame(State s)` (lines top-to-bottom; empty array = draw nothing), `int color(State s)` (ABGR tint),
  and default `shakeCols(State)` / `shakeRows(State)` (a small per-frame recoil the renderer adds and clamps).
  Its nested `final class State` is the reaction snapshot the renderer rebuilds **per draw** from the engine —
  `songMs`, `reactLane` (0..3, or -1), `reactChord`, `reactLaneMs`, `reactMissMs`, `reactHoldLane` (lane of a chart
  hold spanning now, or -1), `combo` — so animations stay stateless except for what they choose to remember.
  Also a default `PanelLayout layout()` (Phase C1) returning how this animation's art is placed in the cell;
  defaults to `PanelLayout.defaults()` so an animation that doesn't override it renders exactly as before.
- **`PanelLayout`** — a small record (`hAlign`, `vAlign`, `padCols`, `padRows`; `HAlign`/`VAlign` enums) describing
  how a panel animation's art sits in the cell, so the renderer FORMATS content data-drivenly instead of hardcoding
  one placement. `defaults()` = `(LEFT, CENTER, padCols 1, padRows 0)` — byte-identical to the original
  vertical-center/left-inset placement, so Recovery's face is unchanged. Pure data; reusable by Phase-6 authoring.
- **`LeftPanelTrack`** — a song's panel *timeline*: parallel `int[] startMs` + `LeftPanelAnimation[] anims`,
  sorted by start time. `active(int songMs)` returns whichever animation is in effect (the last entry whose
  `startMs` ≤ `songMs`), so a song can swap animation TYPES partway through; returns `null` before the first
  entry's start (renderer skips a null). `static single(anim)` is the common one-animation-throughout case (start 0).
  Package-private constructor — tracks are built via `single()` or by code in the layer.
- **`Keyframe`** — the shared clip-frame record (`art` + `durationMs` + ABGR `color`), used by BOTH animation types,
  so a "look"/clip is a sequence of these. (Lifted out of `ScriptedFrameAnimation` when `FaceFrameAnimation` became
  clip-based.)
- **`FaceFrameAnimation`** — the FIRST `LeftPanelAnimation` type: a reactive face, now **clip-based** (each look is a
  multi-frame `Keyframe[]`). The face is a CHALLENGER / demonstrator (DDR-style): it animates the CHART's upcoming
  notes + winces on a MISS — it does NOT react to the player's correct presses. A small state machine (advanced once
  per frame by `tick()`, which both `frame()`/`color()` call) drives it: a NEW miss (`reactMissMs` advances) or a NEW
  note/chord coming due (`reactLaneMs` advances) ABORTS the in-progress clip and restarts the new look from frame 0 —
  even for the same lane — so faster input never leaves a half-finished animation. Per-type completion: `IDLE` LOOPS;
  a lane/chord/miss look plays ONCE then falls to `IDLE`; a look whose lane has a chart hold spanning now
  (`reactHoldLane`) HOLDS its last frame for the hold span. With no idle clip it holds the last lane/chord look in the
  idle tint (the old between-notes behaviour). A 1-frame clip reproduces the old static look exactly: the back-compat
  convenience ctor wraps the old single-`String[]` looks (per-lane colours; `LANE_HOLD_MS` 320 / `MISS_HOLD_MS` 360
  clip durations) as 1-frame clips, so Recovery is byte-compatible. `shakeCols`/`shakeRows` = left-right jitter + an
  occasional bob for `SHAKE_MS` 260ms after a miss. Render-thread only, so the machine fields (`activeLook`,
  `lookStartMs`, `seenLaneMs`/`seenMissMs`, `lastLook`) need no synchronization.
- **`ScriptedFrameAnimation`** — the SECOND `LeftPanelAnimation` type: a timed clip independent of the chart. Plays a
  shared `Keyframe[]` off the song clock relative to `startMs`; `loop` cycles via `elapsed % totalMs`, one-shot settles
  on the final frame. Shipped to PROVE the cell is permissive; per its own comment **no song uses it yet**.
  Package-private constructor; render-thread only.

## How it works
The host renderer (in `bios/`) owns the loop. Each frame it builds a fresh `LeftPanelAnimation.State` from the
rhythm engine (current `songMs`, the lane/chord/miss that just fired and when, the combo), asks the song's
`LeftPanelTrack.active(songMs)` for the animation currently in effect, then — if non-null — calls `frame(state)`,
`color(state)`, and `shakeCols/Rows(state)` on it and draws the returned lines itself, applying the (clamped) shake.
Nothing in this layer touches GL; it only returns data. `FaceFrameAnimation` and `ScriptedFrameAnimation` are the two
concrete sources of that data, and `LeftPanelTrack` is the per-song selector that lets a song hand off between them at
chosen song-ms points (or, via `single()`, use one for the whole song).

## Boundaries
- **Depends on:** nothing outside this package — `LeftPanelTrack` and both animation classes only reference
  `LeftPanelAnimation` / `LeftPanelAnimation.State`. No `bios/`, no other `core/` package, no Minecraft, no LWJGL.
- **Depended on by:** the `bios/` presentation host (the GL renderer that draws the highway) consumes this SPI, and
  the song layer (`early.song` / `early.game`) supplies the `LeftPanelTrack` per song. This layer is the boundary that
  keeps the renderer ignorant of animation types.
- **Invariants:** `core/` never imports `bios/` (honoured — zero outbound imports here). The animation objects are
  **render-thread only** (single-threaded; `FaceFrameAnimation`'s state-machine fields and `ScriptedFrameAnimation`
  state are unsynchronized by design). `State` is rebuilt per draw, so animations must treat it as read-only per-frame
  input. `frame()` and `color()` must agree on the active look — both call the same once-per-frame `tick()` +
  `activeKeyframe()` in `FaceFrameAnimation` (the `tick` is idempotent within a frame via a `songMs` guard).
