package com.retro.bamfmenu.early.bios.modes.rhythm;

import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmClock;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.Chart;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartIO;
import com.retro.bamfmenu.early.core.rhythm.panel.LeftPanelTrack;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.modes.rhythm.parts.RhythmRenderer;
import com.retro.bamfmenu.early.core.rhythm.model.SongDef;
import com.retro.bamfmenu.early.core.rhythm.model.SongDifficulty;
import com.retro.bamfmenu.early.core.rhythm.registry.SongRegistry;

import java.util.List;

/* Mode.RHYTHM HOST: the BAMF! rhythm game (the BIOS's marquee mode). Owns the lifecycle (onEnter/step/
   onExit), drains the provider-owned input queue, loads the chart through ctx (editor Test Play in memory,
   else the committed chart text), and on finish/fail banks the run + shows the RESULTS card via ctx. The VIEW
   (highway/HUD/header drawing) lives in RhythmRenderer, and the scoring economy in RhythmEngine's
   ScoringEngine; this class is the controller that wires input -> engine and delegates render -> renderer.
   Pure logic, no GL, no LWJGL imports (GLFW key codes duplicated as ints, TetrisGame convention). */
public final class RhythmScreen implements Screen {

    private static final int KEY_ESCAPE = 256;
    private static final int ACTION_PRESS = 1;
    private static final int ACTION_RELEASE = 0;

    private static final float DAMAGE_SFX_GAIN = 0.5f;

    /* Song-start pre-roll AND the symmetric end-roll. PRE-ROLL: a SILENT gap where the chart's opening notes
       SCROLL IN from the top (the engine clock runs -PREROLL_MS -> 0) and a 3-2-1-0 count is drawn centered,
       no sound, the silence is intentional. END-ROLL: the same length is used as the engine's tail, so after the
       last note the song plays OUT for PREROLL_MS before RESULTS instead of cutting off. Replaces the old frozen
       lead-in for the rhythm game. Tunable. */
    private static final int PREROLL_MS = 2800; // silent count-in length AND the end-roll/tail length (symmetric)
    private static final int COUNT_STEPS = 4;   // 3, 2, 1, 0
    private static final int FADE_OUT_MS = 500; // ease the song to silence over the last half-second, not a hard cut

    private final java.util.Queue<long[]> input;
    private final RhythmRenderer renderer = new RhythmRenderer(); // step 7.3: the VIEW
    private RhythmEngine engine;
    private LeftPanelTrack leftPanel;
    private boolean inPreRoll;        // true during the silent count-in before the song starts
    private long preRollStartNanos;   // wall-clock start of the pre-roll
    private int startAtMs;            // song-time the run starts at (0 normally; the editor Test Play cursor)

    public RhythmScreen(java.util.Queue<long[]> input) {
        this.input = input;
    }

    @Override
    public Mode mode() {
        return Mode.RHYTHM;
    }

