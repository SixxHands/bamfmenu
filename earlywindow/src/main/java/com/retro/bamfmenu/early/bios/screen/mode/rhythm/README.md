# early/bios/screen/mode/rhythm — the BAMFBEAT rhythm game's host + view
## Intent
This is the `Mode.RHYTHM` slice — the BIOS's marquee mode, the BAMFBEAT rhythm game — split into a controller
and a view that live on the `bios/` (presentation) side. It exists because the rhythm game is the most coupled
and most reused subsystem in the early window: the headless *model* (engine, scoring, chart, clock) was pulled
into `core/` so the editor's Test Play and bespoke specials can drive a full game lifecycle, leaving this layer
to do only the two things that need the host: own the on-screen lifecycle + input wiring (`RhythmScreen`) and
draw the playfield through the `TerminalCanvas` facade (`RhythmRenderer`). This MODEL/VIEW/HOST split is .75 step 7.3 of the provider decomposition; the host's pre-roll/count-in and end-roll are .4. The
load-bearing rule the comments repeat: the host is pure logic (no GL, no LWJGL — GLFW key codes are duplicated
as plain ints) and the view reads engine state but holds no game logic.

## Components
- `RhythmScreen` (implements `bios.screen.Screen`) — the HOST/controller, runs on the **render thread** via
  `onEnter`/`step`/`render`/`onExit`. `onEnter` resolves the current `SongDef` (falling back to `SONGS[0]`),
  starts the audio via `ctx.startSongAudio` (paused at 0; bails if null), loads the damage SFX, and builds the
  note list two ways: an editor **Test Play** consumes the in-memory chart (`ctx.consumeTestChart`, with
  `testStartMs`/`testApproachMs`), otherwise it resolves a `SongDifficulty` (`ctx.currentDifficulty`, defaulting
  to the song's `"normal"` then first difficulty) and reads the committed chart text via `ctx.loadChartText(id,
  token)` (BAMFOS first, then bundled), parsed by `ChartIO.read` into a `Chart`. An empty/uncharted result sets a
  notice and exits. It then constructs the `RhythmEngine` (no-fail = `ctx.safeForRetro`, end-roll = `PREROLL_MS`),
  applies the chart's per-note fall speed (`engine.approachMs = approach` when > 0), `renderer.reset()`s, grabs
  the per-song `newLeftPanel()`, arms the silent pre-roll, and `ctx.enterMode(RHYTHM)`. `step` runs the silent
  pre-roll (negative clock from `startAtMs` so opening notes scroll in + a 3-2-1-0 `countDigit`; lane presses
  call `g.setLaneVisual` to LIGHT the lane unjudged, ESC routes to `returnToEditor`/`openPauseMenu`), then on
  exit either `playFrom(startAtMs)` (Test Play seek) or `resume()`. Live play: reads the audio clock, fades the
  song out over `FADE_OUT_MS` before `stopMs` (min of audio end and `finishMs`), drains the provider-owned
  timestamped `input` queue (`long[]{key, action, eventNanos}`), converts each via `RhythmClock.eventMs`, judges
  `press`/`release` into the engine, plays the damage SFX on a new miss/fail, and routes to RESULTS on fail
  (game-over) or finish/clear. ESC pauses (or returns to the editor on a Test Play). `render` guards on a live
  engine and delegates to the renderer. Key constants: `PREROLL_MS` (2800, symmetric count-in AND end-roll),
  `COUNT_STEPS` (4), `FADE_OUT_MS` (500), `DAMAGE_SFX_GAIN` (0.5). Fields: `startAtMs` (Test Play cursor),
  `inPreRoll`, `preRollStartNanos`. Private helpers: `enterResults`, `exit`, `laneForKey`, `perfectPct`.
- `RhythmRenderer` — the VIEW, **render-thread only**. `render(c, ctx, g, ScoreView, panel)` draws the whole
  playfield through `TerminalCanvas`: the bordered three-column highway box, the 4-lane color-coded note scroll
  (held key boxes that light, holds drawn as bounded ASCII capsules with `|-| ` caps + a `^` release cue at the
  tail), the reactive left panel (`LeftPanelTrack`/`LeftPanelAnimation`), the right-hand
  score/combo/health/judgment HUD, the song-name + 3-line waveform header (playhead = the gold/dim colour
  split), and the centered count-in. It reads the engine's public note/lane/react state (and `g.chartEndMs()`
  as a waveform-length fallback) and a read-only `ScoreView` (not the mutable `ScoringEngine`).
  **Phase B decomposition:** `render()` is now a LAYOUT pass + a dispatch over `RhythmUiPart` —
  it computes the per-frame geometry once (`computeLayout` → a private `Layout` record), bundles the live model
  (a private `Frame` record), then loops `RhythmUiPart.values()` calling one `drawXxx` part-unit each, and
  finally draws the count-in as a centered overlay (not an FX-target part). The part draws are `drawHeader`
  (delegates to `SongHeaderHud`), `drawBorder`, `drawLanes`, `drawHitzones`, `drawNotes`, `drawLeftFrame`,
  `drawJudgment`, `drawScore`, `drawCombo`, `drawHealth`, `drawTally`. This is **behavior-preserving** — the
  highway is pixel-identical; the right-HUD's old mutable `ry` cursor became absolute `playTop + ls*N` offsets,
  and the single lane loop split into two non-overlapping passes (dashes, then hit boxes). It holds the SCORE
  odometer animation state (`shownScore`/`scoreAnim*`) and a lazy `countArt` cache — both render-thread display
  state; `reset()` clears the odometer at song start. Other private helpers: `drawCountdown`, `countArt`,
  `drawLeftPanel`, `animatedScore`, `judgmentColor`, `railRow`, `boxRule`. (The old inline
  `drawWaveform`/`sampleWaveform` were removed — the header now reuses `SongHeaderHud`.) Constants:
  `LANE_GLYPHS` (`< v ^ >`), `EMPTY_WAVE`, `SONG_LABEL` fallback.
  **Phase C improvements:** `drawLeftFrame`/`drawLeftPanel` now honor a
  per-animation `PanelLayout` (align + padding; defaults to the old placement, so existing panels are unchanged).
  `drawJudgment` now renders a per-grade, **per-combo-tier** ASCII banner (`core.rhythm.JudgmentArt` →
  `/ui/judge/<grade>.<tier>.txt` for hit grades, tier = combo multiplier 1..maxMult, falling back to
  `/ui/judge/<grade>.txt`; miss/drop/fail are un-tiered; FIGlet "graffiti") UNIFORMLY scaled to fill a reserved
  **judgment grid** + centered, via
  `TerminalCanvas.drawScaledCentered` — so big art always fits its own area and never auto-cuts; with no banner
  for a grade it falls back to the old plain-text judgment. The grid is a **TWO-ROW-tall zone at the top of the
  right HUD column** (the score/combo/health/tally stats reflow BELOW it, anchored at `Layout.hudTop`, which
  reserves `judgmentRows` at the column top — adaptively 2..10 rows so the 12-row stats block always fits). Two
  rows means you can author a banner as one row OR as two rows stacked in the `.txt`; it scales to fit either —
  no split-marker, no wrapping. The grid is a few numbers in `drawJudgment`/`computeLayout` — trivially resizable.
  `drawTally` is now a compact **3-col × 2-row grid** (PERF/GOOD/OKAY · BAD/MISS; labels shrink to single letters
  on a narrow column) instead of 5 stacked rows, so the stats block is ~9 rows and the judgment zone gets the
  freed space (`computeLayout`: `statsRows`/`judgmentRows`).
- `RhythmUiPart` — the enum of the named, addressable highway parts (Phase B): `WAVEFORM`, `BORDER`, `LANES`,
  `HITZONES`, `NOTES`, `LEFTFRAME`, `JUDGMENT`, `SCORE`, `COMBO`, `HEALTH`, `TALLY`. Each carries a STABLE
  lowercase string `id()`; the
  declaration order IS the draw order, so `values()` drives `render()`'s dispatch. Lookup is GRACEFUL —
  `byId(String)` returns `null` for an unknown/renamed/null id and `RhythmRenderer.draw(null,…)` is a no-op, so
  the part set is "locked in but open": parts can be added/renamed without breaking callers or a stale chart.
  Pure data — no GL, no bios/core imports.
- `SongHeaderHud` — a stateless, final utility (private ctor) holding `static render(c, x, labelY, waveY, cols,
  label, waveform, playFrac)`. It draws the song-name line + 3-line scrubbing waveform. Originally a deliberate
  **COPY** of `RhythmRenderer`'s inline header so a bespoke SPECIAL mode could
  reuse the familiar BAMFBEAT header without pulling in the whole renderer. As of Phase B it is
  also the **single source** of the header: `RhythmRenderer`'s `WAVEFORM` part now delegates here (the inline
  copy was removed), so the main game and specials draw the identical widget. Pure: draws only through
  `TerminalCanvas`, holds no state, null/empty-safe — a special that simply never calls it can't crash by its
  absence.

## How it works
The provider's render loop calls `RhythmScreen.step` then `RhythmScreen.render` each frame. `step` is the
control hub: it pulls the audio clock and drains input the MAIN thread enqueued (onKey/onChar never touch game
state directly — they only push to `input`), so press accuracy survives the queue via `RhythmClock.eventMs`.
The host feeds judged input into the `core` `RhythmEngine` and reads its `ScoringEngine` to decide when to bank
the run (`ctx.bankRhythm`, returning new-best) and hand off to the RESULTS card (`ctx.showResults`), or to pause
(`ctx.openPauseMenu`), return to the editor on a Test Play (`ctx.returnToEditor`), or exit back to SETUP
(`ctx.enterMode(SETUP)`). The grade is `perfectPct` — percent of judgments earned as PERFECT, with holds
counting twice (head press + tail release). `render` produces nothing itself; it hands the live engine +
`ScoreView` + per-song `LeftPanelTrack` to `RhythmRenderer`, which is the sole play-UI drawer. Inside the
renderer, `render()` runs the layout once then dispatches each `RhythmUiPart` in order; the `WAVEFORM` part and
any special mode both draw through `SongHeaderHud`, so the header is one shared widget rather than a duplicated
copy.

## Boundaries
Depends on `bios/` collaborators (`Mode`, `Screen`, `BiosContext`, `TerminalCanvas`) and reaches into `core/`
for the model + assets (`core.rhythm.RhythmEngine`/`RhythmClock`/`chart.Chart`/`chart.ChartIO`/`score.ScoreView`,
`core.audio.AudioPlayer`, `core.song.SongDef`/`SongDifficulty`/`SongRegistry`, `core.panel.*`,
`core.asset.Art`). This respects the one-way invariant: `bios/` may import `core/`, never the reverse — none of
these classes is imported by `core`. Invariants: the host carries **no GL/LWJGL** (key codes duplicated as
ints) and is the input→engine wiring; the renderer is **render-thread only** and reads engine/score state but
never mutates game logic; the SCORE odometer + count-art cache are render-thread display state. `RhythmScreen`
is constructed and stepped by the provider, which owns the shared `input` queue it drains.

(No subfolders — this is a leaf layer with four direct classes: `RhythmScreen`, `RhythmRenderer`,
`RhythmUiPart`, `SongHeaderHud`.)
