# early/bios/overlay, input-capturing layers composited over the active screen
## Features
The `Overlay` implementations the BIOS host draws *after* the active screen, popup LAYERS rather
than `Mode`s, so a song or a game keeps its frozen frame underneath while a menu floats on top. The two
menus were extracted verbatim from the provider, nothing the user sees changed. The overlays keep no GL or
LWJGL imports (GLFW key codes are duplicated as constants) and reach all shared/persisted
state through `BiosContext`, so the provider stays the single owner of the input queues, volume value,
and lane keybinds. Two behavioral reworks are deferred, NOT done here: F2/volume as a pause sub-item and
BIOS-only, and a later look pass on these functional-not-pretty menus.

## Components
- **`VolumeOverlay`** is the F2 volume popup. `toggle(BiosContext)` opens it
  (pausing the current song) or closes it. If `ctx.inSpecialEvent()` it refuses and calls
  `ctx.flashError()` (red ERROR flash) instead. `step(ctx)` drains its provider-owned input queue on the
  render thread: LEFT/DOWN and RIGHT/UP step the value ±5 via `ctx.setVolume`, ENTER/ESC close.
  `close(ctx)` resumes audio and calls `ctx.save()` to persist. `render(c, ctx)` draws a `VOLUME` popup
  with a `TerminalCanvas.asciiBar(vol, 20)` gauge. The volatile `active` flag exposed via `active()` is
  what the provider's `renderFrame` gating, `onKey` priority chain, and hand-off hold loop read.
- **`PauseOverlay`** is the universal in-game pause menu (ESC from
  RHYTHM/TETRIS/SPECIAL) plus a rhythm-only key-remap sub-screen. `open(ctx)` freezes + pauses audio and
  clears both its own queue and the game queues (`ctx.clearGameInputQueues()`). `close(ctx)` resumes.
  `items(ctx)` is `{Resume, Options, Quit}` only in `Mode.RHYTHM`, else `{Resume, Quit}` (Options =
  remap). `step(ctx)` drains on the render thread, routing each key to `handlePause` or (when in the
  sub-screen) `handleRemap`. Both return `true` when the menu is left so draining stops. `handlePause`
  navigates and dispatches: Resume/Esc -> `close`, Quit -> `ctx.exitCurrentGame()` (leaves the mode,
  closing audio), Options -> enter remap. `handleRemap` captures the next key for a lane, swapping any
  lane already on that key so all lanes stay distinct, then changes the live `int[]` from `ctx.laneKeys()`
  and calls `ctx.save()`. `render(c, ctx)` draws either the `PAUSED` menu or the `REMAP KEYS` list (using
  `LANE_GLYPHS` `{<,v,^,>}` and `ctx.keyName`). Volatile `active` plays the same gating role as above.

- **`RebootOverlay`** is the staged-update farewell at boot, the one overlay that is not a menu: it keeps the
  Minecraft hand-off and then ends the process, so a sync that staged files cannot be joined around. It also
  implements `RepairContext`, because the box, the ten seconds, the 1.5s key grace and every string are the real
  `core.repair.session.RepairSession` posed with `RepairResult.ofSync(0)`, the same farewell the UPDATE AND
  REPAIR window puts up in session. `pending(boolean)` mirrors the engine's `PENDING` event in from
  `UpdaterFeed.pendingSwaps()`; `handingOff()` is the main thread saying the boot is done.
  `step(ctx)` arms only when both are true and `ctx.mode() == Mode.POST` with neither menu open, so a player
  in SETUP or a game keeps their session. `compose()` builds the lines through `RepairFrames.modalLines`,
  `render` hands them to `TerminalCanvas.popup`, `hints()` feeds the bottom bar its `Closing in Ns` row. Two
  Law-5 rules shape the rest: no close until `compose()` has answered once, and `active()` releases itself
  15s after arming, so a wedged render thread boots stale rather than never booting. Its clock and its exit
  are both injectable. That is the only reason a thing that halts has unit tests at all.

## How it works
The provider owns the host loop and the input queues. On the main thread, `onKey` enqueues integer
key codes into each overlay's provider-held `Queue<Integer>` (and decides priority, e.g. an active
overlay captures input before the underlying screen). On the single render thread, the provider calls
each active overlay's `step(ctx)` to drain that queue and update the overlay's own selection state plus
shared state via `BiosContext` (volume, lane keys, audio pause/resume, persistence). Then, after drawing
the active screen, the provider calls the overlay's `render(canvas, ctx)` to composite the popup on top.
The `active()` flag ties it all together: it gates whether the provider routes input to
the overlay, whether the overlay is rendered, and whether the hand-off-to-Minecraft hold loop should wait.
Neither overlay knows about the other or about `Mode` screens, they only talk to `BiosContext` and
`TerminalCanvas`.

## Dependencies
- Depends on the `early.bios.screen` framework (`Overlay`, `BiosContext`), `early.bios.gfx.TerminalCanvas`,
  `early.bios.host.Mode` (PauseOverlay to decide the Options item, RebootOverlay to arm only at POST), and
  `early.core.audio.AudioPlayer` (reached via `ctx.audio()`). RebootOverlay alone also reaches into
  `early.core.repair` for the session, the frames and the key mapping it reuses.
- Depended on by the provider/host (`TerminalWindowProvider`), which constructs each overlay with its
  input queue and drives `toggle`/`open` + `step` + `render` + `active()`.
- `step`/`render` are render-thread only (they change game-visible state and touch the canvas), `onKey`
  only *enqueues* on the main thread.
- The input queue, volume value, and lane keybind `int[]` are provider-owned and changed only through
  `BiosContext`.
- This layer keeps no GL/LWJGL imports (key codes are duplicated constants), consistent with `core/` never
  importing `bios/` and only `TerminalCanvas` touching `org.lwjgl.opengl.*`.
