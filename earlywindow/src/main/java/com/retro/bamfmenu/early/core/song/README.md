# early/core/song — the catalog of playable songs, data-driven from `/songs/<id>/song.json` (+ on-disk user songs)

## Intent
This layer answers one question for the rest of the early window: *what songs exist and what does each one carry?*
A `SongDef` is a single song's full self-description — identity, audio path, per-song auto-charter tuning, its
data-driven difficulty set, and its bespoke code (reactive left panel + optional SPECIAL session). It deliberately
replaced the old 5-field `Song` record so a song holds EVERYTHING about itself in one place instead of being smeared
across the provider with `"recovery".equals(...)` branches and global constants. As of **** the catalog is no
longer authored in Java: `SongLoader.discover()` walks the bundled `/songs/<id>/song.json` descriptors and builds the
`SongDef[]`, so adding a built-in song is "drop a folder with a `song.json` + an OGG" — no Java edit. **** adds
the editor surface: `discoverDisk()` loads USER songs from `<gameDir>/BAMFOS/songs/`, and a folder is scaffolded into a
complete, portable, data-driven song on disk. Everything here is pure stdlib + the core
`song`/`tuning`/`panel`/`special`/`asset`/`json`/`rhythm.chart` types (no GL, no Minecraft), so it is safe to run
pre-Minecraft on any thread.

## Components
- **`SongDef`** — a `record` holding one song's full definition: `id` (high-score key + recovery check, never rename),
  `name`, `label`, `order` (jukebox sort key; 0 reserved for Recovery), `ogg` (resource path, a `file:` disk path, the
  `null` audio-less marker, or the `SongLoader.MULTI_OGG` sentinel), `bpm` (grid hint; 0 = auto-detect),
  `detector` (`DetectorConfig` for the per-song auto-charter), two suppliers — `leftPanel` (`Supplier<LeftPanelTrack>`,
  null = blank left cell) and `special` (`Supplier<SpecialSession>`, null = none) — and `difficulties`
  (`List<SongDifficulty>`, never null, empty = none). Methods: `newLeftPanel()` / `newSpecial()` build a FRESH instance
  per play so animation/session state never leaks across games; `difficulty(token)` finds an offered difficulty by token
  (case-insensitive, null if absent); `dir()` returns the song's `/songs/<id>/` resource folder.
