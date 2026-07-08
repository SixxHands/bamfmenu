# early/bios/state — on-disk persistence for the BIOS's saved state
## Intent
This layer owns how the BIOS persists its saved state across launches: unlocks, the Recovery/achievement
flags, the Safe-For-Retro switch, per-song high scores + grades, volume, theme, and lane keybinds. It came
out of .5.2 ("Obfuscated binary cache, out of `config/`"), where the pack's pick was a plain
obfuscated binary blob — opaque and not casually tamperable, but deterrent-level only (no real security).
The deliberate design is to keep the save **out of the `config/` folder** and out of Notepad's reach: it
lives in the mod's own data directory as `<gameDir>/BAMFOS/cache/bios.dat`. The de-scramble logic ships in
this jar, so a determined user can still decode it — the goal is "out of normal eyesight + not casually
tamperable", not security. Note the related .5 item — a separate in-memory `BiosStateStore` object —
was **deferred**; the provider still keeps its live fields, so this layer is purely the disk codec, not an
in-memory state model.

## Components
- `BiosCache` — final, all-static utility (private ctor; no instances). No GL or Minecraft references, so it
  is safe in the service jar; thread affinity is whoever the provider calls it from (the persistence path,
  not a hot render path). It keeps the in-memory shape as `java.util.Properties` so the provider's existing
  load/save logic is unchanged — only the on-disk encoding + location differ.
  - Public API: `load(String gameDir)` → `Properties` (empty/defaults if nothing stored or the file is
    unreadable/foreign; performs the one-time legacy migration); `save(String gameDir, Properties)` (writes
    the obfuscated blob, creating `BAMFOS/cache` on first save); `delete(String gameDir)` (Reset Cache —
    drops the stored state); plus path helpers `cacheDir(gameDir)` and `cacheFile(gameDir)`.
  - Format constants: `MAGIC = {0xB1, 0x05, 0x01}` (3-byte header so a stray/foreign file is rejected rather
    than mis-decoded) and a fixed scramble `KEY` (`"b4mf0s.k3y.v1"`, ASCII) that ships in the jar.
  - Private codec: `encode`/`decode` — magic header + position-varying XOR (`byte ^ KEY[i % KEY.length] ^
    (i * 31)`). No checksum/signature; integrity is intentionally absent.

## How it works
The provider serializes its live state into a `Properties` and calls `BiosCache.save(gameDir, props)`. `save`
stores the `Properties` to an in-memory byte buffer (`props.store`), runs `encode` over it (prepend `MAGIC`,
then position-varying XOR), creates `<gameDir>/BAMFOS/cache/` if needed, and writes `bios.dat`. On startup
the provider calls `load(gameDir)`: if `bios.dat` exists it is read, `decode` verifies the magic and reverses
the XOR (returning `null` — and thus empty `Properties` — if the magic does not match), and the plaintext is
parsed back into `Properties`. If `bios.dat` is absent but the legacy plaintext
`<gameDir>/config/bamfos_bios.properties` exists, `load` performs a **one-time migration**: it parses the old
file, calls `save` to write the new `bios.dat`, then deletes the old file so existing unlocks/scores carry
over and no plaintext is ever left under `config/`. Every operation is best-effort — `Throwable` is swallowed
so a persistence failure never crashes the BIOS; a failed `load` simply yields all-defaults.

## Boundaries
Depends only on the JDK (`java.io`, `java.nio.file`, `java.util.Properties`, `StandardCharsets`) — no LWJGL,
no Minecraft, no other `early/` package, and notably it does not import `bios/` presentation classes. It is a
pure `core`-style leaf physically housed under `bios/state` (it is the codec the BIOS host calls; it does not
call back). The provider/host depends on it for all disk persistence. Invariants: the on-disk file is opaque
binary, never human-readable plaintext, and never under `config/`; the magic header gates decoding so foreign
files are rejected; integrity checking is intentionally omitted (deterrent only).
