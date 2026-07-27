package com.retro.bamfmenu.early.bios.screen;

import java.util.List;

import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.rhythm.model.SongDef;
import com.retro.bamfmenu.early.core.rhythm.model.SongDifficulty;

/**
 * The host screens call back into this context for the provider-owned core: state,
 * the single AudioPlayer, lead-in, and persistence.
 */
public interface BiosContext {

    // for when we request to change the active screen `state`
    /** Requests to change the active screen state mode. */
    void enterMode(Mode mode);

    /** Retrieves the current mode active in the BIOS context. */
    Mode mode();

    // shared AudioPlayer state, null when nothing is playing
    /** Retrieves the shared AudioPlayer state (null when nothing is playing). */
    AudioPlayer audio();

    /** Closes the active audio resources and player. */
    void closeAudio();

    // shared transitional lead-in + special-event input lock
    /** Arms the shared transitional lead-in and special-event input lock. */
    void armLeadIn();

    /** Checks whether the system is currently waiting on a lead-in. */
    boolean holdingLeadIn(); // currently stuck/waiting on a lead in?

    /** Checks if the system is currently in a special screen sequence that blocks generic user inputs. */
    boolean inSpecialEvent(); // currently in a special screen sequence? when true, it blocks generic user inputs.

    // Overlay menus, different from modes
    /** Returns whether the volume control menu overlay is open. */
    boolean volumeMenuOpen(); 

    /** Returns whether the pause menu overlay is open. */
    boolean pauseMenuOpen();

    /** Opens the pause menu overlay. */
    void openPauseMenu();

    /** Clears pending game input queues so stale keypresses do not replay on resume. */
    void clearGameInputQueues(); // we pause overlay calls so a stale press does not replay on resume.

    /** Persists configuration settings and scores to the cache file (BiosCache.dat). */
    void save();

    /** Gets the currently selected song definition. */
    SongDef currentSong();

    /** Gets the current song difficulty. */
    SongDifficulty currentDifficulty();

    /** Loads the sound file for a designated song. Starts the song's OGG paused at the saved volume. Null if the OGG is unreadable */
    AudioPlayer startSongAudio(SongDef song);

    /** Sets the text notice message inside the interface overlay */
    void setNotice(String notice);

    /** Reset and remove any pending lead-ins */
    void clearLeadIn(); 

    /** Immediately plays sound/music from a resource path. */
    void playMusic(String resource);

    /** Get the saved high score for Tetris. */
    int tetrisHighScore();

    /** Sets the saved high score for Tetris. */
    void setTetrisHighScore(int score);

    /** Aborts active mini-game instances and returns the screen to the main BIOS Setup */
    void exitToSetup(int row, String notice);

    /** Gets the current system volume scalar parameter. */
    int volume();

    /** Sets the global volume parameter. */
    void setVolume(int v);

    /** Trigger error flash for an action blocked by a special event */
    void flashError();

    // pause overlay needs
    /** Exit active mini-game instance. */
    void exitCurrentGame();

    /** Active keyboard bind keys for rhythm lanes */
    int[] laneKeys();

    /** Short display name for a GLFW key code. */
    String keyName(int key);

    /** Render-thread-only frame counter. */
    int frame();

    /** Specifies the current song definition. */
    void setCurrentSong(SongDef song);

    // debug/dev flag, blocks logging
    /** Checks if developer mode is active. */
    boolean safeForRetro();

    /** Logs final track completions and performance scores to disk. All no-ops in Safe For Retro. returns whether this run set a new high score. */
    boolean bankRhythm(SongDef song, SongDifficulty diff, int score, int perfectPct, boolean completed);

    /** display the final scoreboard cards screen. */
    void showResults(boolean completed, int score, int grade, boolean newBest);

    /** Translates raw percentage. Perfect-% -> letter grade (S/A/B/C/D/F). */
    String gradeLetter(int perfectPct);

    /** Transitions mode state and begins a rhythm song game phase. Also cutscene hand-off, RESULTS retry. */
    void startRhythm();

    /** Ends active rhythm plays and return back to BIOS SETUP. frees audio/engine. */
    void exitRhythm();

    // cutscene needs (CutsceneScreen)
    /**  Draws the underlying Bios Setup UI graphics beneath overlay screens/wipes. */
    void renderSetupUnder();

    // the shared BIOS shell, for a screen that has to look like SETUP (the UPDATE AND REPAIR window)
    /** Draws the wordmark header into the given logical canvas, returns the body Y under it. */
    int drawBiosHeader(int logicalW, int logicalH);

    /** The logical canvas width SETUP itself would use for a box of `rows` options. */
    int biosBodyWidth(int rows); // one owner for the short-window widen rule, else the two screens pick different font sizes

    /** Where the shared bottom control bar's row lands inside a `logicalW` canvas. */
    int biosControlBarY(int logicalW); // the bar draws in its OWN canvas, so logicalH - lineSpacing is NOT it

    /** True while the updater engine is writing files, the boot lockout's own signal. */
    boolean updaterSyncActive(); // a BIOS page that touches files has to refuse for as long as this holds

    // The Recovery face art frames (eyes / smile), shared with the SETUP achievement panel so they stay provider-owned.
    String[] recoveryFaceA();

    String[] recoveryFaceB(); // why are these living here?

    // chart editor load/save (file I/O stays provider-side, like save()/BiosCache)
    /** Load <gameDir>/BAMFOS, the absolute path string pointing to the active BAMFOS configuration workspace.). */
    String bamfosDir();

    /** Loads the chart layout text content for a song. */
    String loadChartText(String songId, String token);

    /** Writes the chart layout text to disk. Returns true if successful. */
    boolean saveChart(String songId, String token, String text);

    // Mapping a physical GLFW window X/Y coordinate into the editor logical space.
    /** Maps a physical GLFW window X coordinate into the editor logical space. */
    double editorLogicalX(double winX);

    /** Maps a physical GLFW window Y coordinate into the editor logical space. */
    double editorLogicalY(double winY);

    /** Gets the raw horizontal cursor position in GLFW window space. */
    double editorMouseWinX();

    /** Gets the raw vertical cursor position in GLFW window space. */
    double editorMouseWinY();

    // chart editor test play: run the editor's in-memory chart in the real rhythm game
    /** Loads customized map layouts directly inside a testing playground rhythm session. Runs a COPY, the editor's notes are never changed. */
    void startRhythmTest(List<Note> notes, int startMs, int approachMs);

    /** Returns and clears the pending test chart, null for a normal play. */
    List<Note> consumeTestChart();

    /** Gets the start time offset in milliseconds chosen for the playtest run. */
    int testStartMs();

    /** Gets the speed approach travel time in milliseconds chosen for the playtest run. */
    int testApproachMs();

    /** Checks whether a chart playtest run is currently active. */
    boolean editorTestActive();

    /** Stops testing sessions and returns the player directly to the map editor state. */
    void returnToEditor();

    // chart editor: on-disk user songs
    /** Finds and retrieves <gameDir>/BAMFOS/songs/ forked bundled songs, and custom external songs imported locally by the user. */
    List<SongDef> userSongs();

    /** Copies a bundled song to the local user directory to make it editable. */
    SongDef forkSong(SongDef bundled);

    /** Renames a user song's folder to match its OGG base name when needed, never null for a valid input. */
    SongDef normalizeUserSong(SongDef userSong);

    /** Generates an empty custom track directory skeleton. */
    String createNewSong();
}
