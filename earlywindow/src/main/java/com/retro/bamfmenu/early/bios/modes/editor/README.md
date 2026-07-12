# early/bios/modes/editor, the chart editor screen
## Features
The presentation host for the in-house chart editor: the `Screen` that owns `Mode.EDITOR`.
It turns keyboard/mouse/scroll input into edits on a `core/editor.EditChart` and paints that chart as a
retro-ASCII timeline on the `TerminalCanvas`. All headless logic (the editable chart model, the time<->pixel map,
and the snapping grid) lives in `core/rhythm/editor`. This layer is the thin, stateful UI around it plus the
`core/rhythm` engine (for `Note`, fall-speed defaults, and Test Play). It is a three-view state machine: a SONG
browser, a DIFFICULTY browser, and the horizontal TIMELINE editor (4 lanes top->bottom, time left->right). The bulky
TIMELINE editor is its OWN class, `TimelineEditorView`, extracted so no single class owns all three screens.
`EditorScreen` is the `Screen` seam + the two browser views + the coordinator that owns the active `view`. Songs
come from `SongRegistry.SONGS` (bundled) plus `ctx.userSongs()` (on-disk BAMFOS forks/drop-ins). Charts are
read/written through `BiosContext`, never opened directly.

## Components
- **`EditorScreen`** is the coordinator + `Screen` (`mode()` = `Mode.EDITOR`, `onEnter` / `onExit`,
  `step`, `render`, `controlHints`). It OWNS the active `int view` (`VIEW_SONGS` / `VIEW_DIFFS` / `VIEW_EDIT`). `step`
  and `render` dispatch the two browser views to its own `stepX` / `renderX` pairs and `VIEW_EDIT` to the `timeline`.
  It constructs the one `TimelineEditorView` (passing `this` + the three queues) and bridges into it: `openForEdit`
  forks/normalizes then calls `timeline.open(...)`, the timeline calls back via `returnToDiffBrowser` / `editing()`.
  To stay free of the LWJGL import, GLFW key codes/actions are hard-coded `static final int`s. Input is drained on the render thread from the three provider-owned queues (`keyIn`, `mouseIn`,
  `scrollIn`) passed to the constructor (and shared with the timeline view).
- **`TimelineEditorView`** is the horizontal timeline editor (the bulk; package-private, NOT a `Screen`, driven by
  the parent). Holds ALL the live edit state: `EditChart chart`, `TimeMap map`, `TimingGrid grid`, `cursorMs`,
  `selected`/`dragNote`, preview/playhead fields, and the cached view-geometry ints (`viewLaneTop`, `viewBandH`, ...)
  that `render` recomputes and the mouse-hit code reads. The parent drives it via `open` / `resumeAfterTest` / `step`
  / `render` / `controlHints`. On ESC/discard it returns control through `parent.returnToDiffBrowser`.
- **Song browser** (`stepSongs` / `renderSongs`) lists default songs then user songs, each with a `[n/3 charts]`
  count from `songChartedCount`, plus a trailing "Create New Song" row (`isCreateRow`). ENTER on a song checks
  `refuseReason` (a user folder needs exactly one OGG, `null`/`SongLoader.MULTI_OGG` are refused) then moves to
  diffs. ENTER on the create row arms `createConfirm` and `doCreateNew` calls `ctx.createNewSong()`. ESC ->
  `ctx.exitToSetup(5, "")` (the BIOS "Open Chart Editor" row).
- **Difficulty browser** (`stepDiffs` / `renderDiffs`) picks among `DIFFS` (`EASY`/`NORMAL`/`HARD`, `SPECIAL` is
  not chartable), showing per-diff `diffNoteCount`. ENTER -> `openForEdit`. `R` arms `resetConfirm` -> `doReset`
  writes a 0-note `Chart` via `ChartIO.write` + `ctx.saveChart`. ESC -> back to songs.

## How it works
`onEnter` clears the queues, rebuilds `browseSongs` via `recomputeSongList` (bundled `SongRegistry.SONGS` first, then
`ctx.userSongs()`), pre-selects `ctx.currentSong()`, and enters `Mode.EDITOR`. `openForEdit` resolves the target
folder: a bundled default is forked (`ctx.forkSong`) and the browser rebinds to the copy (the "object permanence"
fix so later diffs edit the same fork), a user song is `ctx.normalizeUserSong`'d (folder renamed to its OGG). It then
hands the resolved song+difficulty to `timeline.open`, which loads chart text (`ctx.loadChartText`) into
`EditChart.fromText`, builds a `TimingGrid` from the chart BPM, and starts paused audition audio
(`ctx.startSongAudio`, capturing `durationSeconds`/`waveform`). `EditorScreen` then flips `view` to `VIEW_EDIT`.

The timeline's `step` drains scroll (`map.panPixels`), mouse (`handleMouse`: hit-test via `findNoteAt`/`laneForY`, left =
select/drag or `chart.toggle`, right = delete, minimap drag = `panViewToOverviewX`), then keys (`handleKey`): arrows
`place` taps, `,`/`.` step the snapped cursor (Shift nudges `grid.offsetMs`), `[`/`]` cycle the snap divisor, `-`/`=`
zoom, `Q`/`W` edit BPM, `9`/`0` change per-chart fall speed (`setApproach`, clamped, persisted into the chart),
`G` toggles snap, Delete/Backspace `chart.removeNearest`. Space (`togglePreview`) auditions audio with a sweeping
gold playhead. `P`/`O` (`testPlay`) hand a copy of `chart.notes` to the real game via `ctx.startRhythmTest`, and
`resumeAfterTest` re-enters without reloading the chart. `S` (`save`) writes `chart.toText()` through
`ctx.saveChart`. ESC (`exit`) guards unsaved edits with a `pending` (`P_EXIT`) ENTER-discard/`S`-save popup, else
`backToDiffs` (closes audio, then `parent.returnToDiffBrowser` refreshes counts).

The timeline's `render` lays out the graded-ASCII waveform overview, the beat/measure `drawGrid`, `[]`/`[==]`
notes in `Art.LANE_COLORS`, cursor/start/end bars, and a status HUD.

## Dependencies
- Lives in `bios/` (the presentation host) and is allowed to depend on `core/`: it uses
  `core/rhythm/editor` (`EditChart`, `TimeMap`, `TimingGrid`), `core/rhythm` (`Note`,
  `DEFAULT_APPROACH_MS`, `SongDef`, `Difficulty`, `SongRegistry`, `SongLoader`), `core/rhythm.chart` (`Chart`,
  `ChartIO`), `core/asset` (`Art`), and `core/audio` (`AudioPlayer`).
- It never reaches the disk, the audio device, or other modes directly: every side effect (chart I/O,
  fork/normalize/create, audio start/stop, rhythm Test Play, mode transitions, mouse-coordinate mapping) goes
  through the `BiosContext` interface, which the provider implements.
- This respects the core invariant in the only direction that matters: `core/` knows nothing of this screen.
  This screen orchestrates `core/` types behind the host.
- All rendering is `TerminalCanvas` glyph drawing (pure LWJGL/GL via the canvas), no Minecraft classes,
  keeping it usable in the pre-Minecraft early window.
