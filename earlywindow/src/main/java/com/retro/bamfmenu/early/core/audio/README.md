# early/core/audio, self-contained OpenAL playback for the pre-Minecraft window
## Features
Sound for the BIOS/BAMF! early window, which runs before Minecraft has initialized. It cannot touch
Minecraft's audio stack, so it decodes an OGG with STB Vorbis and plays it through its own OpenAL
device/context, then tears that down at the Minecraft hand-off so MC's own sound init is unaffected. No
Minecraft refs anywhere, so it survives in the service jar. Everything is best-effort: any failure logs and
degrades to "no audio" rather than breaking boot. Beyond playback it is the rhythm engine's master clock
(OpenAL `AL_SEC_OFFSET`).

This is pure playback. The song analysis that used to be fused in here moved to
`core/tools/audio/SongAnalysis`, so plain playback (menu music, the wysi clip) carries no detector machinery
and `bios/` never sees a `DetectorConfig`. `waveform()` stays here on purpose: it is cfg-free presentation
data (the rhythm header and the editor timeline draw it), not analysis.

## Components
- **`AudioPlayer`** is the one class in this layer. It owns the OpenAL `device`/`context`, the song
  `source`/`buffer`, and a second `sfxSource`/`sfxBuffer` pair in the SAME context, so a one-shot SFX mixes
  over the song instead of fighting it from a competing second context. The context is owned by the render
  thread, the thread that calls `play`/`playSfx`/`pause`/`resume`/`close`. `waveform` is a `volatile` array
  so other threads can read it.
  - `play(String resource)`: decode the OGG, open the device/context, upload, compute the waveform, start.
    Returns `false` and degrades on any failure.
  - `positionSeconds()`: the rhythm master clock (`AL_SEC_OFFSET`). `durationSeconds()` beside it.
  - `waveform()`: a 512-bucket 0..1 peak profile, computed once at decode time before the native PCM is
    freed.
  - `loadSfx(resource)` / `playSfx(gain)`: the one-shot channel, rewind + retrigger for back-to-back miss
    sounds. Load after a successful `play`.
  - transport: `setVolume`, `stop`, `pause`/`resume`. Pause preserves `AL_SEC_OFFSET` so the rhythm clock
    freezes and continues exactly. `resume` only un-pauses a genuinely paused source, never replays a
    stopped one.
  - `seekSeconds(sec)`: editor audition, `AL_SEC_OFFSET` is writable on our non-streaming buffer.
    `playFrom(sec)`: seek then play, so the editor's "play from the cursor" works from paused or stopped.
  - `close()`: full teardown of both source/buffer pairs, the context, and the device.
  - `readResource(String)`: a `"file:"` resource reads off disk (user songs under `<gameDir>/BAMFOS/songs/`),
    anything else is a bundled classpath resource. Public because `SongAnalysis` reuses this exact read,
    analysis sees the same bytes playback does.

## How it works
`play` calls `readResource` for the OGG bytes, decodes to PCM, computes duration + waveform, opens the AL
device/context, uploads, and starts the source (the decode buffers are freed in `finally`, AL copied them).
`bios/` reaches it through two `BiosContext` seams: `startSongAudio(SongDef)` for the rhythm/special/editor
screens (the provider closes the old player, plays the song, pauses at 0 for the lead-in) and
`playMusic(resource)` for plain music (jukebox preview, the cutscene jingle, tetris themes). From then on
gameplay polls `positionSeconds()` as its clock, draws `waveform()`, and drives the transport. At hand-off,
`close()` deletes everything and releases the device.

## Dependencies
- Depends on LWJGL only (OpenAL, STB Vorbis, native memory helpers). It imports nothing from `core/tools/`,
  the arrow points the other way: `tools/audio/SongAnalysis` imports `AudioPlayer.readResource`.
- No `bios/` classes, no Minecraft classes, so it stays valid in the service jar.
- Depended on by the `bios/` presentation host (the `startSongAudio`/`playMusic` seams and the screens'
  transport calls) and by `SongAnalysis` (the shared read only).
- The OpenAL context is render-thread-owned, and only one `AudioPlayer`/context is current at a time (the
  reason the SFX rides in the same context). Every public op is best-effort and swallows failures into
  `status`/"no audio". `close()` MUST run before the Minecraft hand-off.
