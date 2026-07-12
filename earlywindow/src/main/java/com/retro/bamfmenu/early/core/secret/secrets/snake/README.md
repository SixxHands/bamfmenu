# early/core/secret/secrets/snake, headless Snake logic
## Features
The pure logic of the hidden Snake game is a snake on a fixed grid that advances one
cell per tick, eats food to grow + score, and ends from a wall or self collision.
`bios/modes/minigame/SnakeScreen` is the presentation host, it owns one of these and draws it (with
the `core/asset.Glitch` shimmer on the body). Kept separate so the rules are testable on their own and the screen
is just I/O + rendering.

## Components
- **`SnakeGame`** is the whole game state + rules, changed only on the render thread (nothing synchronized).
  Grid is `COLS`x`ROWS` (28x16).
  - state: `body` (a `Deque<int[]>` of `{row,col}` cells, head first), `foodR`/`foodC`, `score`, `gameOver`.
  - `reset()`: starts a 3-cell snake mid-left moving right + places food.
  - `handleDir(key)`: queues a direction from an arrow key. A direct 180° reversal is rejected, non-arrow
    keys are ignored.
  - `tick()`: advances one cell. Wall/self collision -> `gameOver`, food -> grow + `score++` + respawn, else
    move (drop the tail). `isPlaying()` = `!gameOver`.
  - GLFW arrow codes are inlined, no LWJGL import.

## How it works
The screen calls `handleDir` as arrow keys arrive and `tick()` on its own move-timer. This is slower than the frame rate, so
the glitch render can shimmer every frame while the snake only steps every ~70-130ms. `tick()` applies the pending
direction, computes the new head, and checks bounds + `hits()`. On food it keeps the tail (grow) and `placeFood()` picks a
random empty cell; otherwise it drops the tail so the length is the same. `reset()` restarts in place, the screen calls it
on the game-over Retry. No high score is persisted; it is a pure Easter egg.

## Dependencies
- Depends only on the JDK (`java.util` + `Random`). It imports nothing from `bios/` or any other early-window
  package, `core/` is host-agnostic logic.
- `bios/modes/minigame/SnakeScreen` depends on this layer (it constructs + ticks + renders a `SnakeGame`) and on
  `core/asset.Glitch` for the body shimmer; this layer depends on neither.
