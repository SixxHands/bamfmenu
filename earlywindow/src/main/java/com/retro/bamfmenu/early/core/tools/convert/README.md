# early/core/tools/convert, dev tool: osu!mania beatmap -> BAMF! song folder
## Features
This layer answers one offline question: given an osu!mania beatmap, produce a song folder this game can already
read. It is a developer entry point (`main`), not runtime code, nothing in the running early window imports it. The
output is exactly the layout `SongLoader` discovers and the editor edits: `<out>/<id>/` holding a `song.json`, one
or more `<token>.chart` files, the audio (when usable), and an empty `panels/`, ready to drag into `BAMFOS/songs/`
(to chart/test) or `resources/songs/` (to bundle). It bridges a foreign format into our own by reusing our real chart
type (`ChartIO`/`Chart`/`core.rhythm.Note`) and our real id rule (`SongLoader.sanitizeId`), so an import is a
first-class song with no hand-fixups. It is pure stdlib (text parsing + `java.util.zip`) plus those core types, no
LWJGL, no Minecraft, so it runs on the plain runtime classpath via a Gradle task.

## Components
- **`OsuManiaImport`** is the whole tool, a `final` no-instance class with `main(String[])`. Args (Gradle `-P` flags):
  `osu` (a `.osz` zip, a single `.osu`, or an extracted folder), `out` (output root, default `build/osu-import`),
  `diffs` (comma list of osu `[Version]` names to keep), an explicit ffmpeg path, and `minHold` (short-hold cutoff,
  default `DEFAULT_MIN_HOLD_MS = 100`). `main` parses args defensively (`parseIntSafe`) and funnels into `run(...)`,
  catching every `Throwable` so a bad beatmap never crashes the build.
- **`Source`** is a private class holding a parsed input: the raw `.osu` texts plus the chosen audio. Audio is split
  by origin: `ogg`/`oggName` (bytes, copied straight through), `mp3Path` (disk source, transcoded in place),
  `mp3Bytes` (zip source, spilled to a temp file then transcoded), and `hadMp3`.
- **`Diff`** is a private `record`, one parsed mania difficulty: `title`, `artist`, `version` (the osu `[Version]`,
  used for `-Pdiffs` selection + the listing), `keyCount`, `bpm`, and `notes` (`List<Note>`).

## How it works
`run` reads the input by kind: `readOsz` walks a `.osz`'s ZIP entries, a folder/`.osu` is read directly and audio
found via `scanAudioDir`. Each `.osu` goes through `parse`, which is section-aware and tolerant (unknown keys ignored,
bad rows skipped) and returns `null` for anything that isn't `Mode: 3` (mania only).

`parseHit` maps one `[HitObjects]` row to a `Note`: `column = x*keyCount/512`, then `lane = column*4/keyCount` (4K is
identity, other key counts proportionally remapped to our 4 lanes, approximate). A type bit-128 long note carries
`endTime - time` as `holdMs`. `collapseShortHolds` then rewrites every hold shorter than `minHold` into a fresh tap
`Note` (holdMs 0), replaced in place because `Note.holdMs` is `final`. The note count is unchanged, so density tallies
stay valid. The cutoff exists because our judge's window (`RhythmEngine` OKAY/EARLY ≈ 100ms) makes a sub-window LN
indistinguishable from a tap and renders as a degenerate capsule.

Diffs are sorted by note count (ascending density), the available set is printed, and `select`/`findByVersion` filter
to `-Pdiffs` (exact match, then a `contains` fallback). Difficulty mapping is data-driven: ≤3 chosen diffs density-map
to `easy`/`normal`/`hard` (always producing the required `normal`) and `song.json` carries no `difficulties` array.
More than 3 ("OSU class") keeps every chosen diff as its own `<token>.chart` named for the slugged `[Version]`
(`slug` + `uniqueToken` for collisions) and writes a `difficulties` array so the jukebox shows the full custom set.

`writeChart` serializes via `ChartIO.write(new Chart(token, bpm, 0, 0, notes))`. The id comes from
`SongLoader.sanitizeId(title)`. Audio is resolved by `ensureAudio`: an OGG is copied as `<id>.ogg` (renamed off the
source's generic name so file/folder/`song.json` stay in sync and two imports never collide). An MP3 is transcoded by
`transcode` via `findFfmpeg` (override -> PATH -> winget/choco link dirs -> CapCut's bundled ffmpeg, newest first),
trying `libvorbis` then the native `vorbis` encoder. If no ffmpeg, the chart + `song.json` are still written and the
folder is left audio-less. Finally `song.json` is emitted (name, label, fixed `order: 1000`, ogg, bpm, optional
difficulties block, empty `detector`) and a summary with the mapping, bpm, key count, and audio status is printed.

## Dependencies
- Depends on two sibling `core` spots: the rhythm root (`Note`, `SongLoader.sanitizeId`) and
  `rhythm/chart` (`Chart`, `ChartIO`). It produces the exact artifacts those layers consume but adds no
  new shared types. It imports nothing from `bios/`.
- It is also one of the few `core` files that is not part of the running early window: no other class references
  `OsuManiaImport`, it is a build-time `main` invoked by `gradlew :earlywindow:importOsu`.
- Everything is pure stdlib + core types (no GL, no Minecraft), and the only outside contact is reading the input
  files, writing the output folder, and spawning `ffmpeg` via `ProcessBuilder` (whose stream it always drains so
  the child can't block).
- A missing/failed ffmpeg degrades to an audio-less folder rather than an error. Mania-only is enforced
  (`Mode != 3` diffs are dropped), and a malformed beatmap yields a printed message, never a thrown exception out
  of `main`.
