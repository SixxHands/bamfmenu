# early/core/tools/probe, the 5 offline dev probes
## Features
Dev-only `main()` classes that verify a subsystem offline: either boot-and-measure the vendored DOOM engine
headless, or rasterize the exact runtime layout/render code to PNGs at the real measured terminal cell
metrics, so results can be eyeballed without booting the dev client. Every task runs with
`java.awt.headless=true`, which makes any accidental window creation THROW: a pass guarantees truly headless.
Probes are analysis machinery, not gameplay. Nothing in the mod references them. They ship as harmless dead
code, reachable only via their gradle tasks (only the `*.md` docs are excluded from shipped jars, these
classes ride along inert).

## Components
Each probe is one `JavaExec` task in `earlywindow/build.gradle` (group `bamfmenu`).
- **`DoomFramebufferProbe`** (`gradlew :earlywindow:genDoomFrame`) boots vendored Mocha Doom through
  `DoomEngine` with no window, samples frames for 8s (distinct via `checksum`), renders one through
  `DoomAscii.render(frame, 100, 56)` (asserts >8 distinct cell colors and all-opaque ABGR; non-opaque cells
  would render transparent), dumps `doom-frame.txt`, verifies `stop()` then restart-with-a-fresh-engine, drives
  Tab/Esc/Enter (`postGlfwKey`) to read the live menu + in-level HUD through `DoomState.Snapshot`
  (health/armor/ammo/weapon/arms/keys), and checks the `Figlet` fonts load. Prints a PASS/FAIL banner; exit 0/1.
- **`DoomImageProbe`** (`gradlew :earlywindow:genDoomImage`) boots to the title screen, then writes
  `doom-raw.png` (framebuffer, palette read as 0xRRGGBB), `doom-raw-swap.png` (R/B swapped, settles the channel
  order against the real DOOM title), and `doom-ascii.png` (the `DoomAscii` grid as colored monospace glyphs,
  what the terminal shows).
- **`DoomMenuProbe`** (`gradlew :earlywindow:genDoomMenu`) needs no engine boot: it rasterizes the real menu
  item sets `DoomState` produces (main DOOM1/DOOM2, episode retail/shareware, skill, options + sound with
  their `""` spacer rows, load/save with `[ EMPTY ]` and named slots) through the exact `DoomMenuLayout` the
  BIOS uses -> `doom-menu-<name>.png` x 9.
- **`DoomHudProbe`** (`gradlew :earlywindow:genDoomHud`) rasterizes the status bar (`DoomHudLayout`, FIGlet
  numbers via the `small` font) with two sample HUD states, fresh start (pistol, HP 100) and loaded mid-fight
  (matching a reference screenshot: HP 86 ARM 187, all weapons, three keys) -> `doom-hud-start.png` /
  `doom-hud-loaded.png`.
- **`AsciiImageProbe`** (`gradlew :earlywindow:genWysi`) is the wysi probe: it rasterizes the cell overlay
  through the `AsciiImage` render + fade at three timeline points (fade-in f=0.25 / hold f=1.0 / fade-out
  f=0.7) -> `wysi-fade-in/hold/fade-out.png` (with the surrounding cell frame drawn for context), prints the
  auto-fit cell grid; also loads the `/wysi` video clip headless (`VideoClip`) and dumps a mid-clip frame as
  3x block pixels -> `wysi-video-mid.png` (a MISSING notice if the frames aren't bundled). The cell-raster
  path it exercises is probe-only now, the shipped cutscene draws the real bitmap.

## How it works
The offline-PNG-verify pattern: each probe calls the SAME layout/render classes the live terminal uses
(`DoomMenuLayout`/`DoomHudLayout`/`DoomAscii`/`AsciiImage`, never a reimplementation), blits each character
into its own fixed cell the way `TerminalCanvas` lays text out, at the real measured native metrics (`cw=13`,
`ls=28`; grid `107x28`, printed by the one-shot `[doomdims]` probe in the dev client; `AsciiImageProbe` uses
the setup screen's ~67x15 right cell), and writes PNG/text artifacts into the working directory. Layout
regressions (banner font choice, big-item placement, centering, fit, fade curves, color channel order) become
visible in an offline eyeball before anyone boots the early window. This gated the DOOM menu/HUD and both
wysi cutscenes. Only the two engine-booting probes (`Framebuffer`/`Image`) drive the vendored engine
(`DoomEngine` `start`/`frame()`/`postGlfwKey`/`stop`, with an `await`-ready poll), the layout probes need no
engine at all. The glyph font in the PNGs is plain `MONOSPACED`, grid alignment is what's being verified, not
the game's glyphs.

## Dependencies
- Depends on `core/secret/doom/game` (`DoomEngine`, `DoomAscii`, `DoomState`, `DoomMenuLayout`, `DoomHudLayout`,
  `Figlet`) and `core/asset` (`AsciiImage`, `VideoClip`), plus `java.awt`/`javax.imageio` for the offline
  rasterizing. No `bios/`, no GL, no LWJGL, no Minecraft.
- Nothing depends on this package, it is reachable only through the five gradle tasks
  (`genDoomFrame`/`genDoomImage` add `libs/mochadoom.jar` to the task classpath, since the vendored engine is
  `compileOnly` for the main build; the layout probes use just the main runtime classpath).
- `java.awt.headless=true` on every task. Probes must keep calling the real runtime render/layout code, or the
  offline verify stops proving anything.