    /* Start the rhythm game for the current song (auto-charted from its audio). Closes any audio in flight,
       plays the song (paused at ~0 for the lead-in), builds the engine, then enterMode(RHYTHM). */
    @Override
    public void onEnter(BiosContext ctx) {
        input.clear();
        SongDef song = ctx.currentSong();
        if (song == null) {
            if (SongRegistry.SONGS.length == 0) { // never on an intact jar (built-in fallback), but never AIOOBE
                ctx.setNotice("No songs loaded.");
                exit(ctx);
                return;
            }
            song = SongRegistry.SONGS[0];
        }
        ctx.setCurrentSong(song);
        AudioPlayer p = ctx.startSongAudio(song); // close+play+pause-at-0+setVolume; null if unreadable
        if (p == null) {
            ctx.setNotice("Song data unreadable.");
            exit(ctx);
            return;
        }
        p.loadSfx("/sfx/damage.ogg"); // "damage taken" SFX (global), mixed over the song on a miss
        /* The chart is LOADED (no DSP at play): editor Test Play runs the in-memory chart; otherwise the committed
           songs/<id>/<token>.chart via ctx.loadChartText (BAMFOS first, then bundled) so USER / osu-imported songs
           load off-disk too. An empty result = uncharted -> bail rather than start an empty highway. */
        List<Note> testNotes = ctx.consumeTestChart();
        List<Note> chart;
        int approach;
        String diffName = "";
        if (testNotes != null) {
            chart = testNotes;              // editor Test Play: run the editor's in-memory chart, not the committed one
            startAtMs = ctx.testStartMs();
            approach = ctx.testApproachMs(); // the editor's per-chart fall speed
        } else {
            SongDifficulty diff = ctx.currentDifficulty();
            if (diff == null) { // defensive: fall back to the song's Normal, else its first difficulty
                SongDifficulty norm = song.difficulty("normal");
                diff = norm != null ? norm
                        : (song.difficulties() != null && !song.difficulties().isEmpty() ? song.difficulties().get(0) : null);
            }
            if (diff == null) {
                ctx.setNotice("Uncharted: " + song.name() + ".");
                exit(ctx);
                return;
            }
            diffName = diff.name();
            String text = ctx.loadChartText(song.id(), diff.token());
            Chart parsed = text != null ? ChartIO.read(text, diff.token()) : null;
            chart = parsed != null ? parsed.notes() : java.util.List.of();
            startAtMs = 0;
            approach = parsed != null ? parsed.approachMs() : 0;
        }
        if (chart.isEmpty()) {
            ctx.setNotice(testNotes != null ? "Nothing to test." : "Uncharted: " + song.name() + " (" + diffName + ").");
            exit(ctx); // closes the audio it just started
            return;
        }
        // no-fail = Safe For Retro. End-roll = pre-roll length (symmetric): after the last note the song plays
        // out for PREROLL_MS before RESULTS instead of cutting off the moment the chart ends.
        engine = new RhythmEngine(chart, ctx.safeForRetro(), PREROLL_MS);
        if (approach > 0) {
            engine.approachMs = approach; // per-chart note fall speed (else the engine keeps DEFAULT_APPROACH_MS)
        }
        renderer.reset(); // reset the SCORE odometer so a replay doesn't show the previous run's spin
        leftPanel = song.newLeftPanel(); // per-song left cell, or null = blank
        // Begin the silent pre-roll: the opening notes scroll in + a 3-2-1-0 count plays, then step() starts
        // the audio at songMs=0. Replaces the old frozen lead-in for the rhythm game.
        inPreRoll = true;
        preRollStartNanos = System.nanoTime();
        ctx.enterMode(Mode.RHYTHM);
    }

    /* Read the audio clock, drain timestamped input (converting each event's nanoTime back to song-time so
       hit accuracy survives the queue), judge presses/releases, advance the engine, route to RESULTS. */
    @Override
    public void step(BiosContext ctx) {
        RhythmEngine g = engine;
        AudioPlayer a = ctx.audio();
        if (g == null || a == null) {
            exit(ctx);
            return;
        }
        if (inPreRoll) {
            long elapsed = (System.nanoTime() - preRollStartNanos) / 1_000_000L;
            if (elapsed < PREROLL_MS) {
                /* Count-in (3-2-1-0): let the player warm up. A lane press LIGHTS the lane (the buttons respond)
                   but is NOT judged, so nothing counts as a hit/miss/drop/fail and the score stays 0; ESC still
                   pauses (or drops back to the editor on a Test Play). Drains the queue itself instead of the old
                   blanket input.clear() so the visual feedback survives but no scoring fires until the song starts. */
                long[] pev;
                while ((pev = input.poll()) != null) {
                    int pkey = (int) pev[0];
                    int paction = (int) pev[1];
                    if (paction == ACTION_PRESS && pkey == KEY_ESCAPE) {
                        if (ctx.editorTestActive()) {
                            ctx.returnToEditor();
                        } else {
                            ctx.openPauseMenu();
                        }
                        return;
                    }
                    int plane = laneForKey(ctx, pkey);
                    if (plane >= 0) {
                        g.setLaneVisual(plane, paction == ACTION_PRESS); // press lights the lane, release clears it, no scoring
                    }
                }
                g.songMs = startAtMs + (int) (elapsed - PREROLL_MS); // count-in scrolls the opening notes in (from startAtMs)
                int step = (int) Math.min(COUNT_STEPS - 1L, elapsed * COUNT_STEPS / PREROLL_MS);
                g.countDigit = (COUNT_STEPS - 1) - step; // 3, 2, 1, 0 (silent, no count SFX, by design)
                return;
            }
            inPreRoll = false;
            g.countDigit = -1;
            if (startAtMs > 0) {
                a.playFrom(startAtMs / 1000f); // Test Play from the cursor: seek + play
            } else {
                a.resume(); // count-in over -> start playback from 0; the audio clock takes over below
            }
        }
        int songMs = (int) (a.positionSeconds() * 1000f);
        /* Fade the song out over the last FADE_OUT_MS before it stops (the earlier of the audio's natural end
           or the end-roll finish) so it eases into silence/RESULTS instead of cutting hard. */
        int durMs = (int) (a.durationSeconds() * 1000f);
        int stopMs = durMs > 0 ? Math.min(durMs, g.finishMs()) : g.finishMs();
        if (songMs > stopMs - FADE_OUT_MS) {
            float f = Math.max(0f, Math.min(1f, (stopMs - songMs) / (float) FADE_OUT_MS));
            a.setVolume((ctx.volume() / 100f) * f);
        }
        long nowNanos = System.nanoTime();
        boolean pause = false;
        int missBefore = g.scoring.miss();
        int failBefore = g.scoring.fail();
        long[] ev;
        while ((ev = input.poll()) != null) {
            if (g.scoring.failed()) {
                break; // game over mid-input -> stop; the transition below routes to RESULTS
            }
            int key = (int) ev[0];
            int action = (int) ev[1];
            long eventNanos = ev[2];
            if (action == ACTION_PRESS && key == KEY_ESCAPE) {
                if (ctx.editorTestActive()) { // a Test Play: ESC drops straight back to the editor (no pause menu)
                    ctx.returnToEditor();
                    return;
                }
                pause = true;
                break;
            }
            int lane = laneForKey(ctx, key);
            if (lane < 0) {
                continue;
            }
            /* if you find that pressing four keys at once apparently does not work for you, and you happen
               to have a razor keyboard, the issue is that razor synapse has a built-in ghosting on the
               s/d/j/k character combo. if you must push these four keys down all at once, the fix is
               simple. Delete Razer Synapse. This is not the mod's fault, this is purely a skill issue
               from Razor's software. */
            int eventMs = RhythmClock.eventMs(songMs, nowNanos, eventNanos);
            if (action == ACTION_PRESS) {
                g.press(lane, eventMs);
            } else if (action == ACTION_RELEASE) {
                g.release(lane, eventMs); // release time judges the hold's tail
            }
        }
        if (pause) {
            ctx.openPauseMenu();
            return;
        }
        if (!g.scoring.failed()) {
            g.update(songMs);
        }
        if (g.scoring.miss() > missBefore || g.scoring.fail() > failBefore) {
            a.playSfx((ctx.volume() / 100f) * DAMAGE_SFX_GAIN); // "damage taken" on a miss / fail hit
        }
        if (g.scoring.failed()) {
            enterResults(ctx, false); // game over -> RESULTS (game-over variant); cuts the music
            return;
        }
        if (g.isFinished() || (!a.isPlaying() && songMs > 2000)) {
            enterResults(ctx, true); // natural clear -> RESULTS (completed variant)
            return;
        }
    }

