# early/bios/modes, one `Screen` host per non-shell `Mode`
## Features
Every `Screen` that is not the woven POST+SETUP shell: the rhythm game and its end card, the per-song SPECIAL
session, terminal Tetris, the Recovery intro cutscene, the chart editor, and the hidden Snake + Galaga. The
pre-editor screens were extracted VERBATIM from the provider (3409 -> ~2180 lines), the provider keeps the
SPI, the GL/thread lifecycle, the `renderFrame` switch, and the shell. The standing rule: a non-shell screen
gets its own `Screen` here and is entered FROM the BIOS via an `onKey` event + a `Mode`, POST/SETUP is the one
deliberate exception. We never built the `Map<Mode,Screen>` dispatch, the provider's `switch` already
delegates cleanly. The editor is the newest member.

## Components
Each class implements `bios.screen.Screen` and is a presentation host: pure logic + `TerminalCanvas` drawing,
no GL of its own. Lifecycle and `render` run on the RENDER thread, input arrives from the MAIN thread through
provider-owned queues.

- **`CutsceneScreen`** (`Mode.CUTSCENE`) is the Recovery-Mode intro cutscene. A locked event: no Esc abort, it
  always runs to completion, and it has no input queue.
  - sequence: `wipeFromRight` erases the SETUP drawn under (`ctx.renderSetupUnder()`), a header scramble
    settles on "Unzipping", the face animates synced to the jingle's stabs (`AudioPlayer.superflux()`), a
    full-screen glitch scramble hands off to `ctx.startRhythm()`.
  - timing: `bios.transition.impl.CutsceneTimeline`, shared wall-clock, no freeze. The jingle fires at
    `cutscene.faceStart` via `ctx.playMusic("/songs/recovery/intro.ogg")`.
  - art: the Recovery faces (`ctx.recoveryFaceA/B`), shared with the SETUP achievement panel.
- **`RhythmScreen`** is NOT a file here, the RHYTHM host + view live in `rhythm/` (see Child layers). Other
  screens reach RHYTHM only through `ctx` (`startRhythm`/`exitRhythm`/`startRhythmTest`).
- **`ResultsScreen`** (`Mode.RESULTS`) is a song's end card: score, grade rank, SONG CLEAR / GAME OVER, a NEW
  BEST line. `RhythmScreen` banks the run then pushes the numbers here via `ctx.showResults()`.
  - input: ENTER replays the same song + difficulty (`ctx.startRhythm()`), ESC back to the BIOS
    (`ctx.exitRhythm()`), no auto-bounce.
  - grade: `ctx.gradeLetter(grade)`, "incomplete run" when `grade < 0`.
- **`SpecialScreen`** (`Mode.SPECIAL`) is the host for a song's optional bespoke session ([TAB] / the jukebox
  "Special" row). Owns a `core.rhythm.special.SpecialSession` that RETURNS lines, so this screen keeps zero GL.
  - onEnter: builds `song.newSpecial()`, starts audio (`ctx.startSongAudio`), arms the lead-in, then
    `enterMode(SPECIAL)`. No special, or unreadable audio, falls back to SETUP with a notice.
  - step: honors `ctx.holdingLeadIn()`, reads the audio clock, drains the timestamped `Queue<long[]>` (ESC
    opens the pause menu, the rest goes to `session.handleKey`), exits on `isDone()`. `onExit` routes the
    pause-menu Quit to the same teardown.
- **`TetrisScreen`** (`Mode.TETRIS`, "Enable Mechanics") is the self-contained terminal Tetris cluster, owns a
  `core.tetris.TetrisGame`. Gravity-tick-based, not audio-synced.
  - music, a data-driven JUKEBOX: `onEnter` lists any `/songs/tetrisN.ogg` via `ResourceScan.listResources`,
    plays a random one, waits a random 5-15s gap after a track ends, then plays a DIFFERENT one. None found
    falls back to the single `/songs/tetris/tetris.ogg` theme.
  - step: drains its queue (ESC during play opens the pause menu, `TetrisGame.SIGNAL_QUIT_TO_BIOS` exits),
    ticks gravity, and a new high score persists immediately (`ctx.setTetrisHighScore` + `ctx.save()`).
  - exit: back to SETUP on the Enable Mechanics row (`ctx.exitToSetup(3, …)`).
