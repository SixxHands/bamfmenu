# early/core/rhythm — the headless "BAMFBEAT" rhythm engine (pure game logic, no GL/audio/Minecraft)

## Intent
This is the rhythm game's pure logic — the Recovery Mode "BAMFBEAT" engine: 4 lanes, scrolling notes with hit
windows, and hold/sustain notes. It lives under `core/` precisely so it has **no GL, no audio, no Minecraft** —
the host (`bios/.../RhythmScreen`) feeds it the current song time and the player's lane presses/releases, and the
engine returns scored game state. This split is what lets the editor's "test my chart" loop drive the exact same
timing-fidelity path headless. In the .75 rhythm re-architecture the scoring /
combo / multiplier / health economy was pulled out of this engine into a uniform `ScoringEngine` (the `score/`
child) that `RhythmEngine` now *holds and delegates to*; chart generation was pulled behind a `Charter` seam (the
`chart/` child); and the input-time conversion was factored out into `RhythmClock`. What remains directly here is
the rhythm-specific core plus the timing helper.

## Components
- **`RhythmEngine`** (`final`) — the engine itself; single-threaded, **render-thread only**, so nothing is
  synchronized. Owns the time-sorted `List<Note> notes`, the current `int songMs`, a `final ScoringEngine scoring`
  (the economy it delegates to, read externally through its `ScoreView`), per-lane `boolean[] laneHeld`, the
  pre-roll `countDigit`, the per-chart visual fall speed `approachMs` (defaults to `DEFAULT_APPROACH_MS=1500`; the
  host overrides it from the loaded chart — purely visual, judgment never depends on it), and the chart-driven
  left-panel react signals (`reactLane`, `reactChord`, `reactLaneMs`, `reactMissMs`). Constructor takes
  `(List<Note> chart, boolean noFail, int tailMs)` and sorts the chart by time. Key methods: `update(int songMs)`
  (drains the multiplier meter via `scoring.tickDrain`, auto-misses notes past `LATE_MS`, resolves held-through
  sustains, then `scoring.clampAndLatch()` + `announceReactions()`); `press(int lane, int t)` (finds the nearest
  unjudged note in `[-EARLY_MS, +LATE_MS]`, classifies the timing window into a `Grade`, calls `scoring.apply`);
  `release(int lane, int t)` (now timing-aware — a live sustain resolves as a *graded tail* by `|t-endMs|` →
  PERFECT/GOOD/OKAY/BAD, or a DROP if released earlier than `-EARLY_MS`); `setLaneVisual(int lane, boolean down)`
  (count-in only: lights a lane so buttons respond without judging anything); `isFinished()` / `finishMs()` /
  `chartEndMs()`; and the static `demoChart()` — a hand-authored Phase-1 test pattern (NOT synced to music)
  exercising taps, chords, bursts and holds. The nested `public static final class Note` carries
  `lane`/`timeMs`/`holdMs` plus the judgment flags `headJudged`, `headHit`, `holdActive`, `holdResolved`, and
  `faded` (MISSED or DROPPED → renders greyed-out and lingers until it falls off), with `isHold()` / `endMs()`.
  Hit windows are `static final` knobs, **tightened** (was too forgiving): `PERFECT_MS=35`,
  `GOOD_MS=70`, `OKAY_MS=100`, `LATE_MS=145` (catchable-late → BAD), `EARLY_MS=100` (earlier = ignored ghost tap);
  `LANES = 4`.
- **`RhythmClock`** (`final`, non-instantiable) — a pure timing helper, factored out of `RhythmScreen.step`. One static method `eventMs(int songMs, long nowNanos, long eventNanos)` converts a queued
  input event's capture time back to song time (`eventMs = songMs - (now - eventNanos)/1e6`) so hit accuracy
  survives the input queue. The editor's chart-test loop reuses it to feed `RhythmEngine.press()` at the right song
  time — no AudioPlayer / BiosContext / GL needed.
