# early/bios/transition/impl cutscene + lead-in clocks
## Features
The two transition clocks the BIOS host plays today: the Recovery-Mode intro `CutsceneTimeline` and the
song-start `LeadIn`. The *timing* of a transition lives here in one small, testable place, separate from the
art/draw/audio the owning code performs. Both are pure subclasses of `transition/PhaseTimeline`, no GL, no
audio, no state beyond the wall-clock start. Future transitions plug in as new subclasses without touching
the existing two. The `CutsceneTimeline` constants are preserved verbatim from the provider's old timing so
the cutscene's beats did not shift when the timing moved out.

## Components
- **`CutsceneTimeline`** is the 6-phase intro-cutscene clock: wipe -> clear-hold -> scramble -> settle ->
  face -> outro. Four public named boundaries are computed in the ctor via `phaseEnd(i)`. Timing only, the
  draw / jingle / stab-sync stay in the owning `CutsceneScreen`.
  - `settle` (2300): the header finishes decoding to "Unzipping...".
  - `faceStart` (2900): face animation + jingle begin.
  - `faceEnd` (5100): smile held, the outro scramble starts.
  - `outroEnd` (5600): hand off to the song.
- **`LeadIn`** is a single-phase clock for the song-start pre-roll, shared by RHYTHM + SPECIAL: the playfield
  shows with the freshly-played song paused at ~0 for `holdMs`, so the player registers the screen before
  the first note. The one ctor passes `new long[]{holdMs}` to `super(...)`, `done()` fires at that deadline.
  The provider owns the live `LeadIn` through a volatile field, the single source of truth for "is a lead-in
  active": the render thread builds/clears it and owns the audio pause/resume, `onKey` reads the volatile
  reference to gate input.

## How it works
Neither class adds behavior beyond construction, all the timing logic is inherited from
`transition/PhaseTimeline`. A subclass supplies an ascending array of cumulative phase-end times to
`super(...)`. The owning screen/provider calls `start(System.nanoTime())` once, then each render-thread
frame calls `phase(now)` to pick what to draw and `done(now)` to know when to hand off. `CutsceneTimeline`
also reads back its own boundaries via `phaseEnd(i)` to expose the named milestones the cutscene-draw code
branches on. All clocks are deliberately WALL-CLOCK with no freeze (the F2 overlay skips their *step* but
never advances their clock), so the phase indices track uninterrupted real time.

## Dependencies
- Depends on `transition/PhaseTimeline` (the abstract base), the only import in either file. No GL, no
  audio, no Minecraft, no `core/` types.
- Depended on by the `bios` host that constructs and plays these clocks, `CutsceneScreen` for the cutscene
  and the provider for the lead-in, driven from the render thread.
- These are timing-only types: they never draw, never touch audio, and hold no changing state beyond the
  inherited start time. The owning code performs all art and side-effects.
- New transitions belong here as additional `PhaseTimeline` subclasses without modifying the existing two.
