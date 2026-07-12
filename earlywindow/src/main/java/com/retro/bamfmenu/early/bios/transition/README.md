# early/bios/transition wall-clock phase timing
## Features
The single, shared "what time is it in the sequence" core for every timed BIOS transition: the Recovery-Mode
intro cutscene, the song-start lead-in, and any future screen-to-screen wipes or pre-rolls. Timed sequences
run on one clock instead of scattered constants and ad-hoc elapsed-time math in the provider. It is
deliberately WALL-CLOCK with no freeze/pause: the cutscene + lead-in run on uninterrupted real time and the
F2 overlay skips their *step* but never advances their *clock*, so a freezable clock would shift their
timing. The phase boundaries are lifted verbatim from the provider's old `computeCutsceneTimeline`, so
behavior is preserved.

## Components
- **`Transition`** is the interface for a timed sequence. Pure timing, no GL/audio. The owning screen draws
  each phase and owns any audio side-effects, the transition only tells time.
  - `start(long nowNanos)`: begins at a supplied clock reading, the provider passes `System.nanoTime()`, so
    the type stays pure and testable.
  - `elapsedMs(long)` / `phase(long)`: elapsed time and the 0-based phase index. The index equals the phase
    count once every phase has elapsed.
  - `done(long)`: true once the last phase has elapsed, the owning code hands off or tears down.
- **`PhaseTimeline`** is the abstract `Transition` implementation, the reusable core both impls share.
  Backed by a `long[] phaseEndsMs` of cumulative phase-end times plus a start stamp.
  - implements `start`, `elapsedMs`, `phase` (linear scan for the first end the elapsed time hasn't
    reached), and `done`.
  - adds `started()` and a `protected phaseEnd(int i)` so subclasses can expose named boundaries.
  - no GL, no audio, no state beyond the start time.

## How it works
The owning code (`CutsceneScreen` for the cutscene, the provider for the lead-in) constructs a
`PhaseTimeline` subclass, calls `start(System.nanoTime())` once, then each render frame queries `phase(now)` /
`done(now)` to decide what to draw and when to hand off. The timeline produces only an index and a done-flag.
It consumes only the caller's clock reading. `CutsceneTimeline` also precomputes named boundary fields
(`settle`, `faceStart`, `faceEnd`, `outroEnd`) via `phaseEnd(i)` so the drawing code can sync art/jingle stabs
to specific beats. `LeadIn` is a single-phase `[holdMs]` timeline whose *liveness* (not its clock) is the
source of truth for "is a lead-in active": the provider keeps it in a volatile field, the render thread
builds/clears it and owns the audio pause/resume, and `onKey` reads the volatile reference to gate input.

## Dependencies
- Depends on nothing but `java.lang`: no GL, no audio, no Minecraft, no other `early/` package.
- The clock is injected, never read internally, so the layer is headless and editor-reusable.
- It physically sits under `bios/` but keeps the `core never imports bios` discipline, it is the timing
  primitive the presentation host leans on.
- Depended on by the provider / cutscene + game screens, which import the subclasses in `impl/`.
- Timelines are WALL-CLOCK with no freeze, `start()` must be called before time queries mean anything,
  `phaseEndsMs` is cumulative and ascending.

## Child layers
- `impl/`, the subclasses: `CutsceneTimeline` (6-phase Recovery-Mode intro: wipe -> clear-hold ->
  scramble -> settle -> face -> outro, boundaries verbatim from the old `computeCutsceneTimeline`) and `LeadIn`
  (single-phase song-start hold).
