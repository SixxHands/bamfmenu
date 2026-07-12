# early/core/tools/gen, generative-song spike + deterministic PRNG
## Features
`SongSpike.main` is a standalone command-line renderer (`gradlew :earlywindow:genSong [out] [seed] [prog|static]`)
that synthesizes a full ~1:56 A-minor track to a 16-bit mono WAV. `Xorshift64` is the tiny reproducible PRNG every
voice draws from so the same seed yields a byte-identical song across JVMs. Nothing in `bios/` or the live menu
references either class, so this package ships as harmless dead code and it never runs unless the gradle task is
invoked. It may eventually be refactored into a `SongGenerator`/`ToneGen`. It is pure Java + stdlib only, 
so it is pretty much headless `core` logic. In practice it is only run from a build task.

## Components
- **`Xorshift64`** is a hand-rolled 64-bit Marsaglia xorshift PRNG (shifts `13/7/17`), and is NOT
  `java.util.Random` so output is byte-identical across JVMs. Ctor guards the zero state (a xorshift fixed point)
  by substituting `0x9E3779B97F4A7C15L`. `nextLong()` is the raw step; `nextInt(bound)` returns `[0,bound)` from
  the high bits (returns `0` if `bound <= 0`); `nextFloat()` returns `[0,1)`. `spawn(id)` forks an independent
  sub-stream mixed off the current state (`s * 0x9E3779B97F4A7C15L + id`) so each voice/layer draws without
  perturbing the others. The spike uses it for `leadRng = rng.spawn(0x1EAD7L)` so new lead-rhythm draws don't
  shift the motif rng's draw order.
- **`SongSpike`** is a `final`, no-instance class whose only entry point is `main(String[])`. Args:
  `out` (WAV path, default `song_spike.wav`), `seed` (default `0x1A2B3C4DL`), and a harmony toggle:
  `"static"` collapses the progression to a single A-minor pedal, anything else keeps the `prog` Am-F-C-G half-time.
  Fixed musical constants: `SR` 44100, `BPM` 132, `STEPS_PER_BAR` 16, `BARS` 64, `SECTION_BARS` 8,
  `BARS_PER_CHORD` 2. It renders the whole song into a `short[] pcm` then calls `writeWav`.

## How it works
`main` computes `samplesPerStep` from BPM/SR and runs one bar to step to sample triple loop over a single master
clock (`idx`). Every musical and timbral rate is an integer division of it, so all layers phase-lock. 
The LEAD is a generative 16th-note line: a `LEAD_TABLE` of rest + A/C/E chord tones, an 8-cell pitch `motif` is 
regenerated per 8-bar phrase via `rng` (smooth ±1 voice-leading) and nudged on alternate bars, 
plus per-bar onset masks chosen by a per-section `MODE_BY_SECTION` treatment
(`MODE_THEME/DEVELOP/LIFT/PULLBACK/CLIMAX/ENDING`) from the `RUN_MASKS`/`LITE_MASKS` pools. Duration is the
inter-onset gap, rests live in the mask. `octBySection` lifts whole sections by octaves (with bounds-safe fold at
`LEAD_HI`), and the bar's last onset is forced to an antecedent/consequent cadence. The song's components are a sub drone, 
a glided 3-saw `pad` over `CHORDS`, a `BASS_ONSET` tresillo pluck, a four-on-floor kick, and a deep detuned-saw layer.
The whole `barPos`-driven arrangement arc (deep gain, groove gate, lead-gain envelope, clarity notch, outro) is
computed per sample via `ramp`. Voices sum through a beat-locked sidechain `duck`, a one-shot resonant Chamberlin
SVF `sweep` (opens then closes), and a ducked feedback delay. The master is `tanh` soft-clip + `fadeIn`/`fadeOut`
edge fades. Oscillators (`saw`/`pulse` with `polyBlep`, `triangle`), `adsr`, and `midiToFreq` are private DSP
helpers. `writeWav` hand-emits a little-endian RIFF/WAVE PCM header.

## Dependencies
- This package depends on NOTHING else: not other `core` packages, not `bios/`, only `java.io`/`java.nio`
stdlib. `Xorshift64` has no imports at all. It is a pure leaf of the `core` tree, so the never-import-`bios/`
invariant keeps for free. 
- The dependency is also empty in the OTHER direction: no live early-window code SHOULD EVER reference
`SongSpike` or `Xorshift64`, so this layer is reachable only through the `genSong` gradle task and is otherwise
inert. Determinism is the rule: identical (seed, harmony) args produce byte-identical PCM, because all
randomness flows through `Xorshift64` and its `spawn` sub-streams.
