# early/bios/screen, Screen/Overlay interfaces, BiosContext host
## Features
The provider is what keeps GL, the render thread, `renderFrame`,
`onKey`/`onChar`, and `state`. Each per-mode behavior is a `Screen`, floating layers originally from `TerminalWindowProvider` are now in
`Overlay`s. In this folder is just the three components that make up the screen, content in the mod and overlay do the actual visible work.

## Components
- **`Screen`** one per `Mode` (POST and SETUP are still apart of `TerminalWindowProvider`). `mode()` keys dispatch,
  `onEnter`/`onExit` contain the lifecycle for keypresses. `step(ctx)` iis the logic that drains the screen's own input queue,
  and takes no canvas on purpose, as the screen physically cannot draw off the render thread. `render(canvas, ctx)`
  draws through the facade only. `controlHints(ctx)` is part of the screen's row/bottom bar.
- **`Overlay`** is for layers composited OVER the active screen (volume, pause, whatever else will be over the main screen in the future), not `Mode`s. `active()` freezes the
  base screen and captures input, `step`/`render` mirror `Screen`.
- **`BiosContext`** is what screens call back into. The provider implements it, screens never hold the provider.
  Groups:
  - transitions: `enterMode`/`mode()`. The provider is the ONLY `state` writer; screens SHOULD ONLY EVER request `state`s.
  - audio: core `audio`/`closeAudio`/`startSongAudio(SongDef)`/`playMusic` for the rhythm engine.
  - lead-in + special-event lock: `armLeadIn`/`holdingLeadIn`/`clearLeadIn`/`inSpecialEvent`.
  - overlays: `volumeMenuOpen`/`pauseMenuOpen`/`openPauseMenu`/`clearGameInputQueues`.
  - persistent `BiosCache`: `save`, `tetrisHighScore`, `laneKeys`/`keyName`, `safeForRetro`.
  - jukebox: `currentSong`/`currentDifficulty`/`setCurrentSong`; `bamfosDir()`.
  - rhythm results/screen transitions: `startRhythm`/`exitRhythm`/`bankRhythm`/`showResults`/`gradeLetter`.
  - SETUP nav + volume: `setNotice`/`exitToSetup`/`exitCurrentGame`; `volume`/`setVolume`/`flashError`; `frame()`.
  - cutscene: `renderSetupUnder`, `recoveryFaceA`/`recoveryFaceB`. (this needs proper categorization and refactoring, its currently completely owned by the recovery cutscene)
  - editor: chart I/O `loadChartText`/`saveChart`; mouse `editorLogicalX/Y` + `editorMouseWinX/Y`; test play
    `startRhythmTest`/`consumeTestChart`/`testStartMs`/`testApproachMs`/`editorTestActive`/`returnToEditor`;
    user songs `userSongs`/`forkSong`/`normalizeUserSong`/`createNewSong`.

## How it works
In order, each render-thread frame the provider runs is `screen.step(ctx)`, `screen.render(canvas, ctx)`, any `active()`
overlay, then the bottom bar. Input lands on the main thread (`onKey`/`onChar`) and only enqueues or sets
inputs. Mode changes go through `ctx.enterMode`, the provider writes `state` last and once, so the hand-off/hold 
loop never is a half-built mode.

## Dependencies
Depends on `Mode`, `TerminalCanvas`, and `core` value types in `BiosContext` (`AudioPlayer`,
`SongDef`/`SongDifficulty`/`Note`). No LWJGL, no Minecraft whatsoever. Invariants: `step()` takes no canvas, `enterMode`
is the only `state` writer, screens hold a `BiosContext` and never anything for provider. POST/SETUP are (currently) 
intentionally not extracted, as they are the BIOS shell.

## Child layers
None anymore, this folder is just the interfaces. The implementations live beside it: `bios/modes/` (the
screens) and `bios/overlay/` (the overlays).
