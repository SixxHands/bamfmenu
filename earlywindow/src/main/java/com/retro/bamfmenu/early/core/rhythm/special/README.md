# early/core/rhythm/special SPECIAL-mode gameplay
## Features
A song can declare a SPECIAL mode which is a distinct gameplay session, separate from the normal rhythm game, that
reuses the audio engine and the song's presets/chart. A song can declare a special today and have it actually launch before any real
per-song gameplay exists. The declaration and framework are data-driven and generic, 
each special's actual gameplay/UI is unique per song. The provider returns text
lines instead of drawing, so a session can stay pure logic. The declaring `SongDef` lives in the rhythm `model/`.

## Components
- **`SpecialSession`** is one song's special-mode session. Hosted on the render thread exactly like the rhythm
  game: the host (provider / `SpecialScreen`) drives it each frame and draws what it returns.
  - `init(SongDef song, AudioPlayer audio)`: called once on entry. The song's audio is already playing, so the
    session may read its clock, `positionSeconds()`, and the cfg-free `waveform()` off the `AudioPlayer`.
    Decode-time analysis is gone, the AudioPlayer split moved it to `core/tools/audio` `SongAnalysis`.
  - `step(int songMs)`: advance one frame to the current song time in ms.
  - `handleKey(long[] event)`: one timestamped `{key, action, nanoTime}` event from the input queue. ESC is the
    host's job, so a session never watches for it.
  - `isDone()`: true when the host should return to BIOS.
  - `render(int cols, int rows)`: the `String[]` lines to draw this frame, top to bottom, laid out to the
    current terminal grid. Empty array draws nothing, and the host does the actual GL drawing.
- **`PlaceholderSpecial`** is the no-op stand-in. `init` only captures
  `song.name()`; `step` and `handleKey` do nothing; `isDone()` always returns `false` (so the session never
  ends itself, the host exits on ESC); `render` returns a fixed "coming soon" card (`SPECIAL MODE` / the song
  name / `( coming soon )` / `[ESC] Back to BIOS`). It exists so any song can carry a `special` and launch a
  real screen before its bespoke gameplay is written.

## How it works
This layer produces an interface. The host (the `SpecialScreen` / provider, in `bios/`)
obtains a `SpecialSession` for the chosen song, calls `init(song, audio)` once on the render thread, then each
frame calls `step(songMs)` with the current song time and `render(cols, rows)` and draws the returned lines.
Queued key events arrive via `handleKey`. The host owns ESC (quit -> back to BIOS) and checks `isDone()` to know
when to return on its own. `PlaceholderSpecial` satisfies the interface trivially: it draws a static card and
never reports done, so control stays in SPECIAL mode until the host catches ESC. Real per-song specials replace
the placeholder with bespoke logic that uses the `AudioPlayer` clock and the song's `SongDef` presets.

## Dependencies
- **Depends on:** only `early.core.audio.AudioPlayer` (a sibling `core/` category) and the rhythm `model/`'s
  `SongDef`.
- **Depended on by:** the SPECIAL-mode host in `bios/` (`bios/modes/special/SpecialScreen`, hosted by the
  provider) and the rhythm model: `SongDef.special` is a `Supplier<SpecialSession>` (`newSpecial()` builds
  fresh per entry), and `SongLoader`'s `Builtin` map supplies the sessions (today `PlaceholderSpecial::new`
  for `far`).
- `core/` never imports `bios/`.
- A `SpecialSession` is render-thread-hosted and stays GL-free by returning lines rather than drawing.
  Sessions never handle ESC (the host does).