- **`JudgmentArt`** (`final`, non-instantiable) — Phase C2 data-driven judgment "pop" art. `render(judgment,
  comboMult)` maps the engine's `lastJudgment` ("PERFECT x3"/"MISS"/…; grade = its first word, lowercased) to the
  lines of a banner under `/ui/judge/` (shipped set = the FIGlet "graffiti" font), or `null` so the host falls
  back to plain text (zero regression). **Per-combo-tier:** the hit grades (perfect/good/okay/bad) resolve
  `/ui/judge/<grade>.<tier>.txt` first (tier = the combo multiplier 1..maxMult, e.g. `perfect.2.txt`), then the
  un-tiered `/ui/judge/<grade>.txt`, then plain text — so you author only the tiers you want. **miss/drop/fail are
  EXCLUDED from tiers** (a miss breaks combo) and always use `/ui/judge/<grade>.txt`. Each banner is its own
  editable file; lazy cache per (grade,tier), the resolved result (incl. fallback / "none") cached so a missing
  file isn't re-read. Pure — no GL; the host (`RhythmRenderer`) scales the returned lines to fit the judgment grid.

## How it works
The host (render thread) runs a frame loop: sample the current song time, call `engine.update(songMs)` to advance
the world (auto-miss, held-through sustain completion, meter drain, react announcements), and drain its input
queue. For each queued key event the host converts the captured `System.nanoTime()` back to song time via
`RhythmClock.eventMs(...)` and calls `press(lane, t)` / `release(lane, t)`. Both `update`/`press`/`release` route
every judgment through the held `ScoringEngine` (`scoring.apply(Grade, songMs)`), keeping the score/combo/health
economy uniform; the renderer and results capture read it back through `scoring` as a `ScoreView`. A pressed note
classifies into PERFECT/GOOD/OKAY/BAD; a press with nothing in range emits FAIL (free in no-fail). Holds are
graded on the *release*: `release()` resolves a live sustain as a graded tail or a DROP, while a sustain held all
the way past `endMs()+LATE_MS` is auto-completed in `update()` as PERFECT (still held) or GOOD. During the pre-roll
`songMs` is negative and `countDigit` counts in (3/2/1/0) so notes scroll in from the top — the host uses
`setLaneVisual()` (not `press`) so buttons respond without scoring — before the audio clock takes over at
`songMs=0`; a natural finish fires at `finishMs()` (chart end + `tailMs`). The chart fed to the constructor is
produced upstream by the `chart/` child (a `Charter`); `demoChart()` is the in-engine fallback pattern.

## Boundaries
Depends only on its `score/` sibling (`Grade`, `ScoreRules`, `ScoringEngine`) and `java.util` — no `bios/`, no GL,
no audio, no Minecraft. Invariant: **`core/` never imports `bios/`**; this engine is the headless half that
`bios/.../RhythmScreen` (the host/VIEW side) drives and reads. Threading invariant: **render-thread only** — all
mutable state here is touched on the single render thread, including the `react*` view signals and the visual
`approachMs`/`faded`/`countDigit` fields, which is why nothing is synchronized. `Note` and `LANES` are public
because the chart pipeline (the `chart/` child) constructs notes against them.

## Child layers
- `score/` — the uniform scoring economy: `Grade` (enum: PERFECT/GOOD/OKAY/BAD/MISS/DROP/FAIL/SUSTAIN),
  `ScoreRules` (economy knobs), `ScoreView` (read-only getters), `ScoringEngine`. `RhythmEngine` holds a
  `ScoringEngine` and delegates all judgments to it through `apply()` (plus `tickDrain`/`clampAndLatch`); see its
  README.
- `chart/` — the chart model + the `Charter` generate seam (`Charter`, `Chart`, `ChartIO`, `ChartRequest`,
  `ChartBootstrap`, and `impl/` charters incl. `DspCharter`/`FileCharter`/`ChartGenerator`) that produces the
  `List<Note>` fed to `RhythmEngine`; see its README.
