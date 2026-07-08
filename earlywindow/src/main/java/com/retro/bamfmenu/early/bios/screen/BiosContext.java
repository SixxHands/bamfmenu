package com.retro.bamfmenu.early.bios.screen;

import com.retro.bamfmenu.early.bios.Mode;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.RhythmEngine;
import com.retro.bamfmenu.early.core.tuning.DetectorConfig;
import com.retro.bamfmenu.early.core.song.SongDef;
import com.retro.bamfmenu.early.core.song.SongDifficulty;

import java.util.List;

/* The cross-cutting host seam screens call back into. MAP 1 of the decomposition analysis showed the
   provider's CORE -- the `state` machine, the single AudioPlayer, the transitional lead-in, persistence,
   and the overlay/jukebox state -- resists single-screen ownership, so it STAYS provider-owned and screens
   reach it only through this small interface. Crucially the provider remains the SOLE writer of `state`
   (screens only request transitions via enterMode, which writes state last/once), so the hand-off hold loop
   never observes a half-built mode.

   Backed by a provider-side adapter; screens hold a BiosContext, never the TerminalWindowProvider type, so
   no screen can reach the render-thread executor / renderLock / GL handles. The surface grows as screens
   are extracted; this is the .75 STEP 0 baseline. */
public interface BiosContext {

    /* ---- transitions: the provider stays the SOLE `state` writer; screens only request ---- */
    void enterMode(Mode mode);

    Mode mode();

    /* ---- the single shared AudioPlayer (render-thread owned); may be null when nothing is playing ---- */
    AudioPlayer audio();

    void closeAudio();

    /* ---- the shared transitional lead-in + the special-event input lock ---- */
    void armLeadIn();

    boolean holdingLeadIn();

    boolean inSpecialEvent();

    /* ---- overlays (host-composited layers, not Modes) ---- */
    boolean volumeMenuOpen();

    boolean pauseMenuOpen();

    void openPauseMenu();

    /* Defensive clear of the active game's input queues (the pause overlay does this so a stale press does
       not replay on resume). */
    void clearGameInputQueues();

    /* ---- persistence: scores / keybinds / flags, written through to the obfuscated BiosCache ---- */
    void save();

    /* ---- the jukebox selection a game launches + banks under ---- */
    SongDef currentSong();

    SongDifficulty currentDifficulty();

    /* ---- launching a song's audio (the close+create+play+pause+setVolume both RHYTHM and SPECIAL share) ----
       Closes any current audio, decodes + plays the song's OGG with the given detector config (paused at ~0
       for the lead-in, at the saved volume) and makes it the single shared AudioPlayer. Returns the player, or
       null if the OGG is unreadable (the player is still set, so a later closeAudio frees it). */
    AudioPlayer startSongAudio(SongDef song, DetectorConfig detector);

    /* Set the SETUP notice line (e.g. an error to show after returning to BIOS). */
    void setNotice(String notice);

    /* Clear any pending transitional lead-in (e.g. quitting during it). */
    void clearLeadIn();

    /* ---- Tetris ("Enable Mechanics"): play its theme immediately (no lead-in) at the saved volume ---- */
    void playMusic(String resource);

    /* The persisted Tetris high score (part of "the cache"). */
    int tetrisHighScore();

    void setTetrisHighScore(int score);

    /* Return to BIOS SETUP landing on a given option row (>=0) with an optional notice, clearing any open
       confirm/password dialog. The provider stays the pack host of the SETUP-nav fields. */
    void exitToSetup(int row, String notice);

    /* ---- F2 volume overlay: the value is persisted + read by the audio launch, so it stays provider-side ---- */
    int volume();

    void setVolume(int v);

    /* Flash the brief red ERROR (an action blocked by a special event, e.g. F2 during the cutscene). */
    void flashError();

    /* ---- in-game pause overlay needs ---- */
    /* Quit from the pause menu: leave whichever game mode is active (routes to the screen's onExit). */
    void exitCurrentGame();

    /* The rhythm lane keybinds as the LIVE int[] (the remap UI mutates it in place, then calls save()). */
    int[] laneKeys();

    /* A short display name for a GLFW key code (the remap UI). */
    String keyName(int key);

    /* ---- the render-thread-only frame counter (POST reveal + spinners) ---- */
    int frame();

    /* ---- rhythm / results hand-off ---- */

    /* Set the active jukebox song (RhythmScreen.onEnter resolves a default + records it). */
    void setCurrentSong(SongDef song);

