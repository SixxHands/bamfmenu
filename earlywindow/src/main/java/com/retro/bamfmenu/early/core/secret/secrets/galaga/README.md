# early/core/secret/secrets/galaga, headless Galaga logic
## Features
The pure logic of the Galaga. A player ship starts at the bottom, an enemy formation that flies in, sways, and 
peels off divers that swoop and shoot, boss galagas that fire a tractor beam to capture your ship, 
the rescue-into-dual-fighter mechanic, escalating waves, and 3 lives. `bios/modes/minigame/GalagaScreen` is the
presentation host, it owns one of these, feeds it input, then draws it.

## Components
- **`GalagaGame`** is the whole game state + rules.
  - time-stepped, unlike Snake/Tetris: `tick(double dt)` integrates by the elapsed seconds the screen passes,
    so movement is smooth, not cell-stepped. Coordinates are floats
    in `FIELD_W`x`FIELD_H` (44x26) cell space. The renderer snaps to the cells.
  - input intent comes via `setLeft`/`setRight` (held state) + `fire()` (per-press, cooldown + the iconic
    2-shot cap, doubled when dual).
  - public state for the renderer: `playerX`, `lives`, `score`, `wave`, `dual`, `gameOver`, `enemies` (each
    `Enemy` has type/slot/state/x/y/`holdsCaptive`/`beaming`), `playerBullets`/`enemyBullets`, capture
    (`captured`/`captiveX`/`captiveY`), `interludeTimer`, and `respawnRemaining()` (the death-window seconds
    the renderer blinks the ship over).
  - the per-boss `Enemy.beaming` flag is only true during a boss's beam sub-phase. This lets the renderer draw a beam
    under each beaming boss.
  - the `Enemy.state` cycles through ENTERING -> FORMATION -> (DIVING | RETURNING | BEAMING). `reset()` restarts,
    `isPlaying()` = `!gameOver`.

## How it works
The screen drains its input each frame, sets the held left/right + fires, then calls `tick(dt)`. `tick`
moves the player, the bullets, advances every enemy by its state, runs the dive scheduler (once
the wave has settled + the interlude elapsed), resolves collisions, and spawns the next wave when the
formation is cleared. During capture a beaming boss descends to hover and emits a beam, a player under it is
captured. If that captor dies mid-rise, the capture is aborted and the player keeps the ship and
loses no life, rather than completing on the corpse. For rescue the captive is held beside of the boss,
so a shot up the boss column hits the boss to rescue, while the captive column is its own target you can
lose. Destroying the holding boss frees the ship into a dual fighter. `dt` is
clamped to 0.04s so even the fastest bullet steps less than the 2.0-wide hit band
and can NEVER tunnel through a collision.

## Dependencies
- Depends only on the JDK (`java.util` + `Random`). It imports nothing from `bios/` or any other
  early-window package.
- `bios/modes/minigame/GalagaScreen` depends on this layer and translates GLFW input into the game.
