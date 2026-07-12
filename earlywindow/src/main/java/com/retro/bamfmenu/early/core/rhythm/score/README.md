# early/core/rhythm/score, the headless scoring/combo/multiplier/health economy
## Features
The uniform scoring state machine for the BAMF! rhythm game, lifted verbatim out of the
old monolithic `RhythmEngine` so the economy can be driven by any game mode, not
just the rhythm highway. The whole point is byte-identical reuse: the rhythm game classifies a press's timing
window into a `Grade` and calls `apply()`, a bespoke SPECIAL classifies its own conditions into the same grades
and calls the identical `apply()`. The combo/multiplier/health math lives only here, so both behave the same.
`ScoreRules.defaults()` reproduces the historical constants exactly (the 350/200/120/40 point
table, `MAX_MULT=4`, the H_* health deltas) so the extraction changed no behavior. Callers may instead pass
their own `ScoreRules` to soften or disable a penalty while keeping the same machine. It is `core/`, so it
imports nothing from `bios/` and knows nothing of GL.

## Components
- **`Grade`** is the uniform judgment token fed into `ScoringEngine.apply()`. Values:
  `PERFECT, GOOD, OKAY, BAD, MISS, DROP, FAIL, SUSTAIN`. Permissive by design: a caller emits only the subset it
  uses (e.g. `{PERFECT, MISS}`), and unused branches never fire.
- **`ScoreRules`** is a record, the tunable economy knobs in one unchangeable place: `healthMax`/`healthStart`, the
  per-grade health deltas, `maxMult`, and the point table. `defaults()` returns the verbatim historical
  values. The rules vary per caller, the engine does not.
- **`ScoreView`** is the read-only face of the scoring state for whoever draws or reports it
  (the rhythm renderer, a special's HUD, results capture, the editor's test view). Exposes every value a GUI
  needs without letting it change anything. `RhythmRenderer` takes a `ScoreView`, not a `ScoringEngine`.
- **`ScoringEngine`** is the actual state machine and the one `ScoreView` implementation. Single-threaded
  (render thread), nothing synchronized, like the rest of the early-window game logic. Constructed with a
  `ScoreRules` + a `noFail` flag. Key methods:
  - `apply(Grade g, int stampMs)`, the single uniform per-grade entry. `stampMs` tags the judgment text for the
    transient flash (ignored by FAIL/SUSTAIN, which show no text).
  - `tickDrain(int songMs)`, opt-in, time-based: drains the multiplier meter 1/sec of song time. A caller with
    no song clock never calls it.
  - `clampAndLatch()`, end-of-update health clamp + one-shot fail latch (catches a DROP's unclamped subtraction).
  - `flushPending()`, banks pending combo-bucket points into `score` without resetting the streak (a natural
    song finish mid-combo).

## How it works
The owning game logic keeps the single `ScoringEngine`, everyone who draws keeps it only as a `ScoreView`. Each
frame a caller may first `tickDrain(songMs)` (meter decay), then for every judged event call `apply(grade,
stampMs)`, and after the per-note loop call `clampAndLatch()`.

Inside `apply`, the per-grade order is load-bearing and verbatim from the old engine: the meter (`comboPerfects`)
is changed BEFORE `comboMult()` is read, so a hit that crosses an x2/x3/x4 threshold banks at the new multiplier.
Only `PERFECT` grows the meter, only `BAD` knocks it down, `GOOD`/`OKAY` leave it untouched. Points accrue into
`comboBucket` (`pts * mult`), not directly into `score`. A `MISS`/`DROP`/`FAIL` calls `breakCombo()`, banking the
bucket into `score` and zeroing the streak + meter. A clean song end uses `flushPending()` to bank without
breaking. `comboMult()` derives x1 below 4 perfects, then doubling thresholds (x2 at 4, x3 at 8, x4 at 16) capped
at `rules.maxMult()`. Health is clamped to `[0, healthMax]`. Outside no-fail mode, crossing 0 latches `failed`.
The renderer/HUD then reads back through `ScoreView` to draw the score, combo, multiplier, health bar, and the
transient judgment flash.

## Dependencies
- Depends only on the JDK (one `Integer.numberOfLeadingZeros` for the log2 multiplier step) and on its own three
  siblings (`ScoringEngine` uses `Grade`, `ScoreRules`, `ScoreView`). It imports nothing: no `bios/`, no
  `org.lwjgl.*`, no Minecraft.
- Depended on by `early.core.rhythm.engine.RhythmEngine` (which keeps a `ScoringEngine` and delegates
  scoring) and by the presentation side (`RhythmRenderer`/`SongHeaderHud` and any SPECIAL's HUD) through
  `ScoreView`.
- Single-threaded render-thread use only (no synchronization), `ScoreView` is read-only (changes go through
  the `ScoringEngine`), and the per-grade update order in `apply()` must stay verbatim or the multiplier
  threshold-crossing reward changes.
