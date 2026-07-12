# early/bios/modes/rhythm, the BAMF! rhythm game
## Features
The `Mode.RHYTHM` slice, the BIOS's marquee mode, the BAMF! rhythm game, split into a controller and a
view. The headless model (engine, scoring, chart, clock) lives in `core/` so the editor's Test Play and the
bespoke specials can drive a full game lifecycle. This layer keeps only what needs the host: `RhythmScreen`
owns the on-screen lifecycle + input wiring, `RhythmRenderer` draws the playfield through the `TerminalCanvas`
facade. The rule: the host is pure logic (no GL, no LWJGL), the view reads engine state but keeps no game
logic.

## Components
- **`RhythmScreen`** (implements `Screen`) is the HOST/controller, render thread via
  `onEnter`/`step`/`render`/`onExit`.
  - onEnter: resolves the `SongDef` (falls back to `SONGS[0]`), starts the audio paused at 0 via
    `ctx.startSongAudio` (bails if null), builds the note list, constructs the `RhythmEngine` (no-fail =
    `ctx.safeForRetro`, end-roll = `PREROLL_MS`), applies the chart's fall speed (`engine.approachMs` when
    > 0), arms the silent pre-roll, then `ctx.enterMode(RHYTHM)`.
  - charts, two ways: a Test Play consumes the editor's in-memory chart (`ctx.consumeTestChart` +
    `testStartMs`/`testApproachMs`), otherwise it resolves a `SongDifficulty` (default `"normal"`, then first)
    and reads the committed text via `ctx.loadChartText` (BAMFOS first, then bundled), parsed by
    `ChartIO.read`. An empty/uncharted result sets a notice and exits.
  - pre-roll: a negative clock from `startAtMs` so opening notes scroll in, a 3-2-1-0 count, lane presses
    LIGHT the lane unjudged. On exit it `playFrom(startAtMs)` (Test Play seek) or `resume()`.
  - live play: reads the audio clock, drains the timestamped queue (`long[]{key, action, eventNanos}`,
    converted via `RhythmClock.eventMs`), judges press/release into the engine, plays the damage SFX on a new
    miss/fail, fades the song over `FADE_OUT_MS` before the end, routes to RESULTS on fail or finish/clear.
    ESC pauses, or returns to the editor on a Test Play.
  - `PREROLL_MS` is symmetric, the same 2800ms is the count-in AND the end-roll.
- **`RhythmRenderer`** is the VIEW, render-thread only. Draws the whole playfield through `TerminalCanvas`,
  reads the engine's public state and a read-only `ScoreView`, never the `ScoringEngine` itself.
  - `render()` is one layout pass (`computeLayout` -> a `Layout` record) then a dispatch over
    `RhythmUiPart.values()`, one `drawXxx` per part, the count-in drawn last as a centered overlay (not an
    FX-target part). The highway stayed pixel-identical through that split. The header now delegates to
    `SongHeaderHud`, the inline waveform copy was removed.
  - display state it keeps: the SCORE odometer animation and a lazy count-art cache, `reset()` clears the
    odometer at song start.
  - Left panels honor a per-animation `PanelLayout` (defaults keep the old placement, existing panels
    unchanged). `drawJudgment` draws a per-grade, per-combo-tier ASCII
    banner (`core.rhythm.JudgmentArt`, `/ui/judge/<grade>.<tier>.txt`, tier = combo multiplier, miss/drop/fail
    un-tiered, FIGlet "graffiti", plain text when no banner exists) scaled UNIFORMLY into a reserved judgment
    grid via `TerminalCanvas.drawScaledCentered`, so big art always fits its area and never auto-cuts.
  - the judgment grid: a two-row zone at the top of the right HUD column. `judgmentRows` adapts 2..16 so the
    9-row stats block below always fits (`Layout.hudTop`). Two rows means a banner is authored as one row or
    two stacked, it scales to fit either, no split-marker, no wrapping. Resizing the grid is a few numbers in
    `drawJudgment`/`computeLayout`.
  - `drawTally`: a 3-col by 2-row grid (labels shrink to single letters on a narrow column) instead of 5
    stacked rows, the freed rows go to the judgment zone.
- **`RhythmUiPart`** is the enum of named, addressable highway parts. Pure data, no GL, no bios/core
  imports.
  - order: declaration order IS the draw order, `values()` drives `render()`'s dispatch.
  - id: each part carries a STABLE lowercase `id()`, the name a future data-driven FX chart addresses a
    part by.
  - graceful: `byId` returns `null` for an unknown/renamed id and `draw(null, …)` is a no-op, so parts can be
    added or renamed without breaking callers or a stale chart.
- **`SongHeaderHud`** is a stateless static utility: the song-name line + the 3-line scrubbing waveform.
  - born a deliberate COPY of the renderer's inline header, so a bespoke SPECIAL could reuse the familiar
    BAMF! header without pulling in the whole renderer.
  - now the single source: the renderer's WAVEFORM part delegates here, so the main game and specials draw
    the identical widget.
  - pure: draws only through `TerminalCanvas`, keeps no state, null/empty-safe, a special that never calls it
    can't crash by its absence.

## How it works
The provider calls `RhythmScreen.step` then `render` each frame. `step` is the control hub: it pulls the audio
clock and drains what the MAIN thread enqueued (`onKey`/`onChar` never touch game state, they only push to
`input`), so press accuracy survives the queue via `RhythmClock.eventMs`. Judged input feeds the `core`
`RhythmEngine`, and the scoring decides the exits: bank the run + RESULTS (`ctx.bankRhythm` returning
new-best, `ctx.showResults`), pause (`ctx.openPauseMenu`), back to the editor on a Test Play
(`ctx.returnToEditor`), or back to SETUP. The grade is `perfectPct`, the percent of judgments earned as
PERFECT, keeps counting twice (head press + tail release). `render` draws nothing itself, it hands the live
engine + `ScoreView` + per-song `LeftPanelTrack` to `RhythmRenderer`, the sole play-UI drawer. Inside, layout
runs once, each `RhythmUiPart` dispatches in order, and the WAVEFORM part and any special both draw through
`SongHeaderHud`, one shared widget instead of a duplicated copy.

## Dependencies
- Depends on the `bios/` collaborators (`Mode`, `Screen`, `BiosContext`, `TerminalCanvas`) and reaches into
  `core/` for the model + assets (`RhythmEngine`/`RhythmClock`/`Chart`/`ChartIO`/`ScoreView`, `AudioPlayer`,
  `SongDef`/`SongDifficulty`/`SongRegistry`, the `rhythm.panel` classes, `Art`).
- One-way as always: `bios/` may import `core/`, never the reverse, nothing here is imported by `core`.
- The host carries no GL/LWJGL (key codes duplicated as plain ints), the renderer is render-thread only and
  never changes game logic, the odometer + count-art cache are render-thread display state.
- The provider constructs and steps `RhythmScreen` and owns the shared `input` queue it drains.
