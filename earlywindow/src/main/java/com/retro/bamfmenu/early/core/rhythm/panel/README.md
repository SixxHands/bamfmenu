# early/core/rhythm/panel rhythm left-panel animation framework
## Features
The left-panel ("reactive face") animation system for the BAMF! uses the cell beside the note highway
that reacts to play. A song can drive an expressive, swappable visual (a face that emotes per lane or on a
miss, or a scripted clip) without the renderer ever knowing what is being drawn. The whole design is
text-out, not draw-here. An animation returns the monospace lines, a color, and an optional small shake offset, and the
host in `bios/` draws them. That keeps all GL in the provider and lets new animation types plug in without
touching the renderer. Pure `core/`: headless, no `bios/` imports, no Minecraft, so the same classes can run
at game time and in the future authoring editor.

The face art is already data-driven, `SongLoader` builds `FaceFrameAnimation` clips from
`/songs/<id>/panels/face/*.txt`. Animation types remain code. A fuller on-disk asset system layers on later,
so today's classes are deliberately the minimal framework that proves the cell is permissive.

## Components
- **`LeftPanelAnimation`** is the interface every panel animation implements.
  - `String[] frame(State s)`: the lines, top to bottom. Empty array = draw nothing.
  - `int color(State s)`: the ABGR tint.
  - `shakeCols(State)` / `shakeRows(State)`: a small per-frame recoil the renderer adds and clamps.
  - `PanelLayout layout()`: how the art is placed in the cell. Defaults to `PanelLayout.defaults()` so an
    animation that doesn't override it renders exactly as before.
  - its nested `State` is the reaction snapshot the renderer rebuilds per draw from the engine: `songMs`,
    `reactLane` (0..3, or -1), `reactChord`, `reactLaneMs`, `reactMissMs`, `reactHoldLane` (lane of a chart
    hold spanning now, or -1), `combo`. Animations stay stateless except for what they choose to remember.
- **`PanelLayout`** is a small record describing how the art sits in the cell (`hAlign`, `vAlign`, `padCols`,
  `padRows`), so the renderer places art from data instead of hardcoding one placement. `defaults()` is
  byte-identical to the original left-inset placement, so Recovery's face is unchanged.
- **`LeftPanelTrack`** is a song's panel timeline: parallel `int[] startMs` + `LeftPanelAnimation[] anims`,
  sorted by start time. `active(int songMs)` returns whichever animation is in effect, so a song can swap
  animation types partway through. Null before the first entry's start, the renderer skips a null.
  `static single(anim)` is the common one-animation-throughout case.
- **`Keyframe`** is the shared clip-frame record (`art` + `durationMs` + ABGR `color`), used by both animation
  types, so a "look" is a sequence of these.
- **`FaceFrameAnimation`** is the first animation type: a reactive face, clip-based, each look a multi-frame
  `Keyframe[]`. The face is a challenger (DDR-style): it animates the CHART's upcoming notes + winces on a
  miss, it does NOT react to the player's correct presses.
  - the machine: a small state machine advanced once per frame by `tick()`, which both `frame()` and
    `color()` call. A new miss or a new note coming due aborts the in-progress clip and restarts the new look
    from frame 0, even for the same lane, so faster input never leaves a half-finished animation.
  - completion per type: idle loops, a lane/chord/miss look plays once then falls to idle, and a look whose
    lane has a chart hold spanning now keeps its last frame for the hold span. With no idle clip it keeps the
    last look in the idle tint.
  - back-compat: a 1-frame clip reproduces the old static look exactly, so Recovery is byte-compatible.
  - shake + threading: left-right jitter + an occasional bob for ~260ms after a miss. Render-thread only, so
    the machine fields need no synchronization.
- **`ScriptedFrameAnimation`** is the second animation type: a timed clip independent of the chart, played
  off the song clock relative to `startMs`. `loop` cycles, one-shot settles on the final frame. Shipped to
  prove the cell is permissive, no song uses it yet.

## How it works
The host renderer owns the loop. Each frame it builds a fresh `State` from the rhythm engine, asks the song's
`LeftPanelTrack.active(songMs)` for the animation in effect, then calls `frame`/`color`/`shakeCols/Rows` and
draws the returned lines itself, applying the clamped shake. Nothing in this layer touches GL, it only
returns data.

## Dependencies
- Depends on nothing outside this package. No `bios/`, no other `core/` package, no Minecraft, no LWJGL.
- Depended on by the `bios/` presentation host (the rhythm renderer draws these), and the rhythm root
  supplies the track per song: `SongDef.leftPanel` is a `Supplier<LeftPanelTrack>`, `newLeftPanel()` rebuilds
  it fresh per play, and `SongLoader` composes the tracks.
- `core/` never imports `bios/`.
- The animation objects are render-thread only, unsynchronized by design.
- `State` is rebuilt per draw, animations must treat it as read-only per-frame input.
- `frame()` and `color()` must agree on the active look: both call the same once-per-frame `tick()`,
  idempotent within a frame via a `songMs` guard.