- **`SnakeScreen`** (`Mode.SNAKE`) is the HIDDEN Snake game, revealed by the `snake` secret (`core.secret` ->
  `ctx.launchGame("snake")`). Owns a `core.secret.snake.game.SnakeGame`. A pure Easter egg, no audio, no high score.
  - timing: advances on its own move-tick timer (~70-130ms), decoupled from the frame rate, renders every frame.
  - signature touch: the body wears the cutscene's GLITCH shimmer, fresh `Glitch.noise(2)` per segment per
    frame, head gold, body bright.
  - exit: ESC to SETUP on the Safe-For-Retro row (`ctx.exitToSetup(2, …)`), ENTER retries at game over.
- **`GalagaScreen`** (`Mode.GALAGA`) is the HIDDEN Galaga game, revealed by the `galaga` secret
  (`ctx.launchGame("galaga")`). Owns a `core.secret.galaga.game.GalagaGame`. Two differences from Snake:
  - input: a `Queue<int[]>` of `{key, action}` PRESS/RELEASE pairs, enqueued by an `onKey` branch placed
    BEFORE the press/repeat guard, so it tracks HELD left/right + per-press fire, the controls a shooter needs.
  - time-stepped: `step` passes the real frame delta to `GalagaGame.tick(dt)` for smooth motion.
  - exit: ESC to SETUP on the Safe-For-Retro row, ENTER retries.

## How it works
The provider hosts: the MAIN thread only enqueues into the per-mode queues or sets volatile flags, the RENDER
thread's `renderFrame` switch calls the current screen's `step` then `render`. A screen enters by
`ctx.enterMode(Mode)` from its `onEnter`, the single atomic `state` write, which also sets the frame period
(60fps for CUTSCENE/SPECIAL/EDITOR/SNAKE/GALAGA, 50ms for TETRIS). It leaves by routing back through `ctx`
(`exitRhythm`, `exitToSetup`, `enterMode(SETUP)`). The queues are shared by REFERENCE with the provider, so an
overlay's defensive `input.clear()` still reaches the right screen. Everything stateful comes through
`BiosContext`, drawing only through `TerminalCanvas`. The RHYTHM<->RESULTS, RHYTHM<->SPECIAL, and
EDITOR<->RHYTHM (Test Play) hand-offs all flow through `ctx`, the screens never reference each other.

## Dependencies
- Depends on the `screen/` interfaces (`Screen`, `BiosContext`), `TerminalCanvas`, `Mode`,
  `bios.transition.impl.CutsceneTimeline`, and the headless `core.*` packages (tetris, rhythm + special,
  audio, asset, the secret games, the editor's `core.editor.*`).
- One-way as always: `bios/` may import `core/`, `core/` NEVER imports `bios/`.
- Depended on by the provider's `renderFrame` switch (the only constructor/caller of these screens) and the
  overlays that clear their input queues.
- RENDER thread only for lifecycle/`render`/game-state changes, MAIN thread only enqueues.
- No `org.lwjgl.opengl.*` here, GLFW codes are hand-duplicated as `int`s to stay LWJGL-free.
- Every pre-editor screen was extracted verbatim, behavior preserved from the pre-split provider.

## Child layers
- `rhythm/`, the RHYTHM cluster: `RhythmScreen` (the host: lifecycle + input + chart ingestion) plus its view
  `RhythmRenderer` and the optional `SongHeaderHud`.
- `editor/`, the chart editor: one `Screen` (`EditorScreen`, `Mode.EDITOR`), three views (song browser ->
  difficulty browser -> horizontal timeline, the timeline its own `TimelineEditorView`).
