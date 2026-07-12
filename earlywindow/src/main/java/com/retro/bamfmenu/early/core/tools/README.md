# early/core/tools, creation/analysis machinery, not gameplay mechanics
## Features
The one home for packages that are tools: anything creation- or analysis-oriented (converters, generators,
analyzers, detector definitions, dev probes), as opposed to a gameplay mechanic or a runtime function, such as audio
playback being a function and stays in `core/audio`, or the rhythm engine is a mechanic and stays in
`core/rhythm`. Tools are not necessarily dev-only, runtime code may call into them. What makes something a tool is what it
is, not when it runs. The dev mains originally sat inside runtime packages; they live here now, in `probe/`
and `gen/`, so `core/`'s six category folders stay clean.

## Dependencies
- Tool packages may depend on runtime `core/` code (`gen/ChartBootstrap` -> the `rhythm/chart` pipeline,
  `probe/` -> `secret/doom/game` + `asset`), and runtime code may call a tool where its job genuinely is
  creation/analysis. Today: `rhythm/chart`'s `ChartRequest`/`DspCharter` carry and consume `SongAnalysis` +
  `DetectorConfig`, the `rhythm` song definitions hold a `DetectorConfig`, and
  `bios/modes/cutscene/CutsceneScreen`'s stab-sync calls `SongAnalysis.analyze` (the only `bios/` -> `tools/`
  contact).
- Plain playback (`core/audio`) needs nothing from here.
- Nothing here imports `bios/`. No GL, the probes rasterize offline via `java.awt`, headless.

## Child layers
- **`audio/`**: song analysis, definitions at the folder root (`DetectorConfig` + `SongAnalysis`) with the
  `dsp/` internals below. The charting pipeline and the cutscene stab-sync consume it.
- **`convert/`**: the osu!mania -> BAMF! song converter (`gradlew :earlywindow:importOsu`).
- **`gen/`**: the generators and song spike (`SongSpike` + `Xorshift64`,
  `gradlew :earlywindow:genSong`) and `ChartBootstrap` (`gradlew :earlywindow:genCharts`, emits the
  committed `normal.chart` files through the exact runtime charter).
- **`probe/`**: the 5 offline dev probes (4 DOOM + `AsciiImageProbe`), one gradle task each
  (`genDoomFrame`/`genDoomImage`/`genDoomMenu`/`genDoomHud`/`genWysi`).
