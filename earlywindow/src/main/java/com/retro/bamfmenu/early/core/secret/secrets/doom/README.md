# early/core/secret/secrets/doom, DOOM in the terminal
## Features
Have you ever wondered, can minecraft early window loaders run `doom`? Well yes it can (with vendored pure-Java Mocha Doom).
Doom runs on a headless daemon thread, where this package turns its framebuffer + live game state into terminal draw-data.
`bios/modes/minigame/DoomScreen` is the presentation host. DOOM's own pixel art is downsampled to colored ASCII. 
However, DOOM's tiny bitmap TEXT (menus / HUD) turns to mush. 
To solve this, every text-ish surface is re-rendered as crisp terminal text from live engine state read by
reflection, styled after the originals in FIGlet banners, and a faithful paneled status bar. Its purely visual, so technically
doom would work the same without it.

## Components
- **`DoomEngine`** is a lifecycle adapter from the vendored engine, and the ONLY class that imports vendored
  types. `hud()` = `DoomState.read`.
  - `start()` is a daemon thread, `-nosound`, and the bundled shareware WAD extracted to a temp
    dir as literally `doom1.wad`, DOOM identifies the IWAD by FILENAME. `stop()` clears the patched
    `Engine.RUN` flag. `ready()`/`failed()`/`isRunning()` report state. Restart should be safe via a stale-instance guard.
  - `frame()` snapshots the framebuffer as `Frame(byte[] palette-indices, w, h, int[256] palette)`. The palette is reflected
    from the REAL PLAYPAL. `getBaseColor` is NOT an index lookup.
  - `postGlfwKey` maps GLFW codes to `ScanCode` doom events. Tab maps to SC_ESCAPE so Tab opens DOOM's menu
    while our ESC exits to the BIOS.
- **`DoomAscii`** is the framebuffer to colored ASCII. Per cell is roughly 2x2 block-average, then
  sqrt gamma, then FLAT (R+G+B) brightness picks a glyph from the 70-char ramp. Annoying, complicated, but better for when 
  color is averaged via RGB as opaque ABGR (`0xFF...`, anything else renders invisible). A vertical-crop cuts DOOM's own pixel
  status bar out of view, so our bar can replace it. Unless you want to try to decipher mush.
- **`DoomState`** is the live engine state read via the REFLECTION (vendored types stay off this class's imports) into `Snapshot`.
  Menu and HUD reads are guarded SEPARATELY: `warnOnce` logs, and one failure degrades only its own overlay.
  - menu items: graphic-lump names to text via `LUMP_TEXT`, honoring live `numitems` so the DOOM2 / shareware
    menu variants are correct.
  - `message`: StartMessage confirm/info prompts. The engine leaves `currentMenu` stale, so this is read
    separately.
  - `helpScreen`: the 1-blank-item READ THIS! pages.
  - save-slot names: `savegamestrings` is a NUL-padded `char[][]`, decoded to the first 0x00.
  - the in-level HUD: health / armor / current ammo / weapon, plus `weaponowned` / `ammo` / `maxammo` / `cards`
    for the status bar.
- **`DoomMenuLayout`** is a headless menu layout to draw-ops `(col,row,text,kind)`. A FIGlet "DOOM" banner, tier
  picked by leftover rows (Poison at 14+, Doom at 10+, small at 7+, else a 1-line header), then a normal text
  centered native item list with the `► ◄` cursor. status==-1 spacer rows render as gaps.
- **`DoomHudLayout`** is the headless status-bar layout to draw-ops: the faithful full-width panel bar (AMMO │ HEALTH
  │ ARMS │ FACE │ ARMOR │ keys + BULL/SHEL/RCKT/CELL). Here we use hybrid text: big values as FIGlet `small` digits plus a
  native `%`, everything else is just native text. ARMS is a boxed 2x3 grid, lit = owned. The face panel is just empty for now.
- **Dev probes** are as harmless dead code used for making visual rendering tests easier. Each
  backs a gradle task and renders to files so changes are verified OFFLINE before needing and in-game run, 
  purely to avoid repeatedly opening and closing the game to test minor or even major changes.
  `DoomFramebufferProbe` (`genDoomFrame`: headless boot, frames, restart, live menu+HUD reflection, and a real
  menu-driven save into a temp `SAVE_DIR`), `DoomImageProbe` (`genDoomImage`),
  `DoomMenuProbe` (`genDoomMenu`), `DoomHudProbe` (`genDoomHud`).

## How it works
`DoomSecret` ("doom") -> provider `launchGame("doom")` -> `Mode.DOOM` + `DoomScreen`. The screen polls `ready()`,
then each frame: `frame()` + `hud()`. Render precedence is message -> helpScreen -> menu -> game. Menus and prompts
draw on a black backdrop, the game ASCII is skipped so nothing bleeds through. In a level the bottom rows are
reserved and `DoomHudLayout` draws the paneled bar while `DoomAscii`'s crop removes DOOM's own. Input drains
through the provider's queue -> `postGlfwKey`. ESC exits to the BIOS (`stop()`). DOOM's own Quit ends the loop,
the screen sees `!isRunning()` and bails.

## Dependencies
- **Depends on:** the JDK; `DoomEngine` alone imports the vendored engine (compile against `earlywindow/libs/
  mochadoom.jar`, merged into the service jar by the jar task). `DoomState` reaches the engine only via
  reflection. The menu/HUD layouts draw their big text through `core/util` `Figlet` (it moved there, nothing
  about it was DOOM-specific). Probes use AWT/ImageIO, dev-only (`java.awt.headless=true` tasks).
- **Depended on by:** `bios/modes/minigame/DoomScreen` (the host), `core/secret/doom/DoomSecret` (the declaring
  def at the doom secret folder root), and the provider's game-wiring touchpoints (`Mode.DOOM` +
  launch/input/render plumbing); nothing else.
- **Saves:** `Engine.SAVE_DIR` prefixes every doomsavN.dsg path. `DoomScreen` points it at
  `<gameDir>/BAMFOS/doom/` via `ctx.bamfosDir()` before start, so saves live with the rest of the mod's data.
  Unset = vanilla working-dir behavior (the probes without a dir).
- **Vendored engine:** patched for embedding. Its 3 files: `mochadoom/Engine.java`
  headless window skip + `RUN` stop flag + accessors, `doom/DoomMain.java` RUN-gated loop, `i/DoomSystem.java`
  no `System.exit`. The modified GPLv3 source lives REPO-TRACKED at `tools/doom-spike/` (upstream
  gaborbata/vanilla-mocha-doom; has its own build scripts + docs, the home that builds `libs/mochadoom.jar`).
  `scratch/doom-spike/` is a gitignored throwaway clone. Subject to change in the future. Licenses ship in `/doom/` next to the WAD.
- `core/` never imports `bios/`. Everything here is render-thread driven except the
  engine's own daemon thread: `DoomEngine`'s crossing fields are `volatile`, `Frame`/`Snapshot`. 
  Any color handed to the canvas MUST always be opaque ABGR.
