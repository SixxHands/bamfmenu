# early/core/repair, the data-driven UPDATE AND REPAIR window: one folder per menu row
## Features
The whole second BIOS screen, minus the pixels. It is the user-facing half of the updater: update
status, verify and repair, sync now, optional mods, release notes, roll back a version, clear the release lock.
Data-driven by drop-in with the same self-enforcing shape the secrets use: each row is a FOLDER under `items/`
with its declaring `RepairDef` class at the folder root, and anything bigger (its page) lives in a subfolder
where discovery cannot see it. `RepairRegistry` scans `.class` files exactly one level down. No central list,
no switch, no registration.

The load-bearing decision: **every string and every rule lives here, not in the screen.** `RepairSession` turns
(snapshot + selection + keys) into what the window IS, `view/RepairFrames` turns that into a finished
`RepairFrame` of cells and tones, and `bios/modes/repair/RepairScreen` only multiplies columns by `charWidth`
and draws. That is what makes the window unit-testable and what lets `tools/repair_render.py` javac these
classes and rasterise every state offline, so the copy can be read without launching the game (its exit code is
the geometry gate a screenshot cannot give you: 3 means a box ran into the bottom control bar).

Two rules run through all of it. **Nothing decorative with nothing behind it:** a row's availability and its
state line are lambdas over the live snapshot, so a row that cannot work right now is dimmed and says why
instead of failing after the keypress. **The engine is the single writer:** the window never touches a
manifest, a ledger or a file, it calls an op and prints what came back.

## Components
- **`api/`** is what a row IS. `RepairItem` is the unchangeable `record(id, label, help, gate, status, effect)`,
  with `act(...)` / `page(...)` factories. `RepairGate` answers "why not, or empty for go" over the snapshot
  (`OPEN`, the shared `FILE_SAFE`, and `all(...)` to compose). `RepairStatus` is the line under the divider.
  `RepairEffect` is `sealed`: `Act(working, confirm, action)` or `Page(page)`, so is-this-an-action-or-a-screen
  is answered by the type once. `RepairAction` runs on the worker and returns a `RepairResult`, whose `ofSync`
  / `ofUnpin` / `ofToggle` are the ONLY place 's return codes become English. `UpdaterState` is one
  immutable read of the install (plus `ReleaseList` for the picker); `RepairPage` + `PageRow` are a second
  screen inside the right panel; `RepairContext` is the host seam, exactly like `SecretContext`; `Tone` names
  how a line reads so `core` never needs a colour.
- **`ops/EngineOps`** is the real engine: one reflective op per method through `core.updater.OpsBridge`,
  deliberately too thin to hold an opinion.
- **`registry/RepairRegistry`** discovers the rows and sorts them by `order()` then id, so the MENU order is
  's and not the jar's alphabetical scan order.
- **`session/RepairSession`** is the state machine: selection, page depth, the confirm prompt, the busy state,
  the key ladder, and `frame(...)` as a thin call into `view/`. Nothing cosmetic lives in it on purpose, because that
  ladder is the only thing standing between an ESC and a hand-off with the engine mid-write.
  `session/RepairSample` poses the states the renderer and the tests use.
- **`view/`** is the geometry, the frame model and the whole layout: `RepairBox` (36-column left cell, the
  borders, `clip`/`wrap`/`dotted`), `RepairFrame` (title, bars, interior rows as left/right cells with tones,
  hints, prompt) and `RepairFrames` (a `View` snapshot in, the finished frame out, plus the page cursor the row
  walk had to correct). Same state/layout split `core/secret/secrets/doom/game/` uses for `DoomState` and
  `DoomHudLayout`.
- **`input/`** is the key events: `RepairKeys` maps a GLFW code to a `RepairIntent` (UP, DOWN, ACTIVATE, BACK),
  codes inlined as ints so this layer never imports LWJGL.
- **`items/`** is one folder per row, in menu order: `status/` (10), `verify/` (20), `sync/` (30), `optional/`
  (40), `notes/` (50), `rollback/` (60), `lock/` (70). Four of them own a `page/` subfolder.

