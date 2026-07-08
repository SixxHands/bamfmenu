# early/bios/screen/mode — one `Screen` host per non-shell `Mode` (the BIOS sub-games / sub-states)
## Intent
This layer holds the per-`Mode` screens that are NOT part of the woven POST+SETUP BIOS shell: the rhythm game
and its end card, the optional per-song SPECIAL session, terminal Tetris, the Recovery-Mode intro cutscene,
the in-house chart editor, and the hidden Snake + Galaga games. They came out of the .75 decomposition (steps 3–9): each reusable
sub-game was extracted *verbatim* from the monolithic `TerminalWindowProvider` (3409 → ~2180 lines) into its
own `Screen` so the provider keeps only the SPI + GL/thread lifecycle + the `renderFrame` dispatch switch + the
BIOS shell. The standing architectural rule (owner) is that any screen that is not the BIOS shell gets its own
`Screen` class here and is entered FROM the BIOS via an `onKey` event + a `Mode`; the POST/SETUP shell is the
one deliberate exception that stays in the provider. The full `Map<Mode,Screen>` dispatch was intentionally NOT
built (step 11) — the provider's `switch` already delegates cleanly. The editor is the newest member.

## Components
Every class here implements `com.retro.bamfmenu.early.bios.screen.Screen` and is a presentation host: pure
logic + `TerminalCanvas` drawing, **no GL of its own** (raw-GL primitives live on the canvas). Lifecycle and
`render` run on the single daemon RENDER thread; `onKey`/`onChar`/mouse/scroll enqueue from the MAIN thread into
the provider-owned queues these classes drain. `enterMode(Mode)` (via `ctx`) is the sole writer of the volatile
`state`.

- **`CutsceneScreen`** (`mode()` = `Mode.CUTSCENE`) — the Recovery-Mode intro cutscene (STEP 9): an opening
  right-to-left `c.wipeFromRight` that erases the SETUP screen drawn under via `ctx.renderSetupUnder()`, a blank
  hold, a header text-scramble (`scrambleText`) settling on "Unzipping", a face animation
  (blank→noise→eyes→noise→smile, `faceForStabs`/`noiseLike`) synced to the recovery jingle's stabs, then a
  full-screen glitch scramble (`drawFullScreenScramble`) that hands off to the song. Driven by
  `bios.transition.impl.CutsceneTimeline` (shared wall-clock, no freeze). A locked event: no Esc abort, always
  runs to completion; `step` fires the jingle at `cutscene.faceStart` via `ctx.playMusic("/songs/recovery/intro.ogg")`,
  detects stabs via `AudioPlayer.superflux()` (`detectStabs`), and calls `ctx.startRhythm()` at `cutscene.done()`.
  Reads the Recovery face art (`ctx.recoveryFaceA/B`), shared with the SETUP achievement panel. No input queue.
- **`RhythmScreen`** — note: this is **not** a direct file here; the RHYTHM host + its view live in the
  `rhythm/` subfolder (see Child layers). The other screens reach RHYTHM only through `ctx` (`startRhythm`,
  `exitRhythm`, `startRhythmTest`).
- **`ResultsScreen`** (`mode()` = `Mode.RESULTS`) — a song's end card (STEP 6): score + grade rank + SONG
  CLEAR / GAME OVER, with a NEW BEST line. `RhythmScreen` banks the run then pushes the numbers here via
  `ctx.showResults()` → `show(completed, score, grade, newBest)`. `step` drains its `Queue<Integer> input`:
  ENTER/KP-ENTER → `ctx.startRhythm()` (replay same song+difficulty), ESC → `ctx.exitRhythm()` (back to BIOS);
  no auto-bounce. `render` uses `ctx.gradeLetter(grade)` for the rank (or "incomplete run" when `grade < 0`).
  `controlHints` reflect Retry vs Play Again (+ F2 Volume). GLFW key codes duplicated as `int`s (no LWJGL import).
