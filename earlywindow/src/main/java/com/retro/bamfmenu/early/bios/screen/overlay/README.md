# early/bios/screen/overlay — host-composited input-capturing layers drawn over the active screen
## Intent
These are the two `Overlay` implementations that the BIOS host draws *after* the active screen, as
popup LAYERS rather than as `Mode`s — so a song or a game can keep its frozen frame underneath while a
menu floats on top. Both were extracted **verbatim from the provider** during the .75 split
(`VolumeOverlay` = STEP 5a, `PauseOverlay` = STEP 5b), preserving behavior; the goal was to get reusable
presentation logic out of `TerminalWindowProvider` and into `early.bios.screen.overlay` without changing
what the user sees. The overlays deliberately keep no GL or LWJGL imports (GLFW key codes are duplicated
as constants, the "TetrisGame convention") and reach all shared/persisted state through `BiosContext`, so
the provider stays the single owner of the input queues, volume value, and lane keybinds. (Two
behavioral reworks are noted in-code as *deferred*, NOT done here: moving F2/volume to be a pause sub-item
and BIOS-only is plan ; prettying these functional-not-pretty menus is a later look pass.)

## Components
- **`VolumeOverlay`** (implements `Overlay`) — the F2 volume popup. `toggle(BiosContext)` opens it
  (pausing the current song) or closes it; if `ctx.inSpecialEvent()` it refuses and calls
  `ctx.flashError()` (red ERROR flash) instead. `step(ctx)` drains its provider-owned input queue on the
  **render thread**: LEFT/DOWN and RIGHT/UP step the value ±5 via `ctx.setVolume`, ENTER/ESC close.
  `close(ctx)` resumes audio and calls `ctx.save()` to persist. `render(c, ctx)` draws a `VOLUME` popup
  with an `TerminalCanvas.asciiBar(vol, 20)` gauge. Volatile `active` flag exposed via `active()` is what
  the provider's `renderFrame` gating, `onKey` priority chain, and hand-off hold loop read.
- **`PauseOverlay`** (implements `Overlay`) — the universal in-game pause menu (ESC from
  RHYTHM/TETRIS/SPECIAL) plus a rhythm-only key-remap sub-screen. `open(ctx)` freezes + pauses audio and
  clears both its own queue and the game queues (`ctx.clearGameInputQueues()`); `close(ctx)` resumes.
  `items(ctx)` is `{Resume, Options, Quit}` only in `Mode.RHYTHM`, else `{Resume, Quit}` (Options =
  remap). `step(ctx)` drains on the **render thread**, routing each key to `handlePause` or (when in the
  sub-screen) `handleRemap`; both return `true` when the menu is left so draining stops. `handlePause`
  navigates and dispatches: Resume/Esc → `close`, Quit → `ctx.exitCurrentGame()` (leaves the mode,
  closing audio), Options → enter remap. `handleRemap` captures the next key for a lane, swapping any
  lane already on that key so all lanes stay distinct, then mutates the live `int[]` from `ctx.laneKeys()`
  and calls `ctx.save()`. `render(c, ctx)` draws either the `PAUSED` menu or the `REMAP KEYS` list (using
  `LANE_GLYPHS` `{<,v,^,>}` and `ctx.keyName`). Volatile `active` plays the same gating role as above.

## How it works
The provider owns the host loop and the input queues. On the **main thread**, `onKey` enqueues integer
key codes into each overlay's provider-held `Queue<Integer>` (and decides priority — e.g. an active
overlay captures input before the underlying screen). On the single **render thread**, the provider calls
each active overlay's `step(ctx)` to drain that queue and mutate the overlay's own selection state plus
shared state via `BiosContext` (volume, lane keys, audio pause/resume, persistence). Then, after drawing
the active screen, the provider calls the overlay's `render(canvas, ctx)` to composite the popup on top.
The `active()` flag is the contract that ties it together: it gates whether the provider routes input to
the overlay, whether the overlay is rendered, and whether the hand-off-to-Minecraft hold loop should wait.
Neither overlay knows about the other or about `Mode` screens; they only talk to `BiosContext` and
`TerminalCanvas`.

## Boundaries
Depends on the `early.bios.screen` framework (`Overlay`, `BiosContext`), `early.bios.gfx.TerminalCanvas`,
`early.bios.Mode` (PauseOverlay only, to decide the Options item), and `early.core.audio.AudioPlayer`
(reached via `ctx.audio()`). Depended on by the provider/host (`TerminalWindowProvider`), which
constructs each overlay with its input queue and drives `toggle`/`open` + `step` + `render` + `active()`.
Invariants: **render-thread only** for `step`/`render` (they mutate game-visible state and touch the
canvas); `onKey` only *enqueues* on the main thread; the input queue, volume value, and lane keybind
`int[]` are **provider-owned** and mutated only through `BiosContext`; this layer holds **no GL/LWJGL
imports** (key codes are duplicated constants) — consistent with `core/` never importing `bios/` and only
`TerminalCanvas` touching `org.lwjgl.opengl.*`.
