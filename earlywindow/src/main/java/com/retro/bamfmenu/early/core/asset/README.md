# early/core/asset — headless ASCII-art loading + the four lane colours + the glitch primitive

## Intent
This layer holds the shared ASCII-art utilities and constants that were lifted out of the
`TerminalWindowProvider` god-class so that song and panel code can load and colour art **without** depending
on it. Per the class's own comment, this was the "first step of the project restructure (relocate shared
utilities to a neutral public home — see docs/BAMF_STRUCTURE_PLAN.md )". The code is pure stdlib (no GL,
no Minecraft), which is exactly why it belongs in `core/` and is safe to run inside the early-window service
jar before Minecraft exists. Resources still resolve from the classpath root precisely as before — the loader
class is irrelevant because both old and new homes live in this one jar.

## Components
- `Art` — `final`, all-static utility (private no-arg constructor; never instantiated). Pure stdlib, thread-agnostic — callers invoke it from whatever thread they are on (in practice the render thread when staging art, since core never owns GL itself).
  - `public static final int[] LANE_COLORS` — the four BAMFBEAT lane colours in ABGR: purple `0xFFFF00AA`, yellow `0xFFFFFF00`, green `0xFF00FF00`, red `0xFF0000FF`. (The Phase-1 relocation target that `SongRegistry` previously read off the provider as a package-private static.)
  - `public static String[] loadArt(String resource)` — reads a bundled ASCII-art resource via `Art.class.getResourceAsStream(resource)`, sanitizes each line, trims leading and trailing blank lines, and returns the lines. A missing resource (`null` stream) or any `Throwable` returns an empty `String[0]` so callers degrade gracefully.
  - `private static boolean isDrawable(char c)` — true only for the glyph ranges `RichFont` actually bakes (ASCII, Latin-1, select punctuation/arrows, box-drawing, block elements, geometric shapes); kept in sync with `RichFont.RANGES` by comment, not by reference.
  - `private static String sanitizeAscii(String s)` — replaces every non-`isDrawable` char with a space and right-trims trailing spaces, so un-bakeable glyphs never reach the font.
- `Glitch` — `final`, all-static glitch-text primitive (also no-instance). The scramble/noise effect's shared home, extracted from `CutsceneScreen` so the hidden Snake game reuses the EXACT same look. `public static final String CHARS` (the symbol alphabet); `glyph()` = one random symbol (re-roll per call → per-frame shimmer); `noise(len)` = a string of random symbols; `scramble(target, progress)` = the left-to-right text-decode reveal (the cutscene's "unzipping" flourish). Uses `Math.random` → render-thread only. Consumed by `CutsceneScreen` (scramble/noise/decode) and `SnakeScreen` (the glitching snake body).

## How it works
`loadArt` is the single entry point. A caller (e.g. song/panel code that used to call
`TerminalWindowProvider.loadArt(...)`) passes a classpath resource path; `Art` opens the stream off its own
class loader, decodes UTF-8 line by line, runs each line through `sanitizeAscii` (glyph filtering +
right-trim), then strips leading/trailing blank lines and hands back a `String[]` of ready-to-draw rows.
Failure is never thrown — it is collapsed to an empty array. `LANE_COLORS` is a plain constant other layers
read directly. Nothing here touches GL or game state; it only produces immutable data (a `String[]` and an
`int[]`) that the presentation side later renders.

## Boundaries
Depends only on the JDK (`java.io`, `java.nio.charset`, `java.util`). It names `RichFont` and
`RichFont.RANGES` in comments only — there is no compile-time dependency, so this stays a leaf with no imports
beyond stdlib. It is consumed by song/panel/registry code (the Phase-1 relocation made `loadArt` and
`LANE_COLORS` a public shared home instead of package-private members of the provider). Invariants: `core/`
never imports `bios/` (honoured — zero `bios` references here); `Art` is uninstantiable and stateless, so it is
inherently safe to call from any thread.
