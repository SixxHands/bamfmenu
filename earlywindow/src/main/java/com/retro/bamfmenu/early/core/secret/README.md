# early/core/secret — data-driven secrets (Easter eggs) entered in the Safe-For-Retro box

## Intent
This layer answers: *what hidden phrases / key sequences does the secret box accept, and what does each do?* The
BIOS "Safe For Retro Mode" option opens a secret-entry box; this layer turns what's entered there into an action.
It is **data-driven by drop-in**: a secret is one small `SecretDef` class under `defs/` returning a `new Secret(...)`,
and `SecretRegistry` AUTO-DISCOVERS it (a scan of the `defs` package's `.class` files) — there is no central list to
edit, no switch, no registration. A secret's action does WHATEVER through the `SecretContext` seam (toggle a mode,
show a notice, launch a hidden song). The classic `password` → Safe-For-Retro Easter egg is itself just one secret
here, no longer a hardcoded `equals`. Pure stdlib + reflection (no GL, no Minecraft), so it builds at provider
construction on any thread.

## Components
- **`Secret`** — the immutable `record(name, trigger, action)`: a name (for logs), a `Trigger` (how it's entered),
  and a `SecretAction` (what it does). Factory `Secret.phrase(name, word, action)` for the common case.
- **`Trigger`** — a `sealed interface` of two kinds: `Trigger.Phrase` (a typed word, normalized trimmed+lowercased so
  matching is **case-insensitive**; `matches(typed)`) and `Trigger.Keys` (an ordered run of GLFW key codes, matched
  live; `endsWith(history, n)`). Factories `Trigger.phrase(text)` / `Trigger.keys(int...)`.
- **`SecretAction`** — a `@FunctionalInterface` (`run(SecretContext)`) so a def writes its effect as a one-line lambda.
- **`SecretContext`** — the host seam the action acts through (the provider implements it, like `BiosContext`):
  `toggleSafeForRetro()`, `notice(msg)`, `launchSong(id)` (a hidden song), `launchGame(id)` (a hidden mini-game, e.g.
  `"snake"`). Small + ADDITIVE — add a method when a secret needs a new power. Defined here in `core` so `core` never
  depends on `bios`.
- **`SecretDef`** — the one-method interface (`Secret secret()`) each per-secret class under `defs/` implements; the
  unit the registry discovers. Needs a public no-arg constructor.
- **`SecretRegistry`** — discovers + matches. Holds the discovered `List<Secret>` + a rolling key-history ring; the
  provider owns one instance. `submit(typed, ctx)` runs the first matching `Phrase` secret (on ENTER); `feedKey(code,
  ctx)` pushes a key + fires any `Keys` secret whose run just completed (live); `resetKeys()` clears the ring.
- **`defs/`** — one `SecretDef` class per secret (the drop-in folder). See its own README.

## How it works
At provider construction `new SecretRegistry()` calls `discover()`: it reuses `SongLoader.listResources(defsDir,
"\\w+\\.class")` (the proven union/jar/dir resource enumeration) to list every `.class` in `core/secret/defs/`,
converts each path to its FQN, `Class.forName`s it, and — if it's a concrete `SecretDef` — instantiates it via the
no-arg constructor and collects `secret()`. A bad/abstract/null def is skipped; it logs `[bamfmenu] secrets: N
loaded`. At runtime the provider's `onKey`/`onChar` feed the box: typed characters build the phrase buffer
(`passwordInput`) and ENTER calls `submit` (case-insensitive phrase match → run the action, set its own notice, close
the box; no match → "Access denied"); every other key PRESS calls `feedKey` (rolling history → a completed key
sequence fires + closes the box). The matched secret's action runs against the provider's `secretCtx`
(`toggleSafeForRetro`/`notice`/`launchSong`).

## Boundaries
Depends on one sibling `core` package — `early.core.song` (`SongLoader.listResources` for the class scan) — plus the
JDK (reflection). It imports NOTHING from `bios/`: `SecretContext` is declared here and the provider (in `bios`)
implements it, so the core invariant holds (core knows nothing of the host; the host wires core into the box). No GL,
no Minecraft. The registry is the SOLE matcher; the provider only forwards input + supplies the `SecretContext`.
CAVEAT: typed letters reach `feedKey` too (every key press does), so a `Keys` secret built from letter codes could be
triggered mid-typing by a word containing them — prefer arrow-anchored or otherwise distinctive sequences (the bundled
Konami code is arrow-prefixed, so no typed word triggers it).
