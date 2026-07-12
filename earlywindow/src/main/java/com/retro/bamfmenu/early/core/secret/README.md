# early/core/secret, data-driven secrets: one folder per secret, machinery at the root
## Features
What hidden phrases / key sequences does the secret box accept, and what does each do? The BIOS "Safe For
Retro Mode" option opens a secret-entry box; this layer turns what's entered there into an action.
Data-driven by drop-in, with a self-enforcing shape: each secret is a folder here. Its declaring `SecretDef`
class sits at the folder root, everything else that belongs to the secret (game logic, renderers, probes)
lives in a subfolder (`game/`), so the definition never mixes with the parts. `SecretRegistry` auto-discovers
by scanning `.class` files exactly one folder level down. The machinery beside `secrets/` (`api/` +
`registry/`) and the subfolder internals (two+ levels) are invisible to it. No central list, no switch, no
registration.

A secret's action does whatever it wants through the `SecretContext` seam (toggle a mode, show a notice,
launch a hidden song/game, play an image/video cutscene). The classic `password` -> Safe-For-Retro egg is
itself just one secret. Pure stdlib + reflection (no GL, no Minecraft), so it builds at provider construction
on any thread. Presentation screens stay in `bios/modes/` with every other screen.

## Components
The machinery is grouped: the five definition types live in `api/` (what a secret IS), the matcher lives in
`registry/`.

- **`Secret`** is the unchangeable `record(name, trigger, action)`: a name (for logs), a `Trigger` (how it's
  entered), and a `SecretAction` (what it does). Factory `Secret.phrase(name, word, action)` for the common
  case.
- **`Trigger`** is a `sealed interface` of two kinds: `Trigger.Phrase` (a typed word, normalized
  trimmed+lowercased so matching is case-insensitive) and `Trigger.Keys` (an ordered run of GLFW key codes,
  matched live). Factories `Trigger.phrase(text)` / `Trigger.keys(int...)`.
- **`SecretAction`** is a `@FunctionalInterface` (`run(SecretContext)`) so a def writes its effect as a
  one-line lambda.
- **`SecretContext`** is the host seam the action acts through, implemented by the provider:
  `toggleSafeForRetro()`, `notice(msg)`, `launchSong(id)`, `launchGame(id)`, `imageCutscene(path)`,
  `videoCutscene(base)`. Small and additive. Declared here in `core` so `core` never depends on `bios`.
- **`SecretDef`** is the one-method interface (`Secret secret()`) each declaring class implements; the unit
  the registry discovers. Needs a public no-arg constructor.
- **`SecretRegistry`** discovers + matches. It keeps the discovered `List<Secret>` + a rolling key-history
  ring; the provider owns one instance. `submit(typed, ctx)` runs the first matching `Phrase` secret (on
  ENTER), `feedKey(code, ctx)` pushes a key + fires any `Keys` secret whose run just completed,
  `resetKeys()` clears the ring.

## How it works
At provider construction `new SecretRegistry()` calls `discover()`: `ResourceScan.listResourcesDeep(secretDir,
"[a-z0-9]+/\\w+\\.class")` lists the `.class` files exactly one folder level down, converts each path to its
FQN, `Class.forName`s it, and, if it's a real, instantiable `SecretDef`, collects `secret()`. A non-def class
parked beside a def, or a bad/abstract def, is skipped; it logs `[bamfmenu] secrets: N loaded`. At runtime
the provider's `onKey`/`onChar` feed the box: typed characters build the phrase buffer and ENTER calls
`submit` (match runs the action and closes the box, no match is "Access denied"); every other key press
calls `feedKey`, so a completed key sequence fires live. The matched secret's action runs against the
provider's `secretCtx`.

## Adding a secret
Make a folder `secrets/<name>/` with a public no-arg `SecretDef` at its root:

```java
package com.retro.bamfmenu.early.core.secret.secrets.myname;
public final class MySecret implements SecretDef {
    @Override public Secret secret() {
        return Secret.phrase("my-secret", "open sesame", ctx -> ctx.notice("It opened."));
    }
}
```
or a key sequence (matched live as keys are pressed; GLFW codes inlined so core never imports LWJGL):
```java
return new Secret("reveal", Trigger.keys(265, 265, 264, 264), ctx -> ctx.launchSong("hidden-song-id"));
```
Anything bigger than the def goes in `<name>/game/` (or a similarly-named subfolder; anything two levels down
is outside discovery). Need a new power? Add one method to `SecretContext` + implement it on the provider.

## Dependencies
- Depends on one sibling `core` package, `early.core.util` (`ResourceScan.listResourcesDeep` for the class
  scan), plus the JDK (reflection).
- It imports nothing from `bios/`: `SecretContext` is declared here and the provider (in `bios`) implements
  it, so core knows nothing of the host and the host wires core into the box. No GL, no Minecraft.
- The registry is the sole matcher; the provider only forwards input + supplies the `SecretContext`.
- Typed letters reach `feedKey` too (every key press does), so a `Keys` secret built from LETTER codes could
  fire mid-typing from a word containing them. Prefer arrow-anchored sequences, the bundled Konami code is
  arrow-prefixed so no typed word triggers it.
- Keep the per-secret folder names LOWERCASE alphanumeric, the discovery pattern is `[a-z0-9]+/\w+\.class`,
  so an underscored/uppercased folder is silently skipped.

## Child layers
- **`api/`**: `Secret`, `Trigger`, `SecretAction`, `SecretContext`, `SecretDef`.
- **`registry/`**: `SecretRegistry`, discovery + matching.
- **`secrets/safeforretro/`**: `password` -> `toggleSafeForRetro()`. The template for a phrase secret.
- **`secrets/konami/`**: the Konami code (up up down down left right left right B A) -> a flavor `notice`.
  The template for a key-sequence secret.
- **`secrets/snake/`**: `snake` -> `launchGame("snake")`; `game/` keeps `SnakeGame`. The template for a
  hidden game (a new game = its Screen + one case in the provider's `launchGame`).
- **`secrets/galaga/`**: `galaga` -> `launchGame("galaga")`; `game/` keeps `GalagaGame`
  (formation/dive/capture-beam/dual).
- **`secrets/doom/`**: `doom` -> `launchGame("doom")`; `game/` keeps the vendored-DOOM bridge + renderers.
- **`secrets/wysi/`**: `wysi` -> `videoCutscene("/wysi")`. The BIOS locks input while the bundled clip plays
  with sound, full quality, in the state box under the divider (`/wysi/f00.jpg..` at 15fps +
  `/wysi/audio.ogg`; regenerate from any mp4 via `scratch/wysi-src/README.md`; missing frames fall back to
  the `/wysi.png` still fade). Its media primitives (`AsciiImage`, `VideoClip`) live in `core/asset`.
