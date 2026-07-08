# early/core/audio — self-contained OpenAL playback + decode-time DSP feature extraction for the pre-Minecraft window
## Intent
This layer owns sound for the BIOS/BAMFBEAT early window, which runs *before* Minecraft has initialized. It cannot
touch Minecraft's audio stack, so it decodes a bundled OGG with STB Vorbis and plays it through its **own** OpenAL
device/context, then tears that down at the Minecraft hand-off so MC's own sound init is unaffected (the class
header is explicit about this and about staying Minecraft-ref-free so it survives in the service jar). Per its own
"SPIKE" comment, the original point was just to prove audio can run pre-MC and close cleanly; everything is
best-effort — any failure logs and degrades to "no audio" rather than breaking boot. Beyond playback it doubles as
the rhythm engine's **master clock** (OpenAL `AL_SEC_OFFSET`) and as the **DSP feature source** the auto-charter
consumes, computed once at decode time before the PCM is freed. It belongs in `core/` because it imports no `bios/`
GL/presentation classes — only LWJGL, `core.tuning.DetectorConfig`, and `core.dsp.SpectralAnalyzer`.

## Components
- **`AudioPlayer`** (`public final`) — the one class in this layer. Owns an OpenAL `device`/`context`, the song
  `source`/`buffer`, and a *second* `sfxSource`/`sfxBuffer` pair kept in the **same** context (so a one-shot SFX
  mixes over the song without a competing second context). Threading: the OpenAL context is owned by the **render
  thread**, which is the thread that calls `play`/`playSfx`/`pause`/`resume`/`close`; analysis getters return
  `volatile` arrays so other threads can read the charting features safely. Key methods:
  - `play(resource, DetectorConfig)` — decode OGG, open device/context, upload to the AL buffer, run all
    decode-time analysis, start playback. Returns `false` and degrades on any failure. The `DetectorConfig` drives
    the per-song band analysis so each song charts with its own settings.
  - `analyzeOnly(resource, DetectorConfig)` — the **headless** path: identical decode + analysis as `play` but
    *without* opening OpenAL or starting playback. Populates the same feature getters so a generator can chart a
    song with no audio device (the Phase-4 `genCharts` chart-bootstrap path). The instance is **not** `ready` for
    playback afterward.
  - `positionSeconds()` — the rhythm master clock (`AL_SEC_OFFSET`); `durationSeconds()`.
  - Analysis getters (all `volatile`-backed): `waveform()` (512-bucket header profile), legacy `onsets()`/`zcr()`/
    `hopMs()`, and the SuperFlux/SpectralAnalyzer front end `superflux()`, `centroid()`, `melodicOdf()`,
    `melodicPitch()`, `melodicF0()`, `percussionOdf()`, `cdConf()`, and `superfluxFrameMs()` (exact, drift-free
    frame spacing for non-44.1kHz rates).
  - SFX: `loadSfx(resource)` (decode a one-shot into the second source; call after a successful `play`),
    `playSfx(gain)` (rewind + retrigger, used for back-to-back miss sounds).
  - Transport/lifecycle: `setVolume(gain)`, `stop()`, `pause()`/`resume()` (preserve `AL_SEC_OFFSET` so the rhythm
    clock freezes and continues exactly), `isReady()`/`isPlaying()`, `status()`, and `close()` (full teardown of
    both source/buffer pairs, context, and device — called before the Minecraft hand-off).
  - Private helpers: `computeOnsets` (legacy RMS-flux + zero-crossing, one linear pass over channel 0),
    `computeWaveform` (peak-amplitude downsample), `readResource` (classpath OGG → native `ByteBuffer`), `fmt`.

## How it works
Two entry points share one decode-and-analyze core. `play` (live) and `analyzeOnly` (headless) both call
`readResource` to pull the OGG off the classpath, `STBVorbis.stb_vorbis_decode_memory` to get interleaved PCM, then
run `computeWaveform`, `computeOnsets`, and `SpectralAnalyzer.analyze(pcm, channels, frames, sampleRate, cfg)` —
storing the results in the `volatile` feature fields *before* the native PCM is freed in the `finally` block. `play`
additionally opens the AL device/context, uploads the PCM with `alBufferData`, and starts the source. From then on
the rhythm screen polls `positionSeconds()` as its clock, reads the analysis getters once to build the chart, draws
the `waveform()`, and drives `pause`/`resume`/`setVolume`/`playSfx` from gameplay. At hand-off, `close()` deletes
everything and releases the device. The auto-charter only ever *reads* the feature getters; it never drives the
device.

## Boundaries
Depends on: LWJGL (`openal.AL`/`ALC`/`AL10`/`AL11`/`ALC10`, `stb.STBVorbis`, `system.MemoryStack`/`MemoryUtil`/
`LibCStdlib`), `core.tuning.DetectorConfig`, and `core.dsp.SpectralAnalyzer` (which produces the `Result` record
unpacked into the feature fields). Imports **no** `bios/` classes and **no** Minecraft classes — the core/-never-
imports-bios/ invariant holds, and the no-Minecraft-refs rule keeps it valid in the service jar. Depended on by the
`bios/` presentation host (the rhythm/special/cutscene screens, via the `BiosContext` audio seam) and by the
headless `genCharts` chart generator. Invariants: the OpenAL context is render-thread-owned; only one
`AudioPlayer`/context is current at a time (the reason the SFX rides in the same context); `analyzeOnly` instances
are read-only (never `ready`); every public op is best-effort and swallows failures into `status`/`no audio`.
