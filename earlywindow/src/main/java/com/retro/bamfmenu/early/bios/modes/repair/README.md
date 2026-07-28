# early/bios/modes/repair, the UPDATE AND REPAIR host + the SETUP row that opens it
## Features
The presentation half of the second BIOS screen (`Mode.REPAIR`). Two classes, both small on purpose: the logic,
the copy and the layout all live in `core/repair`, so this folder is a `Screen` that draws a finished frame and a
tiny string swap for the SETUP row that leads to it.

The screen looks identical to SETUP because it IS SETUP's shape: the same header, the same rule, the same
two-column box with 36 columns on the left, the same bottom control bar, the same short-window widen rule. The
left box is the row list, the right box is that row's help and state, and a page (optional mods, the rollback
picker, notes, status) takes over the right box exactly the way the jukebox song list does.

## Components
- **`RepairEntry`** is the SETUP row-0 swap. `ROW_KEY` keeps `"Roll it back to BAMFOS v1"`, and the provider's
  `SETUP_OPTIONS[0]` reads that constant, so there is ONE copy of the string every `opt.equals(...)` matches on.
  Only the DRAWN label, help and state line change when `OpsBridge.present()` is true. Nothing is deleted: with
  no shim in `mods/` the row is exactly the joke it always was. Its 2-argument overloads take presence as a
  parameter, which is what the tests and the offline renderer use to pose both worlds.
  - the reason the constant matters: the two were independent copies matched by VALUE, and the comment over
    SETUP_OPTIONS invites free edits, so retitling the joke row made this whole window unreachable on every
    install with nothing thrown, nothing logged and every test still green.
- **`RepairScreen`** (`Mode.REPAIR`) is the host: input queue drain, the one daemon worker, the three atomics
  the worker answers through, and the draw. It implements `core.repair.api.RepairContext`, the same arrangement
  `SecretContext` uses (the session decides, the host does).
  - the worker: one thread, one job at a time. Every op is HTTP plus file I/O, and running one where frames are
    drawn would freeze the terminal for seconds on a screen whose whole job is looking alive. The worker only
    writes into `readState` / `readReleases` / `actionResult`; the render thread drains them, so the session is
    single-owner and needs no locks.
  - the widen rule is NOT a copy: `ctx.biosBodyWidth(reservedRows)` hands back the width `drawSetup` itself
    sizes off, so the decision flips at the same window shape for both screens and cannot drift. The box
    COLUMNS are a different story: `RepairBox.LEFT_INNER` is 36 and `drawSetup` still keeps its own local 36,
    so a change to the box shape has to be made in two places until the provider extraction lands.
  - tones map to the live theme, so "You're Blue Now" recolours this window with everything else. ALERT borrows
    the red the SETUP fault panel already uses.

## How it works
SETUP row 0's ENTER sets the provider's `repairStartRequested` (only when `RepairEntry.armed()`), the render
thread drains that flag and calls `onEnter`, which builds the registry on first open, makes a fresh session, asks
the worker for a snapshot and calls `ctx.enterMode(Mode.REPAIR)`. From then on `renderFrame`'s `case REPAIR`
steps then draws, `onKey` enqueues raw GLFW codes, and `controlHints` comes from the session.

`step` drains the worker, stamps the live clock plus `ctx.updaterSyncActive()` onto the snapshot (that flag is
the boot lockout's own signal, reused rather than reinvented), then feeds keys to the session. `render` pushes
`ctx.updaterActivity()` every frame before it builds one, pushed rather than pulled because the session has no clock
and no idea when the engine moves to the next file, while the provider is already tailing that feed for the boot
terminal, so both surfaces read the one source. `leave(notice)` drops the session and routes back through
`ctx.exitToSetup(0, notice)`, landing on the row the window hangs off.
While the worker keeps an action the session refuses BACK, so ESC cannot hand off to Minecraft with the engine
still staging files.

## Dependencies
- Depends on `bios.screen.Screen` + `BiosContext`, `TerminalCanvas`, `Mode`, `core.asset.Art` (the one red), and
  the whole headless `core.repair` tree. One-way as always: `bios/` may import `core/`, never the reverse.
- Four `BiosContext` seams exist for this screen: `drawBiosHeader` and `biosBodyWidth` (so the header art and
  the widen decision are the provider's, not a second copy of the wordmark math), `updaterSyncActive`, and
  `updaterActivity`.
- The provider's diff for this whole window is the row-0 swap at its four draw sites, the ENTER dispatch, and
  the per-mode boilerplate every other screen already has (field, queue, start flag, `renderFrame` case, `onKey`
  branch, `controlHints` case). The provider extraction stays a separate job.
- No `org.lwjgl.*` here; GLFW codes stay in `core.repair.input.RepairKeys` as ints.
- `RepairEntry` must stay import-clean (`OpsBridge` only), because the offline renderer javac's it on its own.
