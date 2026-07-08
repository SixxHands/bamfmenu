# early/core/tuning — per-song auto-charter tuning, as a pure-data parameter object
## Intent
This leaf exists to give each song its OWN auto-charter settings instead of the global constants the rhythm
subsystem used before the fine-split refactor. Per the class's own header, `DetectorConfig` replaces a scatter
of literals: the provider's `RHYTHM_SENSITIVITY` + the `1200` chart lead-in, `ChartGenerator`'s `CD_FLOOR`,
`SpectralAnalyzer`'s `MEL_LO_HZ` / `MEL_HI_HZ` / `PERC_HI_HZ` bands, and the `RhythmEngine` `+1800` tail —
collapsed into one record threaded through the charter. The deliberate contract is that `defaults()` reproduces
EXACTLY the old global values, so any song that overrides nothing charts identically to pre-refactor behavior
(open/closed at the tuning level). The plan doc already names this
as the descriptor's future "autogen settings": `song.json`'s optional `detector` block is just these 8 fields,
each independently defaulting; and in the same object becomes the editor's "Generate" options menu, with
this preserved config as the worked example for which knobs to expose. It is `core/` because it is pure data with
no GL and no Minecraft — safe in the service jar and reusable by the editor.

## Components
- `DetectorConfig` — a Java `record` carrying the 8 tuning fields and nothing else (pure data, no thread
  affinity; immutable, freely shared). Fields: `sensitivity` (onset density / threshold), `leadInMs` (chart
  lead-in time gate — onsets before it are dropped), `cdFloor` (complex-domain phase-confidence veto floor;
  drone/reverb suppression, 0 disables), `melLoHz` / `melHiHz` / `percHiHz` (melodic + percussion band edges),
  `tailMs` (grace past chart end before the song counts as finished), and `minF0Hz` (HPS fundamental-pitch gate;
  drops onsets below it and re-routes lane pitch to the HPS fundamental + disables percussion fill, 0 = off).
  Static factory `defaults()` returns the historical globals: `(0.88f, 1200, 0.40f, 120.0, 5000.0, 6000.0, 1800,
  0.0)`. Copy-with helpers for the common per-song tweaks: `withSensitivity(float)`, `withMelodicBand(double lo,
  double hi)`, `withLeadInMs(int)`, `withMinF0Hz(double)` — each returns a new record with one field changed. A
  trailing comment notes BPM is deliberately NOT here: it lives on the `SongDef`, not the detector.

## How it works
A song produces one `DetectorConfig` (from `SongRegistry` / `song.json`'s `detector` block, falling back per-field
to `defaults()`) and passes it into two consumers, per the header: into `AudioPlayer` for the decode-time band
analysis (the `melLoHz` / `melHiHz` / `percHiHz` / `minF0Hz` fields drive which spectral bands are extracted),
and into `ChartGenerator` for the onset / veto / lead-in stage (`sensitivity`, `cdFloor`, `leadInMs`, `minF0Hz`),
while `tailMs` governs when the engine treats the song as finished. The `with*` helpers exist so a song can clone
`defaults()` and dial a single axis (the worked example in the comments is "Recovery": raise the melodic low edge
or set `minF0Hz` so the charter tracks the higher melody instead of the bit-crushed sub-band). Nothing in this
folder reads audio or draws — it only describes the parameters those other layers act on.

## Boundaries
Depends on nothing but the JDK (`java.lang.Record`) — no imports at all, no GL, no Minecraft, no `bios/`,
not even sibling `core/` packages. Upstream collaborators that hold/consume it live in other layers
(`early.song`'s `SongDef` / `SongRegistry` build it; `AudioPlayer` and `ChartGenerator` read it). Invariants:
`core/` never imports `bios/` (honored — this layer imports nothing); the record is immutable, so it is thread-safe
to share across the MAIN and RENDER threads without coordination; and `defaults()` must keep reproducing the
historical globals exactly, or unspecified songs would silently re-chart. Note the plan doc still lists
`DetectorConfig` under `early.dsp`; the actual code now lives here in `early.core.tuning` — this README documents
the real location.
