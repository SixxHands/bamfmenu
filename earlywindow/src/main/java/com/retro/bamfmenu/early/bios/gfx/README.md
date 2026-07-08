# early/bios/gfx — the BIOS host's GL text/graphics primitives (font baker + render-thread canvas)

## Intent
This layer is the host-side graphics foundation of the BIOS presentation: the two classes that actually
touch OpenGL so that nothing else in `bios/` (screens, overlays, transitions) has to. `RichFont` exists
because FML's `SimpleFont` bakes only ASCII (codepoints 32..126) and re-encodes every string to US-ASCII —
collapsing the box-drawing, block-shade and arrow glyphs the terminal look depends on to `'?'`; it bakes our
own multi-range Unicode atlas instead. `TerminalCanvas` is the render-thread-only GL *facade* every screen
draws through, so screens stay free of LWJGL and the provider remains the sole owner of the framebuffer/
blit/swap. Both date from the .75 decomposition (the plan doc's Step 1 landed the text core, Step 2
expanded the canvas into the full per-frame rendering context — theme colours, framebuffer size, layout/logical
math, and the pure string helpers); per that plan `TerminalCanvas` is the ONLY class importing
`org.lwjgl.opengl.*` outside the provider.

## Components
- **`RichFont`** — our own STBTruetype bitmap-font baker (built on the render thread with the GL context
  current, like `SimpleFont`). The constructor reads a bundled TTF resource, packs the codepoint `RANGES`
  (printable ASCII, Latin-1 supplement, dashes/quotes/bullet/ellipsis, four arrows, box-drawing, block
  elements + shades, a few geometric shapes) into one `GL_RED` atlas via `stbtt_PackFontRanges`, and stores a
  per-codepoint `Glyph` record (advance + atlas quad). `smooth` picks `GL_LINEAR` vs `GL_NEAREST` filtering.
  Reuses FML's `SimpleBufferBuilder` so the GL draw path matches `SimpleFont`; only the glyph set/lookup
  differ. Key methods: `draw(x, y, bb, text, colour)` emits quads by codepoint (handling `\n`/`\t`/space,
  unmapped glyphs advance like a space), `stringWidth(text)` matches that advance model, and `lineSpacing()`.
  Package-private accessors `descent()` / `textureUnit()`.
- **`TerminalCanvas`** — the render-thread-only GL text facade and per-frame rendering context (constructed on
  the render thread in `initRender` after the font is built; holds zero game state). Wraps the provider's
  `ElementShader` + `RichFont` + `SimpleBufferBuilder` + font texture. The provider calls `setFrame(fbWidth,
  fbHeight, blueTheme)` once per frame before any screen draws. Exposes: the shader preamble
  `beginFrameShader(logicalW, logicalH)` (activate shader, set screen-size/texture/FONT-type uniforms) and
  mid-frame `logicalSize(...)`; `text(x, y, text, colour)` (one run, ABGR); the modal `popup(...)` and the
  cutscene `wipeFromRight(...)`, both of which GL-scissor-clear their rect (the text renderer can't fill a
  background) using the same logical→framebuffer mapping; theme-aware colours `cFg`/`cBright`/`cDim`/`cGold`;
  framebuffer/logical accessors (`fbWidth`/`fbHeight`/`logicalW`/`logicalH`/`margin`/`lineSpacing`/`charWidth`/
  `blueTheme`); `blockWidth(lines)`; `drawScaledCentered(lines, rx, ry, rw, rh, colour)` (Phase C2 — draws a block
  of lines UNIFORMLY scaled to fit a logical rect + centered, via the header-logo `logicalSize` remap trick, then
  restores the body coordinate space; lets a "pop" like the rhythm judgment drop in arbitrarily big ASCII art and
  always fit its reserved area, since the ASCII look is by choice, not a real fixed terminal); and the static,
  GL-free string helpers `clip`/`rule`/`center`/`asciiBar`. Constants `LOGICAL_W = 1400`, `MARGIN = 16`.

## How it works
At GL init the provider builds the atlas (`new RichFont(...)`) on the render thread, then constructs the
`TerminalCanvas` over the shader, that font, the shared buffer, and the font texture unit. Each frame the
provider's `renderFrame` calls `canvas.setFrame(fb, fb, theme)` to refresh size + theme, then each active
`Screen` calls `beginFrameShader(...)` once and issues `text(...)` / `popup(...)` / `wipeFromRight(...)`
calls. `text()` opens a `POS_TEX_COLOR`/`QUADS` batch, delegates glyph layout to `RichFont.draw`, and flushes
it; `popup`/`wipeFromRight` additionally issue raw `glScissor`+`glClear` to paint their backgrounds in the
per-frame theme colour. `RichFont` thus produces the geometry; `TerminalCanvas` owns the GL state machine and
hands screens a terminal vocabulary (logical-canvas math, colours, string helpers) with no LWJGL leakage.

## Boundaries
Depends on LWJGL (`org.lwjgl.opengl.GL32C`, `org.lwjgl.stb.STBTruetype` and STB structs, `MemoryUtil`,
`BufferUtils`) and three *public* FML early-display types (`ElementShader`, `SimpleBufferBuilder`, and — via
the bundled TTF resource path — the `SimpleFont` baseline conventions it mirrors). It imports NO Minecraft
classes and NO `core/`. Everything else in `bios/` (the provider, screens, overlays, transitions) depends on
this layer for drawing and must go through `TerminalCanvas` rather than calling GL directly. Invariants:
GL-only (both classes assume the render thread with the context current — `RichFont` must be *built* there,
`TerminalCanvas` must be *used* there); `TerminalCanvas` holds zero mutable game state, only per-frame display
state set by the provider; and this is the sole GL-touching layer the rest of `bios/` is allowed to use.