    /* Flush the pending combo, capture score + grade, bank the run + compute new-best (via ctx), then hand
       off to the RESULTS card. */
    private void enterResults(BiosContext ctx, boolean completed) {
        if (ctx.editorTestActive()) { // a Test Play returns to the editor, not the RESULTS card
            ctx.returnToEditor();
            return;
        }
        RhythmEngine g = engine;
        if (g != null) {
            g.scoring.flushPending();
        }
        int score = g != null ? g.scoring.score() : 0;
        int grade = (completed && g != null) ? perfectPct(g) : -1;
        SongDef song = ctx.currentSong();
        SongDifficulty diff = ctx.currentDifficulty();
        boolean newBest = ctx.bankRhythm(song, diff, score, g != null ? perfectPct(g) : 0, completed);
        ctx.showResults(completed, score, grade, newBest); // closes audio + enterMode(RESULTS)
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return "<- Left     v Down     ^ Up     -> Right          ESC  Pause";
    }

    @Override
    public void onExit(BiosContext ctx) {
        exit(ctx);
    }

    /* Leave the rhythm game back to SETUP: free audio + engine + panel, clear the lead-in, enterMode(SETUP). */
    private void exit(BiosContext ctx) {
        ctx.closeAudio();
        input.clear();
        inPreRoll = false;
        engine = null;
        leftPanel = null;
        ctx.clearLeadIn();
        ctx.enterMode(Mode.SETUP);
    }

    /* The VIEW is RhythmRenderer; the host just guards on a live engine and delegates (engine == null after an
       exit -> no draw, matching the old drawRhythm guard). */
    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        RhythmEngine g = engine;
        if (g == null) {
            return;
        }
        renderer.render(c, ctx, g, g.scoring, leftPanel);
    }

    private int laneForKey(BiosContext ctx, int key) {
        int[] laneKeys = ctx.laneKeys();
        for (int i = 0; i < laneKeys.length; i++) {
            if (laneKeys[i] == key) {
                return i;
            }
        }
        return -1;
    }

    /* The grade source of a finished run: percent of judgments earned as PERFECT. A hold yields TWO judgments
       (head press + tail release), so it counts twice in the denominator, keeps the percentage sane now that
       releases are graded. */
    private static int perfectPct(RhythmEngine g) {
        int total = 0;
        for (Note n : g.notes) {
            total += n.isHold() ? 2 : 1;
        }
        return total > 0 ? Math.round(100f * g.scoring.perfect() / total) : 0;
    }
}
