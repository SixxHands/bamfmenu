# early/core/tetris — headless terminal-Tetris game logic (no GL, no Minecraft)

## Intent

This layer is the self-contained logic + state for the BAMFOS "Enable Mechanics" Tetris option. It lives
under `core/` because it is pure simulation: no OpenGL, no Minecraft classes, no threading primitives, so the
early-window jar stays a plain `ImmediateWindowProvider` service and the same code is reusable from a future
editor. The class's own header states the design choices deliberately: explicit four-state offset tables (not
SRS pivot math) plus a simple horizontal wall kick, chosen as "compact and hard to get wrong for a proof of
concept," and tick-based gravity expressed in frames-per-cell because the host ticks at ~20fps. In the plan
doc's package grouping this sits with the other game logic (alongside `RhythmEngine`, `SpecialSession`); under
the core/bios split it is grouped here so the presentation host (a `TetrisScreen` under `bios/`) can import it
while it imports nothing back.

## Components

- **`TetrisGame`** (`final`) — the entire game: board model, falling-piece mechanics, scoring, and input
  interpretation. Runs on the **render thread only** (see threading contract below); nothing is synchronized.
  - Constants: `COLS=10`, `ROWS=20`; return signals `SIGNAL_NONE`/`SIGNAL_QUIT_TO_BIOS` from `handleKey`.
  - Private static data: `SHAPES` (7 tetrominoes × 4 rotations × 4 `(row,col)` cells in a 4×4 box; ids 1=I,
    2=O … 7=L), `GRAVITY` (frames-per-cell by level, level clamped to `MAX_LEVEL=10`), `LINE_SCORE`.
  - Public state read by the renderer: `cells[ROWS][COLS]` (locked board), `score`, `level`, `lines`, active
    piece `id`/`rot`/`px`/`py`, `next` (preview), `gameOver`, `paused`, `pauseSelYes` (quit-confirm highlight).
  - Public methods the host calls each frame: `tick()` (advance gravity one frame, no-op while paused/over),
    `handleKey(int)` → signal (interprets one GLFW key code against the playing/paused/over sub-state),
    `isPlaying()`; renderer read-only helpers `activeCells()` (board cells under the falling piece) and the
    static `shape(id, rot)` (offsets for the NEXT preview).
  - Internals: piece moves (`move`/`rotate`/`softDrop`/`hardDrop`/`stepDown`/`lockPiece`), line handling
    (`clearLines`/`rowFull`/`collapseInto`), `spawn` with top-out detection, a 7-bag randomizer
    (`nextFromBag`) for fair piece order, and `collides` (walls/floor/occupied; rows above the top allowed).

## How it works

The provider (render thread) owns exactly one `TetrisGame` instance. Each frame it drains queued GLFW key
codes into `handleKey(int)`, calls `tick()` once, then reads the public fields and `activeCells()` /
`shape(...)` to draw. `handleKey` returns a signal — `SIGNAL_QUIT_TO_BIOS` tells the host to leave Tetris
(from the game-over screen, or from the pause menu when "Yes/quit" is confirmed) — so the host reacts without
reaching into internals. GLFW key constants are duplicated as private fields here precisely to keep the class
free of the LWJGL import. Gravity is a frame counter: `tick()` increments `tickCount` and calls `stepDown()`
when it reaches `GRAVITY[level]`; manual drops reset that counter so they don't double-step. Locking a piece
writes it into `cells`, clears full lines (scoring by count × level, leveling every 10 lines), and spawns the
next bag piece; a spawn that immediately collides sets `gameOver`.

## Boundaries

- **Depends on:** only `java.util.Random` (and `System.nanoTime` for the seed). Nothing else — no GL, no
  Minecraft, no `bios/`, no other `core/` package.
- **Depended on by:** the presentation host under `bios/` (the Tetris screen), which constructs it, feeds it
  keys, ticks it, and renders its public state. The only cross-thread channel is the provider's
  `ConcurrentLinkedQueue` feeding `handleKey`; per the class doc all access is on the single render thread, so
  the class itself does no synchronization.
- **Invariants:** `core/` never imports `bios/`. Single-thread (render-thread) access only — the absence of
  synchronization is correct *because of* that contract, not in spite of it.
