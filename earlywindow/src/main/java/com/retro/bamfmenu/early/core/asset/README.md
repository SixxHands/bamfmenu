# early/core/asset, headless shared asset loading
## Features
Asset loading bundled resources (text art, a descriptor file, a picture, a clip) turned into
plain data that other layers consume. Originally from the `TerminalWindowProvider`, it is now one of `core/`'s
six categories. Assets should be only the runtime loaders. Pure stdlib (no GL, no Minecraft; `AsciiImage` adds headless-safe AWT decode), 
so it belongs in `core/` and is safe in the service jar before Minecraft exists. Resources resolve from the classpath 
root. The loader class doesn't matter, every home lives in this one jar.

## Components
- **`Art`** is an all-static utility. Thread-agnostic, in practice the render thread stages the art.
  - `LANE_COLORS`: the four BAMF! lane colors in ABGR, purple/yellow/green/red.
  - `loadArt(resource)`: reads a bundled ASCII-art resource, sanitizes each line, trims the blank edges.
    A missing resource or any error returns an empty array so callers degrade.
  - `isDrawable(c)`: true only for the glyph ranges `RichFont` actually bakes. Kept in sync with
    `RichFont.RANGES` by comment, not by reference.
  - `sanitizeAscii(s)`: swaps every non-drawable char for a space so un-bakeable glyphs never reach the font.
- **`Glitch`** is an all-static glitch-text primitive, extracted from `CutsceneScreen` so the hidden Snake
  game reuses the same look. `Math.random` inside, render-thread only.
  - `CHARS`: the symbol alphabet.
  - `glyph()`: one random symbol, re-rolled per call for the per-frame shimmer.
  - `noise(len)`: a string of random symbols.
  - `scramble(target, progress)`: the left-to-right text-decode reveal, the cutscene's "unzipping" flourish.
  - Used by `CutsceneScreen` and `SnakeScreen` (the glitching snake body).
- **`Json`** is the tiny dependency-free JSON reader for descriptor files. The service jar runs pre-Minecraft
  and ships no Gson, so we parse it ourselves. Moved here from the dissolved `core/json`, a reader is asset
  machinery.
  - a hand-rolled recursive-descent parser covering exactly what descriptors need: objects, arrays, strings,
    numbers (all `Double`), `true`/`false`/`null`.
  - `parse(String)`: the canonical tree, throws `JsonException` on malformed input. `object(String)` swallows
    failure into an empty `Obj` instead.
  - `Obj`: the read-only defaulting view. Getters return the default when a key is absent or wrong-typed,
    `obj(key)`/`objs(key)` return empty views so chained reads never NPE. Unknown fields ignored, missing
    fields defaulted, a malformed descriptor never throws into the runtime.
  - Read-only, the editor writes chart text via `ChartIO`, not JSON. Used by `rhythm/SongLoader` for
    `songs/<id>/song.json`.
- **`AsciiImage`** is the picture decoder + picture to colored-ASCII renderer. `load(resource)` decodes a
  bundled png/jpg into an opaque `Source` handle, AWT types never leak to callers, null on failure. Two
  output paths:
  - real bitmap (the live wysi path): `argb(src)`/`width(src)`/`height(src)`, the raw pixels a renderer feeds
    to `TerminalCanvas.image(...)` for the full-quality reveal (the jarring realness is the joke).
  - cell rasterization (now probe-only): `render(...)` fits the image to a cell box and block-averages each
    cell into a ramp glyph + color. `brightness` scales pixels before the ramp, so a fade resolves through
    the glyph gradient. The only remaining caller is `tools/probe/AsciiImageProbe`.
- **`VideoClip`** is a tiny bundled video for the terminal, made for the wysi cutscene: pre-extracted frame
  jpgs + a fixed fps. Frames are probed sequentially (`f00.jpg`, `f01.jpg`... until the first miss) because
  directory enumeration is unreliable in the union jar, name-probing is not.
  - `frame(tSeconds)`: the clamped frame.
  - `frameIndex(tSeconds)`: the clamped index, also the cheap identity key for a renderer's texture upload.
  - `frameW()`/`frameH()`/`frameArgb(index)`: dimensions + a frame's raw ARGB pixels, memoized per frame
    (~230KB per 240x240 frame).
  - the companion audio is `<base>/audio.ogg` by convention, played by the host, not this class. Frames
    decode via `AsciiImage.load`.

## How it works
Each utility is a one-call resource to data pipe. `loadArt` reads lines, sanitizes them, and hands back a
ready-to-draw `String[]`. `Json.object(src)` parses the text and wraps it in the defaulting `Obj` view that
loaders read typed fields off. `AsciiImage` and `VideoClip` supply raw pixels + a frame key that the provider
hands straight to `TerminalCanvas.image(...)`: decode and memoization here, GL upload there. Failure is never
thrown anywhere, callers get an empty array, an empty `Obj`, or a null handle and degrade. Nothing here
touches GL or game state.

## Dependencies
- Depends only on the JDK. `AsciiImage`/`VideoClip` add `javax.imageio` + `java.awt.image`, headless-safe
  decode only, no window.
- `RichFont` and `RichFont.RANGES` are named in comments only, no compile-time dependency, so this stays a
  leaf with no imports beyond stdlib.
- Consumed by `rhythm/SongLoader` (`Json` + `Art`), song/panel/registry code (`loadArt`, `LANE_COLORS`), the
  provider's wysi cutscene (`AsciiImage` + `VideoClip`), and `tools/probe/AsciiImageProbe` (the cell-raster
  path, offline).
- `core/` never imports `bios/`. Everything is stateless or unchangeable after load (`VideoClip`'s pixel memo is
  render-thread only), so it is safe wherever the caller already is.
