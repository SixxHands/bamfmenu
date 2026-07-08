# early/bios/transition — wall-clock phase-timing for BIOS sequences (cutscene, song lead-in, future wipes)
## Intent
This layer is the single, shared "what time is it in the sequence" core for every timed BIOS transition: the
Recovery-Mode intro cutscene, the song-start lead-in, and any future screen-to-screen wipes / pre-rolls. It
exists so timed sequences run on **one** clock abstraction instead of scattered `CS_*` constants and ad-hoc
elapsed-time math in the provider. It is deliberately **WALL-CLOCK with NO freeze/pause**: per the code's own
comment, the cutscene + lead-in run on uninterrupted real time and the F2 overlay skips their *step* but never
advances their *clock*, so a freezable clock would shift their timing. The package came from **.75 step
8** (the "unified home for cutscene timing + the lead-in", owner chose BUILD NOW), with the concrete
boundaries lifted verbatim from the provider's old `computeCutsceneTimeline` so behaviour is preserved.

## Components
- `Transition` (interface) — the contract for a timed sequence. Pure timing, no GL/audio: `start(long nowNanos)`
  begins at a supplied clock reading (the provider passes `System.nanoTime()`, keeping the type pure and
  testable); `elapsedMs(long)`, `phase(long)` (0-based index; equals the phase *count* once every phase has
  elapsed), and `done(long)` (true once the last phase has elapsed → owner hands off / tears down). The owning
  screen draws each phase and owns any audio side-effects; the transition only tells time.
- `PhaseTimeline` (abstract, implements `Transition`) — the reusable core both impls share. Backed by a
  `long[] phaseEndsMs` of **cumulative** phase-end times (ascending; last entry = total duration) plus a
  `startNanos`/`started` start stamp. Implements `start`, `elapsedMs` (`(now − start)/1e6`), `phase` (linear
  scan for the first end the elapsed time hasn't reached), and `done` (true if no phases, or elapsed ≥ last
  end). Adds `boolean started()` and a `protected final long phaseEnd(int i)` so subclasses can expose named
  boundaries. No GL, no audio, no state beyond the start time.

## How it works
The pack host (today the provider; `CutsceneScreen` from step 9) constructs a concrete `PhaseTimeline` subclass,
calls `start(System.nanoTime())` once, then each render frame queries `phase(now)` / `done(now)` to decide what
to draw and when to hand off. The timeline produces only an index and a done-flag; it consumes only the
caller's clock reading. `CutsceneTimeline` additionally precomputes named boundary fields (`settle`,
`faceStart`, `faceEnd`, `outroEnd`) via `phaseEnd(i)` so the drawing code can sync art/jingle stabs to specific
beats. `LeadIn` is a single-phase `[holdMs]` timeline whose *liveness* (not its clock) is the source of truth
for "is a lead-in active": the provider holds it in a volatile field, the render thread builds/clears it and
owns the audio pause/resume, and `onKey` reads the volatile reference to gate input.

## Boundaries
Depends on nothing but `java.lang` — no GL, no audio, no Minecraft, no other `early/` package; the clock is
injected, never read internally. This makes the layer headless and editor-reusable, consistent with the
`core never imports bios` discipline even though it physically sits under `bios/` (it is the timing primitive
the presentation host leans on). Depended on by the provider / cutscene + game screens, which import the
concrete subclasses in `impl/`. Invariants: timelines are **wall-clock, no freeze**; `start()` must be called
before time queries are meaningful; `phaseEndsMs` is cumulative and ascending.

## Child layers
- `impl/` — the concrete subclasses: `CutsceneTimeline` (6-phase Recovery-Mode intro: wipe → clear-hold →
  scramble → settle → face → outro, boundaries verbatim from the old `computeCutsceneTimeline`) and `LeadIn`
  (single-phase song-start hold). See its README.
