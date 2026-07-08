# early/core/galaga — the hidden Galaga game's logic (headless)

## Intent
This layer is the pure logic of the hidden Galaga game (revealed by the `galaga` secret): a full fixed-shooter —
a player ship at the bottom, an enemy FORMATION that flies in / sways / peels off divers that swoop and shoot, boss
galagas that fire a TRACTOR BEAM to CAPTURE your ship, the RESCUE-into-DUAL-FIGHTER mechanic, escalating waves, and
3 lives. Headless — no GL, no Minecraft, no input plumbing — the same `core/snake` ↔ `bios/screen/mode` split:
`GalagaScreen` (presentation) owns one of these, feeds it input intent, and draws it. Kept separate so the (meaty)
rules are testable on their own (a throwaway headless verifier auto-plays ~90s + drives capture→rescue→dual).

## Components
- **`GalagaGame`** — the whole game state + rules, mutated only on the render thread (nothing synchronized). Unlike
  Snake/Tetris it is **time-stepped**: `tick(double dt)` integrates by the elapsed seconds the screen passes, so
  movement (formation sway, dive swoops, the beam) is smooth, not cell-stepped. Coordinates are floats in a
  `FIELD_W`×`FIELD_H` (44×26) cell space; the renderer snaps to cells. Input intent comes via `setLeft/​setRight`
  (HELD state) + `fire()` (per-press, cooldown + the iconic 2-shot cap, doubled when dual). Public state for the
  renderer: `playerX`, `lives`, `score`, `wave`, `dual`, `gameOver`, `enemies` (each `Enemy` has type/slot/state/x/y/
  `holdsCaptive`/`beaming`), `playerBullets`/`enemyBullets`, capture (`captured`/`captiveX`/`captiveY`),
  `interludeTimer`, and `respawnRemaining()` (the death-window seconds the renderer blinks the ship over). The
  per-boss `Enemy.beaming` flag (true only in a boss's beam sub-phase) lets the renderer draw a beam under EACH
  beaming boss — the legacy shared `beamActive`/`beamX`/`beamTopY` scalars only ever tracked one. The `Enemy.state`
  machine: ENTERING → FORMATION → (DIVING | RETURNING | BEAMING). `reset()` restarts; `isPlaying()` = `!gameOver`.
  GLFW codes are NOT used here (intent only).

## How it works
The screen drains its input each frame, sets the held left/right + fires, then calls `tick(dt)`. `tick` moves the
player, the bullets (both ways), advances every enemy by its state, runs the dive scheduler (once the wave has
settled + the interlude elapsed), resolves collisions, and spawns the next wave when the formation is cleared.
**Capture**: a boss BEAMING run descends to a hover and emits a beam; a player under it is CAPTURED (the ship rises,
costs a life, and the boss carries it home in formation). If that captor is DESTROYED mid-rise (a shot already in
flight catches it), the capture is ABORTED — the player keeps the ship and loses no life — rather than completing on
the corpse. **Rescue**: the captive is held BESIDE the boss (not below — so a shot up the boss column hits the BOSS to
rescue, while the captive column is its own target you can lose); destroying the holding boss frees the ship into a
DUAL FIGHTER (two ships, double fire). `dt` is clamped to 0.04 s so even the fastest bullet (40 c/s → 1.6 cells/tick)
steps less than the 2.0-wide hit band and can never tunnel through a collision.

## Boundaries
Depends only on the JDK (`java.util` + `Random`). It imports NOTHING from `bios/` (or any other early-window
package) — the `core/` invariant: host-agnostic logic. `bios/screen/mode/GalagaScreen` depends on this layer (it
constructs + ticks + renders a `GalagaGame`) and translates GLFW input into the game's intent; this layer depends on
neither. No GL, no Minecraft — safe to run/test pre-Minecraft on any thread (the render thread, in practice).
