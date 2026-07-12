# early/core/rhythm/engine headless judging engine
## Features
The pure logic that plays a chart. `Note` was extracted from `RhythmEngine` to the rhythm `model/`, half the
engine's importers reached into it only to name that type. Everything here is headless (no GL, no audio, no
Minecraft) and single-threaded on the render thread, so nothing is synchronized. Songs differ only by their
chart, the engine is the reusable player.

## Components
- **`RhythmEngine`** is the 4-lane note-highway judge. Holds the chart as a `List<Note>`, the ctor sorts by
  time and computes `chartEndMs`. Its public `ScoringEngine scoring` carries the economy, built with
  `ScoreRules.defaults()` + the `noFail` "Safe For Retro" flag. The renderer reads it through its
  `ScoreView`, and it is changed only via this engine's calls.
  - `songMs`: the song clock the host writes. Runs negative during the pre-roll so opening notes scroll in,
    `countDigit` carries the 3/2/1/0 count.
  - `approachMs`: visual fall-speed look-ahead, default 1500, overridden per-chart by the host.
    Timing/judgment NEVER depend on it.
  - `laneHeld[4]` + the react signals (`reactLane`, `reactChord`, `reactLaneMs`, `reactMissMs`): what the
    renderer and the `panel/` animations read.
  - hit windows: |dt| <= PERFECT 35 / GOOD 70 / OKAY 100 ms, with an asymmetric tail. Up to 145 ms late
    still lands as BAD, more than 100 ms early is an ignored ghost tap.
  - `update(songMs)`: scoring drain, auto-miss past-window heads, complete held-through sustains, clamp +
    latch, announce reactions.
  - `press(lane, t)`: nearest unjudged in-window note by |dt| gets the grade, no note in range is a FAIL.
    Arms `holdActive` on keeps.
  - `release(lane, t)`: a live sustain resolves by release timing, too early is a DROP, else a graded tail.
  - `setLaneVisual`: count-in only, lanes respond, nothing judged.
  - `isFinished()`/`finishMs()`: `chartEndMs + tailMs`, the tail is host-supplied.
  - `demoChart()`: the hand-authored test pattern, `DspCharter`'s last-resort fallback.
- **`RhythmClock`** is a static helper: `eventMs(songMs, nowNanos, eventNanos)` converts a queued input
  event's captured `System.nanoTime()` back to the song time it actually happened, so hit accuracy survives
  the input queue's latency. Callable headless, its one caller is `RhythmScreen.step`.
- **`JudgmentArt`** is the static judgment "pop" banner resolver. `render(judgment, comboMult)` lowercases
  the judgment's first word and resolves ASCII art from `/ui/judge/`.
  - hit grades tier by combo: `<grade>.<tier>.txt` falls back to `<grade>.txt`, else null and the caller
    draws plain text.
  - MISS/DROP/FAIL never tier, a miss breaks combo.
  - the resolved result (including "none") is cached per grade+tier so a missing file isn't re-read every
    frame. Loads via `asset/Art.loadArt`. The host scales and draws.

## How it works
Per play, the `bios/` host obtains a chart through the `chart/` seam and constructs a fresh
`RhythmEngine(chart, noFail, tailMs)`. Each frame on the render thread it drains the input queue, converts
every event's timestamp with `RhythmClock.eventMs`, and feeds `press()`/`release()` (or `setLaneVisual`
during the count-in). Then `update(songMs)` advances auto-misses, sustains, the scoring drain, and the react
signals. The renderer reads engine state and calls `JudgmentArt.render` for the banner. Grade flow is
one-way: this engine classifies timing into a `Grade`, the `score/` member turns grades into the
score/combo/multiplier/health economy.

## Dependencies
- **Depends on:** the rhythm `model/` (`Note`), the sibling `score/` (`Grade`, `ScoreRules`,
  `ScoringEngine`, all economy delegated there), and `core/asset` `Art` (JudgmentArt's file loading). No
  `bios/`, no GL/LWJGL, no audio, no Minecraft.
- **Depended on by:** the `bios/` rhythm host, the editor view (reads the default approach for its
  fall-speed label), `chart/impl` (`demoChart()` fallback), and `tools/gen` (`ChartBootstrap` uses `LANES`).
  Several files still carry unused `RhythmEngine` imports left over from the Note extraction, pending
  cleanup, not real dependencies.
- `core/` never imports `bios/`. Everything is render-thread only and unsynchronized by design.
- `Note` flags are changed in place, so an engine + its chart list serve ONE play, the host constructs fresh
  per `onEnter`.
- Judgments are stamped at the engine's current `songMs` (the last `update()`'s value), preserving the
  historical press-before-update frame order.
