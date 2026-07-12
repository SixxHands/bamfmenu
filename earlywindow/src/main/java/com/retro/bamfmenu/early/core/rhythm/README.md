# early/core/rhythm, BAMF! song definitions + discovery at root, gameplay members
## Features
This is a `core/` category. `model/` keeps the definitions, `registry/` keeps discovery, and gameplay lives
in the sibling subfolders. `model/` is the song catalog: `SongDef`/`SongDifficulty`/`Difficulty` which say
what a song IS, while `registry/`'s `SongRegistry`/`SongLoader` say how songs are FOUND. `Note` is a shared note definition, the
direct analog of `SecretDef`/`SecretRegistry` in `core/secret`. A `SongDef` carries EVERYTHING about one song
in one place, not smeared across the provider as `"recovery".equals(...)` branches. The catalog is discovered
and not authored in Java: adding a built-in song is as easy as dropping only a folder with a `song.json` and OGG.
`Note` moved here from `RhythmEngine` because half its importers reached into the engine only to
name the type.

## Components
- **`SongDef`** is a `record`, one song's whole definition lives in one place. `newLeftPanel()`/`newSpecial()` build a
  FRESH instance per play so state never leaks across games.
  - `id`: high-score key + recovery check.
  - `order`: jukebox sort key, 0 reserved for Recovery.
  - `ogg`: a `/songs/...` resource path, `file:` disk path for user songs, null = audio-less, or the
    `SongLoader.MULTI_OGG`.
  - `bpm`: grid hint, 0 = auto-detect.
  - `detector`: `DetectorConfig` per-song auto-charter tuning.
  - `leftPanel`/`special`: suppliers, null = blank left cell / no special.
  - `difficulties`: `List<SongDifficulty>` never null.
- **`SongDifficulty`** is a `record(name, token, order, sensDelta)` with ONE difficulty a song offers. Data-driven so
  a song can declare any ordered named set (that way an osu!mania import keeps Beginner/Normal/Hyper/Another).
  - `token`: the chart filename stem AND the score-key suffix (`songs/<id>/<token>.chart`, `rhythmHigh.<id>.<token>`).
  - `sensDelta`: shifts auto-charter density only when GENERATING, a committed chart reads as-is.
- **`Difficulty`** is a fixed enum for the DEFAULT set: `EASY`/`NORMAL`/`HARD`/`SPECIAL`. `scored()` is false
  only for SPECIAL, which launches a `SpecialSession` instead of a chart, even though it can still use a chart.
- **`Note`** is the shared note definition: uses unchangeable `lane`/`timeMs`/`holdMs` definitions but with changeable judgment
  flags (`faded` = MISSED/DROPPED, renders greyed-out and lingers). Built by the `chart/` pipeline, changed via the
  engine only.
- **`SongRegistry`**, the static holder the provider iterates: `SONGS = SongLoader.discover()`, class-init runs
  bundled discovery once.
- **`SongLoader`**, discovery + the editor's disk engine. Enumeration is the hard part, everything else
  defaults instead of throwing.
  - `discover()`: enumerate every `/songs/<id>/song.json`, parse via the dependency-free `Json`, sort by
    `order` then `id`, pin Recovery to index 0 (the `SONGS[0]` rule).
  - `load(id)`: defaults every missing field (no `detector` -> `DetectorConfig.defaults()`), a bad descriptor
    drops only that song.
  - enumeration: a dev `file:` dir lists directly, but the packaged FML `union:` URL for `/songs` lists as
    null, so we resolve the containing jar and scan its ZIP entries. Strip ONLY a trailing `%23NNN`/`#NNN`
    union-index marker, and decode with our own RFC-3986 `decodePath`, `URLDecoder` maps `+` to space and
    throws on a lone `%`.
  - fallback: empty enumeration loads `FALLBACK_IDS` (`recovery`, `far`, `tetris`) by known path, so `SONGS` is
    never empty.
  - `BUILTINS`: literally just code that can't live in JSON, keyed by id (Recovery's `FaceFrameAnimation` from its frame
    `.txt`, `far`'s `PlaceholderSpecial`).
  - `loadDifficulties()`: declared `difficulties` array. What existing defaults chart + SPECIAL, behind
    a `chartExists`. Hides in-jar vs on-disk.
  - the classpath enumeration (`listResources`/`listResourcesDeep`) moved to `core/util/ResourceScan`, so the
    outside callers stopped reaching in here. It got that util folder after all.
  - disk: `discoverDisk()`/`loadDisk()` build `SongDef`s from `<gameDir>/BAMFOS/songs/` with
    `file:` OGG refs. No usable OGG = listed audio-less, more than one = the `MULTI_OGG` sentinel so the editor
    refuses, `INTRO_OGG` never counts.
  - scaffolding: `ensureSongScaffold()` idempotently completes a folder (full `song.json`, a blank
    `normal.chart`, an empty `panels/`), `ensureSongsRootReadme()` writes the one-time SIXX-HANDS readme (with
    the editor key map), `copyBundledSong()` forks an in-jar song folder to disk. All disk writes are add-only,
    never overwrite authored files, never throw.

## How it works
Class-load of `SongRegistry` runs `discover()` once: collect ids, parse each descriptor, merge `BUILTINS`,
sort, pin Recovery to 0, fall back to the hardcoded ids if nothing is found. From the `SONGS` array, declared songs take over. 
The jukebox/provider in `bios/` picks a `SongDef` + difficulty. `chart/` turns the committed `<token>.chart` 
(or at generation time the audio + `detector` shifted by `sensDelta`) into a `List<Note>`.
`engine/` judges those notes, delegating every grade to the `score/`. `panel/` animates
left cell activity. SPECIAL skips traditional charting entirely via `newSpecial()`. The editor does the
work on the disk surface: `discoverDisk()` for user songs, edits through `editor/` + `chart/`.

## Dependencies
- **Depends on:** The sibling categories `asset` (`Art`, `Json`) and `tools/audio`. `DetectorConfig` by the root. The
analysis itself runs in `SongAnalysis`, consumed by `chart/`, plus its own members `panel/`, `special/`, and
`chart/`. Also needs `Note`/`SongDef`, root to member acquaintance is expected INSIDE the category.
- Nothing under `rhythm/` imports `bios/`. There is no GL, no audio playback, no Minecraft anywhere in the category's core. 
- `bios/` provider, jukebox, and rhythm/editor screens, plus the known cross-cut: `core/secret` and
`TetrisScreen` borrow the `listResources*` enumeration. 
- `SONGS` are never empty, `SONGS[0]` is always the 'Recovery' song, discovery should never throw, `difficulties` never return or are null, 
folder ends with a complete `song.json` and at least a Normal chart.

## Child layers
- **`model/`**, the song catalog: `SongDef`, `SongDifficulty`, `Difficulty`, `Note`.
- **`registry/`**, discovery + the editor's disk engine: `SongRegistry`, `SongLoader`.
- **`engine/`**, the headless judging engine (`RhythmEngine`, `RhythmClock`, `JudgmentArt`).
- **`chart/`**, the chart seam: `Charter`/`Chart`/`ChartIO`/`ChartRequest` + the `impl/` charters.
- **`score/`**, the scoring/combo/multiplier/health economy the engine delegates every judgment to.
- **`panel/`**, the reactive left-panel animation framework.
- **`special/`**, the SPECIAL-mode seam, a song's bespoke side-game instead of a chart.
- **`editor/`**, the chart editor's headless core.