## How it works
`RepairScreen.onEnter` builds the registry (on the FIRST open only, a boot that never opens this pays nothing)
and a fresh `RepairSession`, then asks the worker for a snapshot; the page opens on "Reading the updater ..."
and fills in when it lands. Each frame the screen drains the worker's three answers into the session, stamps
the live clock plus the engine's sync flag onto the snapshot, drains the key queue through `RepairKeys`, and
draws `session.frame(...)`.

`key(intent, ctx)` is a gate ladder, and the order IS the safety story: busy refuses ACTIVATE and BACK first
(walking out mid-write would hand off to Minecraft with the engine still staging files), then an open prompt
owns the keys, then a page, then the list. ACTIVATE on the list refuses if the snapshot has not landed, then
prints the gate's own sentence if the row is gated, then either opens a page or offers the `Act` (a non-empty
`confirm` becomes the popup first). A page's own rows go through the same central `FILE_SAFE` check, so no page
can be written that forgets it, and `start()` runs it ONE more time right before the hand-off, because a prompt sits
on screen for as long as a player takes to read it and the engine can start writing inside that gap.
Actions run on the screen's single daemon worker and come back as one line.

## Dependencies
- Depends on `core.updater.OpsBridge` (the reflective handle to the shim) and `core.util.ResourceScan` (the
  class scan), plus the JDK. No GL, no Minecraft, no LWJGL, which is exactly why the offline renderer can
  compile this tree on its own.
- It imports nothing from `bios/`: `RepairContext` is declared here and `RepairScreen` implements it.
- The window is only reachable when `OpsBridge.present()` is true, so inside these classes an engine is
  assumed to exist; an op that a running engine predates comes back as `NO_ANSWER` and reads as
  "The updater did not answer.", never as success.
- Single-threaded by convention: the session is touched only on the render thread. The worker writes into the
  screen's atomics and nothing else.
- `view/RepairBox`'s numbers are a deliberate SECOND copy of `drawSetup`'s box math (36 columns, the same
  borders), so the two screens look identical. When the provider's extraction lands, both collapse onto
  `RepairBox`, and until then a change to the box shape has to be made in two places. The short-window widen
  rule is NOT one of those places: the screen asks the provider for it through `BiosContext.biosBodyWidth(rows)`,
  because a second copy of that formula means the font jumps between the two screens the first time it is tuned.
- Keep the per-item folder names LOWERCASE alphanumeric, the discovery pattern is `[a-z0-9]+/\w+\.class`, so
  an underscored or capitalised folder is silently skipped.

## Adding a row
Make a folder `items/<name>/` with a public no-arg `RepairDef` at its root:

```java
public final class MyItem implements RepairDef {
    @Override public RepairItem item() {
        return RepairItem.act("myid", "Do The Thing", "What the thing is.",
                RepairGate.FILE_SAFE, state -> "Last sync: " + state.lastSyncCell(),
                "Do the thing?", ops -> RepairResult.ofSync(ops.syncNow()));
    }
    @Override public int order() { return 35; }
}
```
Anything bigger than the def goes in `<name>/page/`. A row that needs a new engine call adds one method to
`RepairOps` + one line in `EngineOps`; a row whose op the shipped engine might not have gets `NO_ANSWER` for
free. Add the scenario to `RepairSample.SCENARIOS` and the offline renderer draws it next run.

## Child layers
- **`api/`**: the ten definition types a row is built from.
- **`input/`**: `RepairIntent` + `RepairKeys`, the key events.
- **`items/`**: the seven shipped rows, one folder each, four with a `page/`.
- **`ops/`**: `EngineOps`, the reflective engine surface.
- **`registry/`**: `RepairRegistry`, discovery + menu order.
- **`session/`**: `RepairSession` (the state machine) + `RepairSample` (posed states).
- **`view/`**: `RepairBox` (columns) + `RepairFrame` (the finished frame) + `RepairFrames` (the layout).
