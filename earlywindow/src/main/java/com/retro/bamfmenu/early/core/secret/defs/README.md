# early/core/secret/defs — one class per secret (drop-in, auto-discovered)

## Intent
This is the data-driven "folder of secrets": each secret is ONE small class here implementing `SecretDef` and
returning a `new Secret(...)`. `SecretRegistry` scans this package's `.class` files and instantiates each — so adding
a secret is literally **drop a file here, done** (no registration, no central list, no edit anywhere else). See the
parent `early/core/secret` README for the machinery.

## Adding a secret
Create `<Name>Secret.java` with a public no-arg class implementing `SecretDef`:

```java
public final class MySecret implements SecretDef {
    @Override public Secret secret() {
        // a typed PHRASE (case-insensitive), entered + ENTER in the Safe-For-Retro box:
        return Secret.phrase("my-secret", "open sesame", ctx -> ctx.notice("It opened."));
    }
}
```
or a KEY SEQUENCE (Konami-style, matched live as you press the keys — GLFW codes):
```java
return new Secret("reveal", Trigger.keys(265, 265, 264, 264), ctx -> ctx.launchSong("hidden-song-id"));
```
The action does whatever the `SecretContext` exposes (`toggleSafeForRetro` / `notice` / `launchSong`); add a method
to `SecretContext` (+ implement it on the provider) if you need a new power.

## Components
- **`SafeForRetroSecret`** — the phrase `password` → `ctx.toggleSafeForRetro()`. The classic Easter egg, now a
  data-driven secret (the template for a PHRASE secret).
- **`KonamiSecret`** — the Konami code (↑↑↓↓←→←→ B A) → a flavour `notice`. The template for a KEY-SEQUENCE secret;
  swap the codes + action (e.g. `ctx.launchSong("<id>")`) to reveal a hidden song.
- **`SnakeSecret`** — the phrase `snake` → `ctx.launchGame("snake")`, launching the hidden Snake game. The template
  for a secret that reveals a hidden GAME (a new game = its Screen + one case in the provider's `launchGame`).
- **`GalagaSecret`** — the phrase `galaga` → `ctx.launchGame("galaga")`, launching the hidden Galaga game (full
  fixed-shooter with the capture-beam / dual-fighter; `core.galaga.GalagaGame` + `bios.screen.mode.GalagaScreen`).

## Notes
- **Case** doesn't matter for phrases (normalized lowercase). Phrases match the WHOLE typed buffer (exact word, not a
  substring), on ENTER.
- **Hidden song:** `launchSong(id)` plays any song by id (built-in or on-disk user song); to make a song "hidden"
  from the jukebox, that's a separate jukebox filter — `launchSong` itself doesn't care whether it's listed.
- **Sequence collisions:** every key press (including typed letters) feeds the live sequence matcher, so a sequence
  built from letter codes can fire mid-typing — prefer arrow-anchored / distinctive sequences.
- **Boundaries:** keep GLFW key codes inlined here (don't import LWJGL into `core`); the `KonamiSecret` shows the
  pattern (`private static final int UP = 265, ...`).
