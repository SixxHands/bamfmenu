# early/bios/transition/impl — the concrete `PhaseTimeline` subclasses (cutscene + lead-in clocks)
## Intent
This is the leaf layer holding the two concrete transition clocks that the BIOS host plays today: the
Recovery-Mode intro `CutsceneTimeline` and the song-start `LeadIn`. It exists so the *timing* of a transition
lives in one small, testable place separate from the art/draw/audio that the owning code performs — both
classes are pure subclasses of `transition/PhaseTimeline` and carry no GL, no audio, and no state beyond the
wall-clock start. It comes from the .75 step 8 refactor (see `Transition.java`'s header), whose stated
goal was "the unified home for the cutscene timing + the lead-in" so future transitions can plug in as new
`PhaseTimeline` subclasses here without touching the existing ones. The `CutsceneTimeline` constants are
preserved verbatim from the provider's old `CS_*` constants / `computeCutsceneTimeline` so the cutscene's
beats did not shift when the timing moved out of the provider.

## Components
- **`CutsceneTimeline`** (`extends PhaseTimeline`, `final`) — the 6-phase intro-cutscene clock: WIPE →
  CLEAR-HOLD → SCRAMBLE → SETTLE → FACE → OUTRO. Public `static final long` segment durations (`WIPE1=600`,
  `CLEAR_HOLD=800`, `SCRAMBLE_START=1400`, `SCRAMBLE_MS=900`, `SETTLE_HOLD=600`, `FACE_HOLD=2200`,
  `OUTRO_SCRAMBLE_MS=500`) and four public `final long` named cumulative boundaries computed in the ctor via
  `phaseEnd(i)`: `settle` (=2300, header decodes to "Unzipping…"), `faceStart` (=2900, face anim + jingle
  begin), `faceEnd` (=5100, smile held → outro scramble), `outroEnd` (=5600, hand off to the song). Private
  `static long[] boundaries()` builds the cumulative phase-end array fed to `super(...)`. TIMING ONLY — the
  draw / jingle / stab-sync stay in the owning code (the provider today, `CutsceneScreen` at step 9).
- **`LeadIn`** (`extends PhaseTimeline`, `final`) — a single-phase `[HOLD]` clock for the song-start
  pre-roll (shared by RHYTHM + SPECIAL): the playfield is shown with the freshly-played song paused at ~0 for
  `holdMs` so the player registers the screen before the first note. Sole member is the `LeadIn(long holdMs)`
  ctor, which passes `new long[]{holdMs}` to `super(...)`; `done()` fires at that single deadline. The class
  itself only tells time — the provider owns the live `LeadIn` through a volatile field that is the single
  source of truth for "is a lead-in active" (the render thread builds/clears it and owns the audio
  pause/resume; `onKey` reads the volatile reference to gate input).

## How it works
Neither class adds behaviour beyond construction; all the timing logic is inherited from
`transition/PhaseTimeline`. A subclass supplies an ascending array of cumulative phase-END times to
`super(...)`. The owning screen/provider calls `start(System.nanoTime())` once, then each render-thread frame
calls `phase(now)` to pick what to draw and `done(now)` to know when to hand off / tear down — `phase` walks
the array and returns the first index whose end-time the elapsed ms has not yet reached (or the phase count
once fully elapsed); `done` returns true once elapsed ≥ the last entry. `CutsceneTimeline` additionally reads
back its own boundaries via the protected `phaseEnd(i)` to expose human-named milestones (`settle`,
`faceStart`, `faceEnd`, `outroEnd`) that the cutscene-draw code branches on. All clocks are deliberately
WALL-CLOCK with no freeze (the F2 overlay skips their *step* but never advances their clock), so the produced
phase indices track uninterrupted real time.

## Boundaries
- **Depends on:** `com.retro.bamfmenu.early.bios.transition.PhaseTimeline` (the abstract base; provides
  `start`/`elapsedMs`/`phase`/`done` and the protected `phaseEnd`) — the only import in either file. No GL, no
  audio, no Minecraft, no `core/` types.
- **Depended on by:** the `bios` host/provider that constructs and plays these clocks (provider today;
  `CutsceneScreen` for the cutscene at step 9), instantiating `new CutsceneTimeline()` and `new LeadIn(holdMs)`
  and driving them from the **render thread**.
- **Invariants:** these are *timing-only* types — they never draw, never touch audio, and hold no mutable
  state beyond the inherited start time; the owning code performs all art and side-effects. New transitions
  belong here as additional `PhaseTimeline` subclasses without modifying the existing two.
