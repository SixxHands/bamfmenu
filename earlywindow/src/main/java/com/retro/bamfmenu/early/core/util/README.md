# early/core/util, shared utility functions
## Features
The one home for utility code the rest of the tree shares. Anything here is a pure function or a
self-contained helper: no GL, no Minecraft, no `bios/`, no game state. It exists because util stuff was
scattered wherever it was first needed and callers were reaching into unrelated classes to get it (the secret
registry and the Tetris playlist both reached into `SongLoader` just for a classpath walk). This folder grows
slowly, things move in as we touch them, not in one sweep.

## Components
- **`ResourceScan`** is the classpath enumeration. A dev `file:` dir is walked directly; the packaged FML
  `union:` URL can't be listed, so the containing jar is resolved and its ZIP entries scanned instead.
  - `listResources(dir, regex)`: files directly under `/<dir>/` whose name matches (the Tetris playlist).
  - `listResourcesDeep(dir, pathRegex)`: matches the relative path under `/<dir>/`, so a shape-enforcing
    pattern works (secret discovery matches exactly one folder level down).
  - `jarFileFromUrl(url)` / `jarFileFromCodeSource()`: resolve the real jar file behind FML's URL schemes.
    Strips only a trailing `%23NNN` union marker, and decodes with our own RFC-3986 decoder. `URLDecoder`
    maps `+` to a space and throws on a lone `%`, both corrupt real install paths.
  - never throws, never null, results sorted for a stable order.
- **`Figlet`** is a tiny on-the-fly FIGlet `.flf` renderer. Big ASCII-art text composed of native glyphs, a
  terminal-correct way to make large text instead of trying to scale the font. Fonts bundled at
  `/doom/fonts/`: `Poison` (h12), `Doom` (h8), `small` (h5). Cached, full-width layout, no smushing. DOOM's
  menus and status bar use it today; it lives here because nothing about it is DOOM-specific.

## How it works
Callers call the statics. Nothing here keeps state between calls (Figlet caches parsed fonts, that's it).

## Dependencies
- Depends on the JDK only. No GL, no Minecraft, no `bios/`, no other `core/` package.
- Depended on by `core/rhythm/registry` (song discovery), `core/secret/registry` (secret discovery),
  `bios/modes/minigame/TetrisScreen` (the playlist), and the DOOM menu/HUD layouts + probes (`Figlet`).
- The union-URL handling is load-bearing for BOTH discoveries: songs and secrets go dark in the packaged jar
  if the jar resolution breaks. Test both branches after touching it, `genCharts` exercises the dev walk and
  the scratch jar probe exercises the ZIP branch.