    /* The dev "Safe For Retro" no-fail/no-score flag, read when building the engine + banking. */
    boolean safeForRetro();

    /* Bank a finished rhythm run: high score on any run, grade only on a completion, Recovery unlock on a
       completion (all no-ops in Safe For Retro). Returns whether this run set a new high score. */
    boolean bankRhythm(SongDef song, SongDifficulty diff, int score, int perfectPct, boolean completed);

    /* Hand the finished run's numbers to the RESULTS card, free the audio, and switch to Mode.RESULTS. */
    void showResults(boolean completed, int score, int grade, boolean newBest);

    /* Perfect-% -> letter grade (S/A/B/C/D/F), for the RESULTS rank line. */
    String gradeLetter(int perfectPct);

    /* Start (or restart) the rhythm game for the current song -- the single funnel for the jukebox launch,
       the cutscene hand-off, and RESULTS retry. */
    void startRhythm();

    /* Leave the rhythm/results flow back to BIOS SETUP (frees audio + engine). */
    void exitRhythm();

    /* ---- cutscene ---- */

    /* Draw the BIOS SETUP screen underneath (the cutscene's opening wipe erases it). Backed by the provider's
       SETUP renderer -- the ONE SetupScreen once SETUP is extracted; never a second instance. */
    void renderSetupUnder();

    /* The Recovery-Mode face art frames (eyes / smile). Shared with the SETUP achievement panel so they stay
       provider-owned; the cutscene reads them for the stab-synced reveal. */
    String[] recoveryFaceA();

    String[] recoveryFaceB();

    /* ---- chart editor load/save (file I/O stays provider-side, like save()/BiosCache) ---- */

    /* The editor's chart text for a song-difficulty: the on-disk BAMFOS save if present, else the bundled
       chart (the seed to remap), else null (start a new chart). */
    String loadChartText(String songId, String token);

    /* Best-effort write of the editor's chart to <gameDir>/BAMFOS/songs/<id>/<token>.chart; returns success
       (degrades to a notice on failure, never throws on the render thread). */
    boolean saveChart(String songId, String token, String text);

    /* ---- chart editor mouse: convert a GLFW window pixel to the logical drawing space, + the live
       cursor position (window space), so the editor hit-tests clicks + tracks drags on the render thread ---- */
    double editorLogicalX(double winX);

    double editorLogicalY(double winY);

    double editorMouseWinX();

    double editorMouseWinY();

    /* ---- chart editor TEST PLAY: run the editor's in-memory chart in the real rhythm game ---- */

    /* Launch the rhythm game on a COPY of these notes, starting at startMs (notes before startMs are dropped),
       at the given note fall speed (approachMs; 0 = the default), and route the run's end/quit back to the editor
       instead of RESULTS/SETUP. */
    void startRhythmTest(List<RhythmEngine.Note> notes, int startMs, int approachMs);

    /* The pending editor-test chart for RhythmScreen.onEnter to use instead of the committed chart (returns it
       AND clears it; null for a normal play). */
    List<RhythmEngine.Note> consumeTestChart();

    /* The song time the editor test starts at (the cursor for P, 0 for O). */
    int testStartMs();

    /* The note fall speed (approachMs) the editor test should use (0 = the default). */
    int testApproachMs();

    /* Whether the current rhythm run is an editor test, so RhythmScreen routes its end back to the editor. */
    boolean editorTestActive();

    /* End an editor test: re-establish the editor (audio + Mode.EDITOR) WITHOUT reloading its in-memory chart. */
    void returnToEditor();

    /* ---- chart editor: on-disk user songs ---- */

    /* User songs discovered under <gameDir>/BAMFOS/songs/ (the pack's forks + drop-ins), each with a file: OGG. */
    List<SongDef> userSongs();

    /* Fork a bundled song: copy its WHOLE folder to a new numbered id under BAMFOS/songs/, returning the new
       on-disk SongDef (or null on failure). */
    SongDef forkSong(SongDef bundled);

    /* Mirror the on-disk layout: rename a user song's folder to match its OGG base name (if needed + safe),
       synchronously, returning the (possibly renamed) SongDef. Returns the input unchanged when no rename is
       needed/safe, and falls back to the input on failure (never null for a valid input). */
    SongDef normalizeUserSong(SongDef userSong);

    /* Create a new empty user song folder (next-free new-song / new-song-N), fully scaffolded but with no audio
       yet; returns its id (folder name), or null on failure. */
    String createNewSong();
}
