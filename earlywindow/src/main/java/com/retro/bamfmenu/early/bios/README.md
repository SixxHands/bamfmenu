# early/bios, BIOS host: SPI early window, GL render loop, input, and POST/SETUP shell
## Features
This is the host half of early window. It uses GL-touching and has a Minecraft-aware side. `TerminalWindowProvider` is the
`ImmediateWindowProvider` SPI entry FML discovers on the service layer before mods load. It is built from
scratch, is not a `DisplayWindow` subclass, as `DisplayWindow` draws on a private render loop whose element
list cannot be extended. This would make a text terminal + animated spinner impossible inside it. So create our own window and
reuse only FML's public draw classes (`SimpleBufferBuilder`/`ElementShader`). Plus we also have our own `gfx/RichFont`.
This was originally a ~3409-line god class which has turned into the `screen/`, `transition/`, `gfx/`, `state/` parts. POST and SETUP
currently still live inside it as they are BIOS specific and never have plans to be reused. Im also just lazy.

## Components
- **`TerminalWindowProvider`** is the SPI implementation and central host. The main thread does SPI/window/input,
  one daemon render thread does ALL GL + game state.
  - back to ~3800 lines. Phase 5 (editor + test play), Phase B (on-disk user songs), and the secret
    games (snake/galaga/DOOM) added mouse/scroll input, chart file I/O, the merged jukebox, and the secret hosts. (this needs to be fix oml)
  - lifecycle: `initialize()` parses FML args, loads the BIOS config (`state/BiosCache`), caches the POST
    hardware strings, loads art + the Recovery faces (`core/asset/Art`), creates the window on the main
    thread. `initRender` on the render thread absorbs the GL context, builds shader/font/canvas, starts the
    ~20fps tick. (yuck godclass)
  - `renderFrame` (render thread): draws into an offscreen FBO then Y-flip-blits to the window. Drains the
    per-mode start-request flags, steps/renders overlays, switches on `state` to the active `screen/`, falls
    back to the woven `drawTerminal()` (POST) / `drawSetup()` (SETUP + jukebox + difficulty menu), then
    `drawControlBar()` draws one shared hint bar from `controlHints()`.
  - input (main thread): `onKey`/`onChar` and the mouse/cursor/scroll callbacks only enqueue to the per-mode
    concurrent queues or set volatile flags, never GL or game state. Mouse/scroll enqueue is gated to `Mode.EDITOR`.
  - `enterMode(Mode)`: the SOLE writer of the volatile `state`, written last, after sub fields. Also
    sets the frame period, 16ms for RHYTHM/CUTSCENE/SPECIAL/EDITOR/SNAKE/GALAGA, 33ms for DOOM, 50ms otherwise.
  - hand-off: `setupMinecraftWindow` keeps while `state.holdsHandoff()` or an overlay is up, plays the optional
    `POST_EXIT_GRACE_MS` live-POST beat when `biosUsed`, uses the the render thread via `renderLock`, flips
    `handedOff`, drops the context onto the main thread, frees callbacks and OpenAL, then returns the window handle.
  - `loadingOverlay`: reflectively builds a VANILLA `LoadingOverlay`. NeoForge needs a real `DisplayWindow`.
  - cross-cutting core: `AudioPlayer`, the lead-in (over `transition/impl/LeadIn`), persistence +
    `bankRhythm`, scoring (`scoreKey`/`gradeLetter`), the jukebox (`rebuildJukebox` merges `SongRegistry.SONGS`
    + on-disk `userSongs()`), and chart/song file I/O under `<gameDir>/BAMFOS/songs/` (folder scaffolding
    delegated is under `core/rhythm/SongLoader`).
  - `biosCtx`: the inner anonymous `screen/BiosContext`. Screens reach all of the above through it and never
    see the provider type, the executor, `renderLock`, or GL handles.
- **`Mode`** is the plain old enum read on both threads, the active base screen. POST, SETUP, REPAIR, CUTSCENE, RHYTHM,
  TETRIS, RESULTS, SPECIAL, EDITOR, plus the hidden SNAKE/GALAGA/DOOM wire through here. Replaced the old bag of volatile mode booleans.
  - `state`: exactly one base screen is up, held in one volatile field. That single atomic write is what stops
    the hand-off hold loop ever seeing a blank "no screen up" mid-transition.
  - `holdsHandoff()`: `this != POST`. Every screen except the plain loading terminal delays the Minecraft
    hand-off until the user leaves it.

## How it works
FML instantiates the provider via a SPI and calls `initialize()`. The returned `periodicTick` polls GLFW
events onto the main thread, then render thread ticks `renderFrame` on its own. Control crosses threads one way, through
input enqueues or flags. The render thread drains and steps the active screen then draws. The volatile `state`
is the only thing that keeps the two threads together. At shutdown `setupMinecraftWindow` is blocked while
any screen keeps the hand-off, then takes the GL context back and returns the window, so that the early window
literally BECOMES Minecraft's window.

## Dependencies
Depends on the child layers (`gfx/`, `screen/`, `state/`, `transition/`), the `core/` subtree (audio, tetris,
songs, rhythm, special, tuning, asset), LWJGL (`GLFW`/`GL32C`), and FML's public early-display API
(`ElementShader`, `SimpleBufferBuilder`, `ProgressMeter`, `StartupNotificationManager`,
`ImmediateWindowProvider`). FML depends back through the SPI service file naming `TerminalWindowProvider`, so
the provider stays in a stable package. `core/` should never import `bios/`, all GL + changeable game states
live on the render thread. Any input callbacks are only enqueue/flag, `enterMode` is the ONLY  `state` writer, and
`gfx/TerminalCanvas` is the only class OUTSIDE this provider importing `org.lwjgl.opengl.*`. The jukebox never
lists a USER folder whose id collides with a bundled song (`isReservedSongId`), so that an id never shows twice.

## Child layers
- `host/` is `TerminalWindowProvider` + `Mode` + `BootPhase` (FML's progress labels, passed through verbatim,
  plus the one hand-off line FML never labels) +
  `SetupSizing` (the SETUP / UPDATE AND REPAIR widen rule, pulled out GL-free so it can be tested), plus the
  planned `BiosRuntime` stub.
- `gfx/` is the render-thread GL facade: `TerminalCanvas` (text/popup/box primitives + theme + layout) and
  `RichFont` (the STB-baked outline-font atlas).
- `input/` is planned stubs for the input extraction, empty today.
- `screen/` is the `Screen`/`Overlay`/`BiosContext` interfaces.
- `modes/` is every per-`Mode` screen: cutscene, results, special, rhythm, editor, and the four minigames.
- `overlay/` is `VolumeOverlay` + `PauseOverlay`.
- `state/` persistence: `BiosCache`, obfuscated binary BIOS save blob under `BAMFOS/cache/bios.dat`.
- `transition/` lead-in / phase-timeline helpers (`LeadIn`, `PhaseTimeline`, `Transition`, impls).