- **`SpecialScreen`** (`mode()` = `Mode.SPECIAL`) — host for a song's optional bespoke gameplay session
  ([TAB] / the jukebox "Special" row, STEP 3). Owns a `core.special.SpecialSession` that RETURNS lines, so
  this screen keeps zero GL. `onEnter` builds `song.newSpecial()`, plays the song audio via
  `ctx.startSongAudio`, arms the lead-in (`ctx.armLeadIn`), then `enterMode(SPECIAL)`; falls back to SETUP (with
  `ctx.setNotice` on unreadable audio) if there is no special. `step` honors `ctx.holdingLeadIn()`, reads the
  audio clock, drains the timestamped `Queue<long[]> input` (ESC press → `ctx.openPauseMenu()`, the rest →
  `session.handleKey`), steps the session, and `exit`s on `isDone()`. `onExit` routes the pause-menu Quit to the
  same teardown.
- **`TetrisScreen`** (`mode()` = `Mode.TETRIS`, "Enable Mechanics", STEP 4) — the self-contained terminal
  Tetris cluster; owns a `core.tetris.TetrisGame`. Gravity-tick-based, not audio-synced. Music is now a
  **data-driven JUKEBOX**: `onEnter` builds a playlist via `SongLoader.listResources("songs", "tetris\\d+\\.ogg")`
  (any `/songs/tetrisN.ogg`, OGG-only), plays a random one (`pickTrack`/`startTrack`), and `updateMusic` waits a
  random 5–15s gap (`GAP_MIN_MS`/`GAP_MAX_MS`) after a track ends before playing a *different* one; if no
  `tetrisN.ogg` are found it falls back to the single `/songs/tetris/tetris.ogg` theme. `step` drains
  `Queue<Integer> input` (ESC during play → `ctx.openPauseMenu()`; `TetrisGame.SIGNAL_QUIT_TO_BIOS` → exit),
  `tick()`s gravity while playing, and `commitHighScore` persists a new high score immediately via
  `ctx.setTetrisHighScore` + `ctx.save()`. `exit` returns to SETUP on the Enable Mechanics row via
  `ctx.exitToSetup(3, …)`. `render` draws the title, bordered playfield (locked cells one pass, active piece
  overlaid in gold), the side panel (SCORE/LEVEL/LINES/HIGH/NEXT), and the pause / game-over popups, sizing the
  logical canvas so all `TetrisGame.ROWS` fit.
- **`SnakeScreen`** (`mode()` = `Mode.SNAKE`) — the HIDDEN Snake game, revealed by the `snake` secret (the
  `core.secret` system → `ctx.launchGame("snake")` → `snakeStartRequested`). Owns a `core.snake.SnakeGame`; drains
  `Queue<Integer> input`, advances on its own move-tick timer (`moveIntervalNanos`, ~70–130ms, decoupled from the
  frame rate), and `render`s every frame. Signature touch: the snake body is drawn with the cutscene's GLITCH
  shimmer — each segment is fresh `core.asset.Glitch.noise(2)` per frame (head gold, body bright) — over a solid grid
  + `()` food. ESC `exit`s to SETUP on the Safe-For-Retro row (`ctx.exitToSetup(2, …)`); ENTER retries at game over.
  No audio, no persisted high score (a pure Easter egg). GLFW codes duplicated as `int`s (no LWJGL import).
