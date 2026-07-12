# early/core/tetris, headless terminal-Tetris game logic
## Features
The self-contained logic + state for the BAMFOS "Enable Mechanics" Tetris option. It lives under `core/`
because it is pure simulation: no OpenGL, no Minecraft classes, no threading primitives, so the early-window
jar stays a plain `ImmediateWindowProvider` service and the same code is reusable from a future editor. The
piece math is explicit four-state offset tables plus a simple horizontal wall kick, not SRS pivot math,
compact and hard to get wrong. Gravity is tick-based, frames-per-cell, because the host ticks at ~20fps. It
sits here so the presentation host (a `TetrisScreen` under `bios/`) can import it while it imports nothing
back.

## Components
- **`TetrisGame`** is the entire game: board model, falling-piece mechanics, scoring, and input
  interpretation. Runs on the render thread ONLY; nothing is synchronized.
  - public state the renderer reads: `cells[ROWS][COLS]` (locked board), `score`, `level`, `lines`, active
    piece `id`/`rot`/`px`/`py`, `next` (preview), `gameOver`, `paused`, `pauseSelYes` (quit-confirm
    highlight).
  - per-frame methods: `tick()` (advance gravity one frame, no-op while paused/over), `handleKey(int)` ->
    `SIGNAL_NONE`/`SIGNAL_QUIT_TO_BIOS` (one GLFW key code against the playing/paused/over sub-state),
    `isPlaying()`. Renderer helpers `activeCells()` and the static `shape(id, rot)` (the next-piece preview).
  - internals: piece moves (`move`/`rotate`/`softDrop`/`hardDrop`/`stepDown`/`lockPiece`), line handling
    (`clearLines`/`rowFull`/`collapseInto`), `spawn` with top-out detection, a 7-bag randomizer
    (`nextFromBag`) for fair piece order, and `collides` (walls/floor/occupied; rows above the top allowed).

## How it works
The provider (render thread) owns exactly one `TetrisGame` instance. Each frame it drains queued GLFW key
codes into `handleKey(int)`, calls `tick()` once, then reads the public fields and `activeCells()` /
`shape(...)` to draw. `handleKey` returns a signal, `SIGNAL_QUIT_TO_BIOS` tells the host to leave Tetris
(from the game-over screen, or the pause menu's quit confirm), so the host reacts without reaching into
internals. GLFW key constants are duplicated as private fields to keep the class free of the LWJGL import.
Gravity is a frame counter: `tick()` increments `tickCount` and calls `stepDown()` when it reaches
`GRAVITY[level]`; manual drops reset that counter so they don't double-step. Locking a piece writes it into
`cells`, clears full lines (scoring by count x level, leveling every 10 lines), and spawns the next bag
piece; a spawn that immediately collides sets `gameOver`.

## Dependencies
- Depends on only `java.util.Random` (and `System.nanoTime` for the seed). Nothing else: no GL, no
  Minecraft, no `bios/`, no other `core/` package.
- Depended on by the presentation host under `bios/` (the Tetris screen), which constructs it, feeds it
  keys, ticks it, and renders its public state. The only cross-thread channel is the provider's
  `ConcurrentLinkedQueue` feeding `handleKey`.
- `core/` never imports `bios/`. Single-thread render-thread access only; the absence of synchronization is
  correct because of that rule, not in spite of it.
