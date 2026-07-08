# early/core/snake — the hidden Snake game's logic (headless)

## Intent
This layer is the pure logic of the hidden Snake game (revealed by the `snake` secret): a snake on a fixed grid that
advances one cell per tick, eats food to grow + score, and ends on a wall or self collision. It is headless — no GL,
no Minecraft, no input plumbing — exactly the `core/tetris` ↔ `bios/screen/mode` split: `SnakeScreen` (presentation)
owns one of these and draws it (with the `core/asset.Glitch` shimmer on the body). Kept separate so the rules are
testable on their own and the screen is just I/O + rendering.

## Components
- **`SnakeGame`** — the whole game state + rules, mutated only on the render thread (nothing synchronized). Grid is
  `COLS`×`ROWS` (28×16). State: `body` (a `Deque<int[]>` of `{row,col}` cells, HEAD first), `foodR`/`foodC`,
  `score`, `gameOver`. Methods: `reset()` seeds a 3-cell snake mid-left moving right + places food; `handleDir(key)`
  queues a direction from an arrow key (a direct 180° reversal is rejected; non-arrow keys ignored); `tick()`
  advances one cell — wall/self collision ⇒ `gameOver`, food ⇒ grow + `score++` + respawn, else move (drop the
  tail); `isPlaying()` = `!gameOver`. GLFW arrow codes are inlined (no LWJGL import, the `TetrisGame` convention).

## How it works
The screen calls `handleDir` as arrow keys arrive and `tick()` on its own move-timer (slower than the frame rate, so
the glitch render can shimmer every frame while the snake only steps every ~70–130ms). `tick()` applies the pending
direction, computes the new head, and checks bounds + `hits()` (self collision, excluding the tail cell which moves
away that step so you can chase the spot it vacated). On food it keeps the tail (grow) and `placeFood()` picks a
random empty cell; otherwise it drops the tail so the length holds. `reset()` restarts in place (the screen calls it
on the game-over Retry). No high score is persisted — it is a pure Easter egg.

## Boundaries
Depends only on the JDK (`java.util` + `Random`). It imports NOTHING from `bios/` (or any other early-window package),
holding the core invariant: `core/` is host-agnostic logic. `bios/screen/mode/SnakeScreen` depends on this layer (it
constructs + ticks + renders a `SnakeGame`) and on `core/asset.Glitch` for the body shimmer; this layer depends on
neither. No GL, no Minecraft — safe to run/test pre-Minecraft on any thread (the render thread, in practice).