- **`GalagaScreen`** (`mode()` = `Mode.GALAGA`) — the HIDDEN Galaga game, revealed by the `galaga` secret
  (`ctx.launchGame("galaga")` → `galagaStartRequested`). Owns a `core.galaga.GalagaGame`. Two differences from
  Snake: (1) its queue is `Queue<int[]>` of `{key, action}` (PRESS/RELEASE) — enqueued by an `onKey` branch placed
  BEFORE the press/repeat guard — so it tracks HELD left/right + per-press fire, the controls a shooter needs; (2)
  it is **time-stepped** — `step` passes the real frame delta to `GalagaGame.tick(dt)` for smooth motion. `render`
  draws the field box, the player ship (`/^\`, two when dual), the enemy formation (`<@>` boss / `>W<` butterfly /
  `vov` bee), bullets, the tractor beam, a held captive beside its boss, the SCORE/WAVE/SHIPS HUD, the STAGE banner,
  and the game-over popup. ESC `exit`s to SETUP on the Safe-For-Retro row; ENTER retries. GLFW codes inlined.

## How it works
The provider is the host: `onKey`/`onChar`/mouse/scroll run on the MAIN thread and only enqueue into the
per-mode queues (`tetrisInput`, `specialInput`, the results queue, the editor's key/mouse/scroll queues) or set
volatile flags; the RENDER thread's per-frame `renderFrame` switch dispatches to the current mode's screen
`step` then `render`. Each screen is entered by calling `ctx.enterMode(Mode)` from its `onEnter` (the single
atomic `state` write that also sets the frame period — 60fps for CUTSCENE/SPECIAL/EDITOR/SNAKE/GALAGA, 50ms for TETRIS) and
leaves by routing back through `ctx` (`exitRhythm`, `exitToSetup`, or `enterMode(SETUP)`). The input queues are
shared by reference with the provider so an overlay's defensive `input.clear()` (e.g. the pause overlay) still
reaches the right screen. All these screens consume `BiosContext` for audio, persistence, lead-in, transitions,
the song/difficulty, theme colours, the canvas string helpers, editor chart I/O, and editor mouse→logical
mapping — they never touch GL except through `TerminalCanvas`. The RHYTHM↔RESULTS, RHYTHM↔SPECIAL, and
EDITOR↔RHYTHM (Test Play) hand-offs flow entirely through `ctx` (`showResults`, `startRhythm`, `exitRhythm`,
`startRhythmTest`), so the screens never reference each other directly.

## Boundaries
- **Depends on:** `bios.screen.Screen` (the contract), `bios.screen.BiosContext` (everything stateful/host),
  `bios.gfx.TerminalCanvas` (all drawing + raw-GL prims like `wipeFromRight`/`popup`), `bios.Mode`,
  `bios.transition.impl.CutsceneTimeline`, and the headless **`core.*`** packages (`core.tetris.TetrisGame`,
  `core.special.SpecialSession`, `core.song.SongDef`/`Difficulty`/`SongLoader`/`SongRegistry`,
  `core.audio.AudioPlayer`, `core.asset.Art`/`Glitch`, `core.snake.SnakeGame`, `core.galaga.GalagaGame`, plus the editor's `core.editor.*` and `core.rhythm.*`/`chart.*`).
  This is a `bios/` (presentation/host) layer, so importing `core/` is allowed; `core/` must NEVER import `bios/`.
- **Depended on by:** the provider's `renderFrame` dispatch switch (the only constructor/caller of these
  screens) and, indirectly, the `bios.screen.overlay` layers that clear these screens' input queues.
- **Invariants:** RENDER thread only for lifecycle/`render`/all game-state mutation; MAIN thread only
  enqueues. No `org.lwjgl.opengl.*` import here (`TerminalCanvas` is the sole GL importer). GLFW key/action/mod
  codes are hand-duplicated as `int` constants (the `TetrisGame` convention) to keep these classes LWJGL-free.
  Each pre-editor screen was extracted *verbatim* — behavior is preserved from the pre-split provider.

## Child layers
- `rhythm/` — the RHYTHM mode cluster: `RhythmScreen` (the host: lifecycle + input + chart ingestion via the
  `Charter` seam) plus its view `RhythmRenderer` (all `draw*`) and the optional `SongHeaderHud`; see its README.
- `editor/` — the chart EDITOR: a single `Screen` with three views
  (song browser → difficulty browser → horizontal timeline). It browses `SongRegistry.SONGS` + on-disk
  `ctx.userSongs()`, forks a bundled song or normalizes a user song before editing, loads/edits an `EditChart`
  over a `TimingGrid`/`TimeMap`, auditions audio (`previewing`/playhead), and hands a COPY of the chart to the
  real game via `ctx.startRhythmTest` (Test Play, resumed by `resumeAfterTest`). Saves `.chart` files through
  `ctx.saveChart`. Three input queues: `keyIn`/`mouseIn`/`scrollIn`. The bulky timeline is its own
  `TimelineEditorView` (`EditorScreen` is the coordinator + the two browsers); see its README.
