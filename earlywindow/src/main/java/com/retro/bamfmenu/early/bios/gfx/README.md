# early/bios/gfx, font baker and render-thread GL canvas
## Features
These are the two classes that actually touch OpenGL, so nothing else in `bios/` (screens, overlays, transitions) has
to. `RichFont` exists because FML's `SimpleFont` bakes only ASCII (codepoints 32..126) and re-encodes every
string to US-ASCII, collapsing the box-drawing, block-shade and arrow glyphs the terminal look depends on to
`'?'`. `RichFont` bakes our own multi-range Unicode atlas instead. `TerminalCanvas` is the render-thread-only
GL facade every screen draws through, so screens stay free of LWJGL and the provider remains the sole owner
of the framebuffer/blit/swap. `TerminalCanvas` is the ONLY class importing `org.lwjgl.opengl.*` outside the
provider.

## Components
- **`RichFont`** is our own STBTruetype bitmap-font baker. Built on the render thread with the GL context
  current, like `SimpleFont`. The constructor reads a bundled TTF resource, packs the codepoint `RANGES`
  into one `GL_RED` atlas via `stbtt_PackFontRanges`, and stores a
  per-codepoint `Glyph` record. `smooth` picks `GL_LINEAR` vs `GL_NEAREST` filtering.
  Reuses FML's `SimpleBufferBuilder` so the GL draw path matches `SimpleFont`. Only glyph set/lookup
  differ. `draw(x, y, bb, text, colour)` is the key method that emits quads by codepoint (handling `\n`/`\t`/space,
  unmapped glyphs advance like a space), `stringWidth(text)` matches that advance model, and `lineSpacing()`.
  Package-private accessors `descent()` / `textureUnit()`.
- **`TerminalCanvas`**is the render-thread-only GL text facade and per-frame rendering context. Its constructed on
  the render thread in `initRender` after the font is built. It keeps zero game state. `TerminalCanvas` wraps the provider's
  `ElementShader` + `RichFont` + `SimpleBufferBuilder` + font texture. The provider calls `setFrame(fbWidth,
  fbHeight, blueTheme)` once per frame BEFORE any screen draws. Constants `LOGICAL_W = 1400`, `MARGIN = 16`.
  - shader: the preamble `beginFrameShader(logicalW, logicalH)` (activate shader, set the
    screen-size/texture/FONT-type uniforms) and the mid-frame `logicalSize(...)`.
  - text: `text(x, y, text, colour)`, one run, ABGR.
  - fills: the modal `popup(...)` and the cutscene `wipeFromRight(...)`. Both GL-scissor-clear their rect
    using the same logical -> framebuffer mapping, the text renderer can't fill a background.
  - theme colours `cFg`/`cBright`/`cDim`/`cGold`; framebuffer/logical accessors
    `fbWidth`/`fbHeight`/`logicalW`/`logicalH`/`margin`/`lineSpacing`/`charWidth`/`blueTheme`;
    `blockWidth(lines)`.
  - `drawScaledCentered(lines, rx, ry, rw, rh, colour)`: draws a block of lines UNIFORMLY scaled to fit a
    logical rect + centered, via a header-logo `logicalSize` remap trick, then restores the body coordinate
    space. Lets a "pop" like the rhythm judgment drop in arbitrarily big ASCII art and always fit its
    reserved area, since the ASCII look is by choice, and this is not a real fixed terminal.
  - `image(x, y, w, h, imgW, imgH, argb, key, tint)`: This is the ONE real-bitmap textured-quad primitive. 
    Draws raw ARGB pixels (`BufferedImage.getRGB` order) as a quad in logical px, uploaded to a canvas-owned 
    lazily-created GL texture on its own unit. Drawn under the FML `TEXTURE` render type with an ABGR `tint` 
    (pass `cBright()` for as-is, drop the alpha byte to fade against the terminal), then restores the FONT shader state + texture
    uniform so surrounding text draws are unaffected.
  - GL-free string helpers that are static, `clip`/`rule`/`center`/`asciiBar`.

## How it works
When GL is inited the provider builds the atlas (`new RichFont(...)`) on the render thread, then constructs the
`TerminalCanvas` over the shader, that font, the shared buffer, and the font texture unit. Each frame the
provider's `renderFrame` calls `canvas.setFrame(fb, fb, theme)` to refresh size + theme, then each active
`Screen` calls `beginFrameShader(...)` once and issues `text(...)` / `popup(...)` / `wipeFromRight(...)`
calls. `text()` opens a `POS_TEX_COLOR`/`QUADS` batch, delegates glyph layout to `RichFont.draw`, and flushes
it. `popup`/`wipeFromRight` also issue raw `glScissor`+`glClear` to paint their backgrounds in the per-frame
theme colour. `image()` swaps the shader to the `TEXTURE` render type + texture unit 7 for one quad, then
swaps back to FONT state before returning. `RichFont` produces the geometry. `TerminalCanvas` owns the GL
state machine. Screens get logical-canvas math, colours, string helpers, and the one bitmap slot, never
LWJGL.

## Dependencies
- **Depends on:** LWJGL (`org.lwjgl.opengl.GL32C`, `org.lwjgl.stb.STBTruetype` and STB structs, `MemoryUtil`,
`BufferUtils`) and the public FML early-display types `ElementShader` + `SimpleBufferBuilder`, plus the
`SimpleFont` baseline conventions it mirrors. 
- It imports NO Minecraft classes and NO `core/`. Everything else in `bios/` (the provider, screens, overlays, transitions) 
depends on this layer for drawing and must go
through `TerminalCanvas` rather than calling GL directly. 
- Both classes assume the render thread with the context current, so `RichFont` must be built there, and `TerminalCanvas` must be used there.
- `TerminalCanvas` keeps zero changeable game state, only display state (the per-frame size/theme set by the
provider, plus the canvas-owned image texture id + upload key `image()` manages itself). This is the sole
GL-touching layer the rest of `bios/` is allowed to use.