- **`SongDifficulty`** — a `record(name, token, order, sensDelta)` for ONE difficulty a song offers, DATA-DRIVEN so a
  song can declare any ordered named set (e.g. an osu!mania import's Beginner/Normal/Hyper/Another), not just three
  slots. `token` is the chart filename stem AND score-key suffix (`songs/<id>/<token>.chart`, `rhythmHigh.<id>.<token>`);
  `sensDelta` shifts auto-charter density only when GENERATING a chart (a committed chart reads as-is). Convenience ctor
  defaults `sensDelta` to 0; `isSpecial()` is true when `token` is `"special"`.
- **`Difficulty`** — the fixed enum used as the DEFAULT difficulty set (and for scaffolding): `EASY`, `NORMAL`, `HARD`,
  `SPECIAL`. Each carries `label`, `token`, and `sensDelta` (Easy `-0.12`, Normal `0`, Hard `+0.08`, Special `0`).
  `scored()` returns true for everything except `SPECIAL`, which launches a `SpecialSession` instead of a chart. A song
  with no declared `difficulties` array falls back to the EASY/NORMAL/HARD whose `<token>.chart` actually exist (+ SPECIAL
  if the song has a special session).
- **`SongRegistry`** — the static holder the provider iterates. A `final` no-instance class exposing one field,
  `public static final SongDef[] SONGS = SongLoader.discover()`. Its class-init does the bundled-song discovery once.
- **`SongLoader`** — the discovery + editor engine (no-instance utility). Bundled side: `discover()` enumerates every
  `/songs/<id>/song.json`, parses each via the dependency-free `Json` reader into a `SongDef`, sorts by `order` then
  `id`, and pins Recovery to index 0 (the `SONGS[0]` contract). `load(id)` defaults every field (missing `detector` or
  any field falls back to `DetectorConfig.defaults()`) so a partial/malformed descriptor never throws — it just drops
  that one song. Enumeration is the hard part: `songIds()` lists a `file:` dev-exploded dir directly, but in the packaged
  FML module layer `/songs` resolves to a cpw `union:` URL where listing returns null — so it resolves the containing jar
  on disk (`jarFileFromUrl` / `jarFileFromCodeSource`, stripping a trailing `%23NNN`/`#NNN` union-index marker, with a
  custom RFC-3986 `decodePath` that, unlike `URLDecoder`, neither maps `+` to space nor throws on a lone `%`) and scans
  its ZIP entries (`scanJar`). If enumeration finds nothing, `FALLBACK_IDS` (`recovery`, `far`, `tetris`) load by known
  path so `SONGS` is never empty. Code that can't live in JSON — a reactive panel and/or SPECIAL supplier — lives in the
  `BUILTINS` map keyed by id (`recoveryFace()` builds a `FaceFrameAnimation` from Recovery's frame `.txt`; `far` gets a
  `PlaceholderSpecial`). `loadDifficulties()` produces the data-driven set (declared `difficulties` array, else existing
  charts + SPECIAL). `listResources(dir, regex)` reuses the same union/jar enumeration to list classpath files by name
  pattern (e.g. the Tetris background tracks). **editor/on-disk surface:** `discoverDisk(songsDir)` + `loadDisk()`
  build `SongDef`s from `<gameDir>/BAMFOS/songs/` with `file:`-prefixed OGG refs (audio-less folders list with `ogg ==
  null`; a folder with >1 song OGG returns the `MULTI_OGG` sentinel so the editor refuses it; `INTRO_OGG` is excluded
  from the count and never chosen). `ensureSongScaffold()` idempotently completes a folder (full `song.json`, a guaranteed
  blank `normal.chart` via `ChartIO.write`/`Chart`, an empty `panels/`), `ensureSongsRootReadme()` writes a one-time
  SIXX-HANDS readme (with editor key map) into the songs root, `copyBundledSong()`/`bundledSongFiles()` fork a whole
  in-jar song folder to disk, and helpers `sanitizeId()`, `patchOggField()`, `firstOgg()`/`songOggs()`, `readBpm()`,
  `jsonEscape()` support the editor. All writes are non-destructive (add-only, never overwrite authored files).

## How it works
At class-load, `SongRegistry.SONGS` triggers `SongLoader.discover()` exactly once. `discover()` calls `songIds()` to
collect folder ids (dev: list the `file:` dir; packaged: scan the jar's ZIP entries for `songs/<id>/song.json`), then
`load(id)` parses each descriptor through `Json` into a `SongDef`, merging any `BUILTINS` panel/special suppliers by id
and computing its `difficulties` via `loadDifficulties`. The list is sorted by `order` then `id`, Recovery is moved to
index 0, and (if enumeration yielded nothing) the hardcoded fallbacks load by known path. The result is the immutable
`SONGS` array the jukebox/provider read; from there the gameplay code asks a chosen `SongDef` for its `ogg`, `detector`
(handed to the auto-charter), its `difficulties`, and — per play — a fresh `newLeftPanel()` / `newSpecial()`. The editor
calls `discoverDisk()` for user songs and `ensureSongScaffold()` / `copyBundledSong()` / `patchOggField()` to create and
maintain folders on disk. A `SongDifficulty.sensDelta` shifts the song's `detector` sensitivity only at chart-GENERATION
time; SPECIAL bypasses charting entirely.

## Boundaries
Depends only on sibling `core` packages: `early.core.tuning` (`DetectorConfig`), `early.core.special` (`SpecialSession`,
`PlaceholderSpecial`), `early.core.panel` (`LeftPanelTrack`, `LeftPanelAnimation`, `FaceFrameAnimation`),
`early.core.asset` (`Art`), `early.core.json` (`Json`), and `early.core.rhythm.chart` (`Chart`, `ChartIO`, for the
scaffolded blank chart). It imports nothing from `bios/`, holding the core invariant that `core/` never depends on the
presentation host. Everything here is pure stdlib + core types — no GL, no Minecraft — so discovery is safe pre-Minecraft
and the `SONGS` list can be built on any thread (in practice once, at registry class-init); the file writes are
best-effort and never throw (callable on the render thread). The provider/jukebox/editor in `bios/` depend on this layer
(they read `SongRegistry.SONGS`, consume `SongDef`/`SongDifficulty`/`Difficulty`, and drive the on-disk helpers); this
layer depends on none of them. Invariants enforced here: `SONGS` is never empty, `SONGS[0]` is always Recovery, discovery
never throws (a bad descriptor drops only itself), `difficulties` is never null, and every scaffolded song folder ends up
with a complete `song.json` and a Normal chart.
