# early/core/special — the SPECIAL-mode gameplay seam (a song's bespoke side-game)

## Intent
A song can declare a SPECIAL mode: a distinct gameplay session, separate from the normal rhythm game, that
reuses the audio engine and the song's presets. This layer is **only the seam** — the contract a special must
satisfy and a no-op stand-in — so a song can declare a special *today* and have it actually launch (proving the
host + jukebox launch path) before any real per-song gameplay exists. Per the plan doc's , the
*declaration* and *framework* are deliberately data-driven and generic, while each special's actual gameplay/UI
is meant to be **bespoke code per song** (the real "FAR" special being the first to fill this in). The seam
returns text lines instead of drawing, so a session stays pure logic with no GL or Minecraft references — safe
to live in `core/` and inside the service jar.

## Components
- **`SpecialSession`** (interface) — the contract for one song's special-mode session. Designed to be hosted on
  the **render thread** exactly like the rhythm game: the host (provider / `SpecialScreen`) drives it each frame
  and draws what it returns. Methods: `init(SongDef song, AudioPlayer audio)` (called once on entry; the song's
  audio is already playing, so the session may read its clock / decode-time analysis off the `AudioPlayer`);
  `step(int songMs)` (advance one frame to the current song time in ms); `handleKey(long[] event)` (one
  timestamped `{key, action, nanoTime}` event from the input queue — ESC is the host's job, so a session never
  watches for it); `isDone()` (true when the host should return to BIOS); `render(int cols, int rows)` (the
  `String[]` lines to draw this frame, top to bottom, laid out to the current terminal grid — empty array draws
  nothing, and the host does the actual GL drawing).
- **`PlaceholderSpecial`** (`final class implements SpecialSession`) — the no-op stand-in. `init` only captures
  `song.name()`; `step` and `handleKey` do nothing; `isDone()` always returns `false` (so the session never
  ends itself — the host exits on ESC); `render` returns a fixed "coming soon" card (`SPECIAL MODE` / the song
  name / `( coming soon )` / `[ESC] Back to BIOS`). It exists so any song can carry a `special` and launch a
  real screen before its bespoke gameplay is written.

## How it works
This layer produces a contract, not a flow of its own. The host (the `SpecialScreen` / provider, in `bios/`)
obtains a `SpecialSession` for the chosen song, calls `init(song, audio)` once on the render thread, then each
frame calls `step(songMs)` with the current song time and `render(cols, rows)` and draws the returned lines;
queued key events arrive via `handleKey`. The host owns ESC (quit → back to BIOS) and checks `isDone()` to know
when to return on its own. `PlaceholderSpecial` satisfies the contract trivially: it draws a static card and
never reports done, so control stays in SPECIAL mode until the host catches ESC. Real per-song specials replace
the placeholder with bespoke logic that uses the `AudioPlayer` clock and the song's `SongDef` presets.

## Boundaries
- **Depends on:** only two `core/` siblings — `early.core.audio.AudioPlayer` and `early.core.song.SongDef`. No
  GL, no LWJGL, no Minecraft, no `bios/` imports.
- **Depended on by:** the SPECIAL-mode host in `bios/` (the `SpecialScreen` / provider) and `SongRegistry`,
  which today supplies a `SpecialSession` (e.g. a `PlaceholderSpecial`) for a song declaring `SongDef.special`.
- **Invariants:** `core/` never imports `bios/` (honored — this layer imports only `core.audio` / `core.song`);
  a `SpecialSession` is **render-thread-hosted** and stays GL-free by returning lines rather than drawing;
  sessions never handle ESC (the host does). Note: the plan doc's component table lists these classes under
  `early.game`, but in the current tree they live here in `early.core.special` — the code is authoritative.
