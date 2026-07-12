# early/bios/state BIOS save persistence
## Features
This layer owns how the BIOS persists its saved state across launches: unlocks, the Recovery/achievement
flags, the Safe-For-Retro switch, per-song high scores + grades, volume, theme, and lane keybinds. The save
is an obfuscated binary blob, deterrent only, no real security. The point is to keep it out of the `config/`
folder and out of Notepad's reach: it lives in the mod's own data directory as
`<gameDir>/BAMFOS/cache/bios.dat`. The de-scramble logic ships in this jar, so a determined user can still
decode it. There is no separate in-memory state object. The provider keeps its live fields, this layer is
purely the disk codec.

## Components
- **`BiosCache`** is a final, all-static utility. No GL or Minecraft references, safe in the service jar.
  Thread affinity is whoever the provider calls it from (the persistence path, not a hot render path). It
  keeps the in-memory shape as `java.util.Properties` so the provider's existing load/save logic is
  unchanged, only the on-disk encoding + location differ.
  - `load(gameDir)`: the stored `Properties`, or empty/defaults if nothing stored or the file is
    unreadable/foreign. Performs the one-time legacy migration.
  - `save(gameDir, props)`: writes the obfuscated blob, creating `BAMFOS/cache` on first save.
  - `delete(gameDir)`: Reset Cache, drops the stored state.
  - `cacheDir(gameDir)` / `cacheFile(gameDir)`: the path helpers.
  - `MAGIC = {0xB1, 0x05, 0x01}`: a 3-byte header so a stray/foreign file is rejected rather than
    mis-decoded. The scramble `KEY` ships in the jar.
  - the codec is a magic header + position-varying XOR. No checksum, integrity is intentionally absent.

## How it works
The provider serializes its live state into a `Properties` and calls `BiosCache.save(gameDir, props)`.
`save` stores the `Properties` to an in-memory buffer, runs `encode` over it, creates
`<gameDir>/BAMFOS/cache/` if needed, and writes `bios.dat`. On startup the provider calls `load(gameDir)`:
`decode` verifies the magic and reverses the XOR, a bad magic yields empty `Properties`. If `bios.dat` is
absent but the legacy plaintext `<gameDir>/config/bamfos_bios.properties` exists, `load` performs a one-time
migration: parse the old file, `save` the new blob, delete the old file, so existing unlocks/scores carry
over and no plaintext is left under `config/`. Every operation is best-effort, `Throwable` is swallowed so a
persistence failure never crashes the BIOS, and a failed `load` yields all-defaults.

## Dependencies
- Depends only on the JDK. No LWJGL, no Minecraft, no other `early/` package.
- The provider/host depends on it for all disk persistence, it never calls back.
- The on-disk file is opaque binary, never plaintext, never under `config/`. The magic header gates decoding
  so foreign files are rejected.
- Integrity checking is intentionally omitted, deterrent only.
