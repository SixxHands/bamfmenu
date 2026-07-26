# early/core/updater, the two one-way doors onto the updater: the bus feed and the ops handle
## Features
Everything the early window knows about the updater comes through this folder, and it is deliberately two
separate paths that fail separately.

`UpdaterFeed` is the DISPLAY path: it tails `.bamfos-updater/bus.log` and turns the engine's events into the
POST block, the dmesg lines, the three detected-hardware rows, and the mid-sync flag. The filesystem IS the bus
on purpose, so no combination of absent, old or broken updater can cost us a frame.

`OpsBridge` is the CONTROL path: the one class that knows the shim's class name, reached reflectively so this
jar never compiles against it. It is what the UPDATE AND REPAIR window calls, and it is why that window can
exist at all on a service floor where the engine lives in its own isolated classloader (sanctions
exactly one same-floor handle, `UpdaterBridge`).

Neither one ever throws at a caller. An absent updater is the normal case, not an error: the BIOS keeps its
joke row and the POST screen says the kmod is missing, once.

## Components
- **`UpdaterFeed`** owns the bus reader and the POST display model. Truncates the file at construction (the
  window starts BEFORE the locator phase, so everything read after is THIS boot's), keeps a fixed row footprint
  that scrolls itself, and terminates the block on a non-ok END rather than leaving a spinner spinning forever.
  `syncActive()` is the mid-sync flag the DEL gate and the repair window's single-writer refusal both read.
  Render thread only.
- **`OpsBridge`** resolves `UpdaterBridge` + its two methods ONCE in a static initialiser and latches presence
  in a volatile once the door has bound. Absent shim leaves the handles null and `present()` is a field read
  forever after, which matters because SETUP reads it every frame: resolving per call would build a
  `ClassNotFoundException` roughly 240 times a second on an install with no updater. Every accessor coerces
  what came back (`code`, `flag`, `text`, `map`, `rows`, `rowsIn`) rather than casting, and `NO_ANSWER` (-1) is
  its own value so an op the running engine predates can never be read as the success code 0.

## How it works
The provider builds one `UpdaterFeed` at window init and pumps it once per frame from `renderFrame`, for EVERY
screen and not just POST: `syncActive()` is a safety signal, and a flag frozen at whatever it was the last time
the POST screen drew would let a BIOS page offer a file action while the engine is mid-write.

`OpsBridge` has no lifecycle. The shim class is on the service layer from frame 0 if its jar is in `mods/`, but
the `ops` FIELD inside it only binds when the client door runs during mod discovery, which is after the BIOS can
already be open. So presence is polled, the SETUP row re-reads it every frame, and a player sitting in the menu
watches the first row turn into UPDATE AND REPAIR when the door lands.

## Dependencies
- Depends on the JDK only (NIO for the feed, reflection for the bridge). No GL, no Minecraft, no LWJGL.
- `UpdaterFeed` depends on the engine's bus FORMAT (tab-separated event lines), which is the one contract that
  spans the two modules. Its test in `src/test` is the reader half of it, so a rename on the writer side has
  something red to trip on.
- `OpsBridge` depends on `com.retro.bamfos.updater.boot.UpdaterBridge` by NAME only, and on the ops names in
  . An unknown name comes back as an error string, which becomes `NO_ANSWER`.
- Depended on by the provider's POST screen (`UpdaterFeed`) and by `core.repair` + `bios.modes.repair`
  (`OpsBridge`). Nothing here depends on `bios/`.
- at both doors: a hiccup on either path logs a line and the boot continues.
