package com.retro.bamfmenu.early.bios;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.tuning.DetectorConfig;
import com.retro.bamfmenu.early.core.special.PlaceholderSpecial;
import com.retro.bamfmenu.early.core.special.SpecialSession;
import com.retro.bamfmenu.early.core.tetris.TetrisGame;
import com.retro.bamfmenu.early.core.song.Difficulty;
import com.retro.bamfmenu.early.core.song.SongDifficulty;
import com.retro.bamfmenu.early.core.song.SongDef;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.gfx.RichFont;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.core.asset.Art;
import com.retro.bamfmenu.early.bios.screen.mode.CutsceneScreen;
import com.retro.bamfmenu.early.bios.screen.mode.rhythm.RhythmScreen;
import com.retro.bamfmenu.early.bios.screen.mode.ResultsScreen;
import com.retro.bamfmenu.early.bios.screen.mode.SpecialScreen;
import com.retro.bamfmenu.early.bios.screen.mode.TetrisScreen;
import com.retro.bamfmenu.early.bios.screen.mode.SnakeScreen;
import com.retro.bamfmenu.early.bios.screen.mode.GalagaScreen;
import com.retro.bamfmenu.early.bios.screen.mode.DoomScreen;
import com.retro.bamfmenu.early.bios.screen.overlay.PauseOverlay;
import com.retro.bamfmenu.early.bios.screen.overlay.VolumeOverlay;
import com.retro.bamfmenu.early.core.song.SongLoader;
import com.retro.bamfmenu.early.core.song.SongRegistry;
import com.retro.bamfmenu.early.core.secret.SecretContext;
import com.retro.bamfmenu.early.core.secret.SecretRegistry;
import com.retro.bamfmenu.early.bios.state.BiosCache;
import com.retro.bamfmenu.early.bios.screen.mode.editor.EditorScreen;
import com.retro.bamfmenu.early.bios.transition.impl.LeadIn;
import net.neoforged.fml.earlydisplay.ElementShader;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowPosCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.opengl.GL;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;

/* A from-scratch early-boot window that draws a real text terminal: the live "what's loading" lines
   printed top-down like a console, with an animated spinner + ASCII progress bar underneath. It is a
   full ImmediateWindowProvider (the SPI FancyModLoader discovers on the service layer before mods),
   NOT a DisplayWindow subclass.

   Why from scratch: FancyModLoader's DisplayWindow draws on a private render loop that only iterates an
   element list we cannot extend (RenderElement's constructor needs package-private interfaces), so a
   custom text layout + animated spinner is impossible inside it. We therefore own the window, the GL
   render thread, and the hand-off to Minecraft, and reuse FancyModLoader's PUBLIC draw classes
   (SimpleBufferBuilder / ElementShader). Glyphs come from our own RichFont (Consolas baked over ASCII +
   box-drawing + shades + arrows) instead of FML's SimpleFont, which is hard-limited to ASCII 32..126.

   The in-game transition deliberately avoids NeoForge's own overlay: NeoForgeLoadingOverlay.newInstance
   requires a concrete DisplayWindow, which we are not, so loadingOverlay() hands Minecraft a VANILLA
   LoadingOverlay (built reflectively). The main mod's LoadingOverlay mixin then paints the Win95 setup
   dialog over it. This means we never need the early framebuffer texture.

   Threading mirrors DisplayWindow's proven model: the window is created on the main thread; the GL
   context is absorbed by a single render thread that ticks ~20fps; setupMinecraftWindow stops that
   thread, takes the context back onto the main thread, and returns the handle to Minecraft. Everything
   is best-effort: the legacy DisplayWindow-based provider remains selectable via config/fml.toml if
   this ever fails to hand off. */
public class TerminalWindowProvider implements ImmediateWindowProvider {

    /*
     * Every screen lays out in a fixed logical canvas that is STRETCHED to fill the
     * window: the layout is
     * LOCKED regardless of window size (same columns/rows), and a bigger window
     * scales the SAME grid up, so
     * fullscreen = bigger text, NOT more characters. ONE unified width is used by
     * every screen so the text is
     * the same size across them all. This is THE text-size knob: RAISE it for
     * smaller text (more columns),
     * LOWER it for bigger text. (Old split was 960 terminal / 1120 the rest;
     * unified + smaller here.)
     */
    private static final int LOGICAL_W = 1400;
    private static final int MARGIN = 16;
    private static final int FONT_TEX = 6; // texture unit for our RichFont atlas

    /*
     * ---- MASTER FONT SETTING ----
     * The early window's font. Each Face = a bundled TTF, the pixel size to bake it
     * at, and whether to smooth
     * it (LINEAR) or keep it crunchy (NEAREST, for pixel fonts). Change ACTIVE_FONT
     * to swap the WHOLE early
     * window's font in one place. Every bundled face is monospace and carries the
     * box-drawing + block + arrow
     * glyphs the UI draws (verified); fonts from the codeface set that lacked those
     * were deliberately not
     * bundled. size is the per-font tuning knob (bigger = chunkier, fewer columns).
     */
    private record Face(String id, String resource, int size, boolean smooth) {
    }

    private static final Face[] FONTS = {
            /*
             * gohufont: a fully-OUTLINED pixel/terminal font -- crisp NEAREST look AND real
             * box-drawing outlines.
             * STB rasterizes from OUTLINES, so a font's box/block glyphs must be vector
             * (not bitmap-only) or they
             * render blank. Every face below is verified monospace with real outlines for
             * the box/block/arrow
             * glyphs the UI draws -- EXCEPT terminus (see note). gohufont is the default
             * (closest to the wanted
             * Terminus pixel vibe while actually drawing the borders).
             */
            new Face("gohufont", "/fonts/gohufont.ttf", 24, false), // pixel, NEAREST
            new Face("hack", "/fonts/hack.ttf", 24, true),
            new Face("fira-mono", "/fonts/fira-mono.ttf", 24, true),
            new Face("source-code-pro", "/fonts/source-code-pro.ttf", 24, true),
            new Face("dejavu-sans-mono", "/fonts/dejavu-sans-mono.ttf", 24, true),
            new Face("cousine", "/fonts/cousine.ttf", 24, true),
            new Face("iosevka", "/fonts/iosevka.ttf", 24, true),
            new Face("mensch", "/fonts/mensch.ttf", 24, true),
            new Face("sudo", "/fonts/sudo.ttf", 24, true),
            new Face("consolas", "/consola.ttf", 24, true),
            /*
             * terminus: ASCII renders, but its box-drawing/block glyphs are BITMAP-ONLY
             * (empty outlines), so they
             * render BLANK under our outline rasterizer -- the missing-borders bug. Do NOT
             * make it active; kept
             * only to document the finding.
             */
            new Face("terminus", "/fonts/terminus.ttf", 24, false),
    };
    private static final Face ACTIVE_FONT = FONTS[0]; // <<< the one knob: gohufont (pixel vibe + real boxes)
    private static final int FONT_ATLAS = 1024; // glyph atlas size (px); 1024 fits any bundled face + ranges
    /*
     * Colours are ABGR packed ints (SimpleFont/SimpleBufferBuilder convention).
     * Grays/white are
     * symmetric so the channel order does not bite us.
     */
    private static final int FG = 0xFFAAAAAA; // DOS light gray
    private static final int FG_BRIGHT = 0xFFFFFFFF; // active line / spinner
    private static final int FG_DIM = 0xFF6E6E6E; // header / older lines
    private static final int FG_GOLD = 0xFF00D7FF; // gold accent (ABGR) for the "OS" in the wordmark
    private static final char[] SPINNER = { '|', '/', '-', '\\' };
    private static final int MAX_LOG = 256;
    private static final int MEM_FRAMES = 40; // ~2s memory-count animation at 20fps
    /*
     * The BAMFOS wordmark spans ~this fraction of the window width; the SixxHands
     * logo is rendered in the
     * SAME canvas so its glyph size matches exactly (they read as one terminal
     * header).
     */
    private static final double HEADER_FW = 0.42;
    /*
     * Window title (taskbar/hover) during boot; the main mod's WindowTitleMixin
     * re-asserts the same name
     * in-game so the brand is consistent before and after hand-off. Mirrors
     * WindowChromeController.APP_NAME.
     */
    private static final String APP_TITLE = "BAMF :: MINECRAFT";

    /*
     * Default boot scheme is the black DOS terminal above; the "You're Blue Now"
     * SETUP option flips the
     * whole boot screen to the classic AMI blue (blue background + white text). RGB
     * floats for glClear.
     */
    private static final float[] BG_BLACK = { 0.0F, 0.0F, 0.0F };
    private static final float[] BG_BLUE = { 0.0F, 0.0F, 0.66F };

    /*
     * The BAMFOS BIOS SETUP menu (press DEL during boot). Mostly cosmetic in-joke
     * toggles; a few do
     * something: "You're Blue Now" recolors the boot screen,
     * "Unlock 'Bios Screen' Achievement" pops a
     * joke, "Exit Bios" leaves. Edit these strings (and SETUP_HELP, one entry per
     * option) freely.
     */
    private static final String SETUP_TITLE = "Welcome to the BAMFOS Bios Screen, please select an option.";
    private static final String[] SETUP_OPTIONS = {
            "Roll it back to BAMFOS v1",
            "Reset Cache",
            "Safe For Retro Mode",
            "Enable Mechanics",
            "Select Song",
            "Open Chart Editor",
            "Open Stage Editor",
            "You're Blue Now",
            "Party Fun Time",
            "Unlock 'Bios Screen' Achievement",
            "Enter Recovery Mode",
            "Exit Bios",
    };
    /*
     * Help text shown in the right panel for the highlighted option; '\n' splits
     * lines.
     */
    private static final String[] SETUP_HELP = {
            "This is the first version,\nsilly!",
            "Reset the BAMFOS to\nfactory defaults.",
            "I'm the developer so I\nshould know my own\npassword.",
            "Enter the mechanic screen to enable mechanics.\nWhatever that's supposed to mean. What kind of developer writes these unhelpful descriptions?",
            "", /*
                 * Select Song: deliberately blank - the right panel stays empty until you
                 * press ENTER to open the jukebox
                 */
            "Open the chart editor.\nComing soon!",
            "Open the stage editor.\nComing soon!",
            "Recolor the boot screen\nblue and white - the\nclassic AMI BIOS look.",
            "Ferret Party Forever!\nComing Soon!",
            "Unlock the secret 'Bios\nScreen' achievement by\nbeating Recovery Mode.",
            "Boot into Recovery Mode\nto restore BAMFOS.",
            "Leave SETUP and start\nthe game.",
    };

    private long window;
    private String glVersion = "3.2";
    private String mcVersion = "";
    private String forgeVersion = "";
    private String gameDir = ".";

    /*
     * Detected-hardware strings for the POST screen, cached once on the main thread
     * (safely published to
     * the render thread via the executor submit that schedules initRender). gpuName
     * is filled on the
     * render thread once the GL context exists.
     */
    private String cpuInfo = "";
    private String osInfo = "";
    private String javaInfo = "";
    private String biosDate = "06/15/26";
    private long memTotalKb = 65536L;
    private String gpuName;

    /*
     * Boot-splash ASCII art, loaded from bundled text files (so they are exact and
     * editable).
     */
    private String[] bamfosArt = new String[0];
    private String[] sixxArt = new String[0];

    private int winWidth = 854;
    private int winHeight = 480;
    private int winX;
    private int winY;
    private volatile int fbWidth = 854;
    private volatile int fbHeight = 480;

    private ScheduledExecutorService renderScheduler;
    private ScheduledFuture<?> windowTick;
    private ScheduledFuture<?> initFuture;
    private final Semaphore renderLock = new Semaphore(1);
    private volatile boolean handedOff;
    private volatile boolean initFailed;

    private ElementShader shader;
    private RichFont font;
    private SimpleBufferBuilder buffer;
    private TerminalCanvas canvas; // STEP 1: the GL text facade screens draw through (built in initRender)
    private int lineSpacing = 28;
    private int charWidth = 14;
    /*
     * Offscreen framebuffer we render into, then blit to the window flipping Y
     * (FML's text shader maps
     * screen-y=0 to the bottom of clip space, so direct-to-window rendering comes
     * out upside down).
     */
    private int offscreenFbo;
    private int offscreenTex;
    private int offscreenW;
    private int offscreenH;

    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWWindowSizeCallback sizeCallback;
    private GLFWWindowPosCallback posCallback;

    private Constructor<?> loadingOverlayCtor;

    private final Deque<String> log = new ArrayDeque<>();
    private String lastSeen = "";
    private int frame;

    /*
     * BIOS SETUP state. Written from the GLFW key callback (main thread, during
     * glfwPollEvents) and read
     * by the render thread, hence volatile. optionOn holds the cosmetic per-option
     * toggle states.
     */
    private GLFWKeyCallback keyCallback;
    private GLFWCharCallback charCallback;
    /*
     * mouse + scroll, used ONLY by the chart editor (Mode.EDITOR).
     * Enqueue-only on the GLFW (main)
     * thread; the editor's render-thread step drains them. Gated to EDITOR so no
     * other screen sees mouse input.
     * Freed at hand-off alongside the others.
     */
    private GLFWMouseButtonCallback mouseButtonCallback;
    private GLFWCursorPosCallback cursorPosCallback;
    private GLFWScrollCallback scrollCallback;
    /*
     * The active base screen (replaces the old
     * setupMode/tetrisMode/rhythmMode/cutsceneMode flag bag).
     * One volatile field, written atomically on each transition, so the hand-off
     * hold loop can never see
     * "no screen up" mid-switch. Written from the main thread for the POST<->SETUP
     * transitions (a plain
     * volatile store, no render-period change) and from the render thread for the
     * game transitions (via
     * enterMode, which also sets the frame period).
     */
    private volatile Mode state = Mode.POST;
    /*
     * BIOS is a ONE-WAY door: once the user leaves SETUP back to POST, set true. It
     * (a) disables re-entering
     * BIOS (DEL is ignored in POST), (b) drops the "Press DEL to enter SETUP"
     * footer prompt, and (c) tells the
     * hand-off to show the live POST terminal briefly before handing off (so the
     * exit reads BIOS -> POST ->
     * loading overlay, not a frozen BIOS frame). False on the FIRST POST, so that
     * screen keeps the DEL prompt.
     */
    private volatile boolean biosUsed;
    private volatile boolean blueTheme;
    private volatile boolean safeForRetro; // dev-controls flag; persisted so future Easter eggs can read it
    private volatile boolean gameBeaten; // persisted: set by the (future) Recovery Mode game
    private volatile boolean biosAchievement; // persisted: the 'Bios Screen' achievement, gated by gameBeaten
    private volatile int setupSel;
    private volatile String setupNotice = "";
    private volatile int confirmFor = -1; // option index awaiting an are-you-sure confirmation, or -1
    private volatile int passwordFor = -1; // option index awaiting a password, or -1
    private volatile String passwordInput = "";
    private final boolean[] optionOn = new boolean[SETUP_OPTIONS.length];

    /* ---- data-driven secrets (see core.secret) ---- The Safe-For-Retro box is now a secret-entry box: typed
       phrases (ENTER) + key sequences (live) are matched against auto-discovered Secret defs. The registry holds the
       rolling key-history; secretCtx is the host seam the secret actions act through (toggle the mode, notice, launch
       a hidden song). Add a secret by dropping a SecretDef in core/secret/defs/ -- no wiring here. */
    private final SecretRegistry secrets = new SecretRegistry();
    private final SecretContext secretCtx = new SecretContext() {
        @Override
        public void toggleSafeForRetro() {
            safeForRetro = !safeForRetro;
            setupNotice = safeForRetro ? "Safe For Retro Mode enabled." : "Safe For Retro Mode disabled.";
            saveBiosConfig(); // persist so the main mod + other Easter eggs can read it
        }

        @Override
        public void notice(String message) {
            setupNotice = message == null ? "" : message;
        }

        @Override
        public void launchSong(String songId) {
            SongDef target = findSongById(songId);
            if (target == null) {
                setupNotice = "Secret song not found: " + songId;
                return;
            }
            currentSong = target;
            SongDifficulty d = target.difficulty("normal");
            if (d == null && target.difficulties() != null && !target.difficulties().isEmpty()) {
                d = target.difficulties().get(0);
            }
            currentDifficulty = d;
            rhythmStartRequested = true; // the render thread builds the game next frame
        }

        @Override
        public void launchGame(String gameId) {
            if ("snake".equals(gameId)) {
                snakeStartRequested = true; // the render thread builds the game next frame
            } else if ("galaga".equals(gameId)) {
                galagaStartRequested = true;
            } else if ("doom".equals(gameId)) {
                doomStartRequested = true;
            } else {
                setupNotice = "Unknown game: " + gameId;
            }
        }
    };

    /*
     * "Enable Mechanics" terminal Tetris (state == Mode.TETRIS). Input arrives on
     * the main thread via
     * onKey and is enqueued; the render thread drains tetrisInput, ticks the game,
     * and draws -- so every
     * TetrisGame field is touched by a single thread and needs no locks. tetris is
     * volatile so the render
     * thread safely sees the fully constructed game once state flips to TETRIS.
     * tetrisHighScore persists
     * in the BIOS config and is wiped by Reset Cache (the high score is part of
     * "the cache").
     */
    private volatile int tetrisHighScore;
    private final java.util.Queue<Integer> tetrisInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final TetrisScreen tetrisScreen = new TetrisScreen(tetrisInput); // STEP 4: TETRIS host extracted
    /* The hidden Snake game (Mode.SNAKE), launched by the "snake" secret. Same host pattern as Tetris: onKey
       enqueues to snakeInput on the main thread, the render thread drains + ticks + renders. */
    private final java.util.Queue<Integer> snakeInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final SnakeScreen snakeScreen = new SnakeScreen(snakeInput);
    private volatile boolean snakeStartRequested;
    /* The hidden Galaga game (Mode.GALAGA), launched by the "galaga" secret. Like Snake, but its queue carries
       {key, action} so the render thread can track HELD left/right (PRESS/RELEASE) for the shooter, not just taps. */
    private final java.util.Queue<int[]> galagaInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final GalagaScreen galagaScreen = new GalagaScreen(galagaInput);
    private volatile boolean galagaStartRequested;
    /* The hidden DOOM (Mode.DOOM), launched by the "doom" secret: the REAL vendored engine (core.doom.DoomEngine) on
       its own daemon thread, drawn as colored ASCII. {key, action} like Galaga so DOOM gets HELD movement + key-up. */
    private final java.util.Queue<int[]> doomInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final DoomScreen doomScreen = new DoomScreen(doomInput);
    private volatile boolean doomStartRequested;

    /*
     * Our own pre-Minecraft OpenAL audio (the rhythm/Tetris music + the volume
     * menu); torn down at
     * hand-off so MC's sound init is unaffected.
     */
    private volatile AudioPlayer audio;

    /*
     * Global volume menu (F2): pauses the current activity + audio, shows a slider,
     * resumes on close.
     * volume (0..100) is persisted. volumeInput carries nav keys from the main
     * thread to the render
     * thread, where all audio ops happen.
     */
    private volatile boolean volumeToggleRequested;
    private volatile int volume = 100;
    private final java.util.Queue<Integer> volumeInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final VolumeOverlay volumeOverlay = new VolumeOverlay(volumeInput); // STEP 5a: F2 volume overlay extracted

    /*
     * In-game PAUSE menu (Esc during the rhythm game): Resume / Options / Quit,
     * like the volume menu it
     * freezes the game + pauses the audio. "Options" opens the key-remap
     * sub-screen. remapLane >= 0 means
     * that screen is waiting to capture the next key press for that lane. All on
     * the render thread; onKey
     * just enqueues to pauseInput.
     */
    private final java.util.Queue<Integer> pauseInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final PauseOverlay pauseOverlay = new PauseOverlay(pauseInput); // STEP 5b: pause/remap overlay extracted
    /*
     * Tetris launch is deferred to the render thread (like the rhythm game) so its
     * song decode happens
     * there, not on the main thread inside the key callback.
     */
    private volatile boolean tetrisStartRequested;

    /*
     * Recovery Mode rhythm game ("BAMFBEAT", state == Mode.RHYTHM). rhythmInput
     * carries timestamped lane
     * events ({key, action, nanoTime}) from the main thread to the render thread;
     * the RhythmScreen host drains
     * it + owns the engine. Extracted to early.screen.modes.RhythmScreen (Phase
     * 1.75 STEP 6).
     */
    private volatile boolean rhythmStartRequested;
    private final java.util.Queue<long[]> rhythmInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final RhythmScreen rhythmScreen = new RhythmScreen(rhythmInput);

    /*
     * A song's optional SPECIAL mode (state == Mode.SPECIAL): a distinct gameplay
     * session, launched by picking
     * the "Special" row in the song's jukebox difficulty menu (only shown when
     * SongDef.special != null). Hosted
     * like the rhythm game -- onKey enqueues timestamped events to specialInput;
     * the render thread builds/
     * steps/draws the session.
     */
    private volatile boolean specialStartRequested;
    private final java.util.Queue<long[]> specialInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final SpecialScreen specialScreen = new SpecialScreen(specialInput); // STEP 3: SPECIAL host extracted
    /*
     * RESULTS screen (Mode.RESULTS): a song's end card -- score + grade ranking +
     * status, with Retry/Play-Again
     * and an explicit back-to-BIOS (no auto-bounce). Extracted to
     * early.screen.modes.ResultsScreen (.75
     * STEP 6); the provider keeps only the resultsInput queue (onKey enqueues) +
     * the resultsScreen field.
     */
    private final java.util.Queue<Integer> resultsInput = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ResultsScreen resultsScreen = new ResultsScreen(resultsInput);
    /*
     * chart editor (Mode.EDITOR) mouse/scroll plumbing. editorMouse
     * carries {button, action, winX,
     * winY} per click; editorScroll carries {dx, dy} per wheel notch -- both in
     * WINDOW pixel space; the editor
     * render thread converts to logical via windowToLogicalX/Y. mouseWinX/Y hold
     * the latest cursor position so a
     * click event can attach the current position (the button callback gives no
     * coords). Editor KEYBOARD input
     * still arrives through onKey (the EDITOR branch is added with the screen in
     * 5B).
     */
    private final java.util.Queue<double[]> editorMouse = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<double[]> editorScroll = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile double mouseWinX;
    private volatile double mouseWinY;
    /*
     * the chart editor screen (Mode.EDITOR), hosted like
     * rhythmScreen/tetrisScreen. editorKey carries
     * {key, action, mods} from onKey; the render thread drains it + the
     * mouse/scroll queues. editorStartRequested
     * defers the launch to the render thread (built/entered there, like the rhythm
     * + tetris launches).
     */
    private final java.util.Queue<int[]> editorKey = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean editorStartRequested;
    private final EditorScreen editorScreen = new EditorScreen(editorKey, editorMouse, editorScroll);
    /*
     * TEST PLAY: the editor's in-memory chart handed to the rhythm game
     * (copied), the start offset, and a
     * flag routing the run's end back to the editor instead of RESULTS/SETUP.
     */
    private volatile java.util.List<com.retro.bamfmenu.early.core.rhythm.RhythmEngine.Note> testChart;
    private volatile int testStartMs;
    private volatile int testApproach;
    private volatile boolean editorTestReturn;
    /*
     * The physical key for each lane -- remappable in the in-game Options screen,
     * persisted to the BIOS
     * config. Defaults to the arrow keys. Mutated only on the render thread (the
     * remap), read by RhythmScreen
     * via ctx.laneKeys(). Lane colours live in Art.LANE_COLORS (shared, public);
     * the lane glyphs / waveform /
     * SFX constants moved to RhythmScreen with the game.
     */
    private final int[] laneKeys = { GLFW_KEY_LEFT, GLFW_KEY_DOWN, GLFW_KEY_UP, GLFW_KEY_RIGHT };

    /*
     * The jukebox songs now live in SongRegistry.SONGS as self-describing SongDef
     * rows (id/name/label/ogg/bpm
     * + per-song DetectorConfig + left-panel supplier). Add a song there, not here.
     */
    /*
     * Jukebox ("Select Song") = a sub-menu of SETUP. songMenuOpen routes the
     * arrows/ENTER to the song list in
     * the right panel; songSel is the cursor there. recoveryRequiredError shows the
     * terse locked error (for
     * Select Song or the achievement) until you navigate away. currentSong drives
     * which OGG/chart/label the
     * rhythm game uses and which high-score key it banks. rhythmHigh persists
     * per-song scores (cache).
     */
    private volatile boolean songMenuOpen;
    private volatile int songSel;
    private volatile boolean recoveryRequiredError;
    private volatile SongDef currentSong;
    /*
     * The LIVE jukebox list: the bundled SongRegistry.SONGS followed by the user's on-disk songs
     * (BAMFOS/songs/, forks + drop-ins + converted maps). Rebuilt each time "Select Song" opens, so a
     * newly-added folder shows up without a relaunch. All jukebox nav + render reads THIS, not the bundled
     * array directly; only the Recovery launch + the reserved-id check stay bundled-only by design. Starts as
     * the bundled list so an arrow press before the first open never NPEs.
     */
    private volatile java.util.List<SongDef> jukebox = java.util.List.of(SongRegistry.SONGS);
    /*
     * High scores + the grade source per (song, difficulty): both maps are keyed
     * "id.difficultyToken"
     * (scoreKey). rhythmHigh = best score; rhythmPerf = the perfect-% (0..100) of
     * the run that set it, so the
     * grade shown alongside is that record run's. Both are cache (Reset Cache wipes
     * them).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> rhythmHigh = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> rhythmPerf = new java.util.concurrent.ConcurrentHashMap<>();
    /*
     * Jukebox difficulty sub-menu: after picking a song (songMenuOpen), ENTER opens
     * the difficulty list
     * (difficultyMenu); difficultySel indexes the song's available difficulties
     * (Easy/Normal/Hard, + Special
     * when the song defines one). currentDifficulty is the one the rhythm game is
     * playing / banking under.
     */
    private volatile boolean difficultyMenu;
    private volatile int difficultySel;
    private volatile SongDifficulty currentDifficulty; // the selected difficulty (data-driven); null until a launch sets it

    /*
     * The reactive left panel + the SCORE odometer animation moved into
     * RhythmScreen with the game (.75
     * STEP 6).
     */

    /*
     * Transitional lead-in (render thread only): a shared
     * "settle on the UI for a beat" pause used by the
     * song/scripted modes (RHYTHM + SPECIAL). The mode's screen is shown with the
     * freshly-played song PAUSED
     * at ~0 so the player can take in the screen before anything starts -- a
     * deliberate "here we go" moment
     * (e.g. after the cutscene's scramble-into-the-game, and on every jukebox
     * start). A start arms it via
     * armLeadIn() after pausing the audio; the mode's step calls holdingLeadIn() to
     * hold the clock + swallow
     * input until the deadline, then playback resumes. 0 = not in a lead-in.
     * Pausing at ~0 also keeps the
     * first notes from being eaten by the chart-gen time that elapses between
     * play() and the first frame.
     */
    private static final long LEAD_IN_MS = 1500;
    /*
     * When the user leaves BIOS back to POST at the (already-held) hand-off, show
     * the live POST terminal for
     * this long before handing off, so the exit reads BIOS -> POST -> loading
     * overlay instead of freezing on
     * the BIOS frame. Only applies after BIOS was used; the normal boot is
     * unaffected.
     */
    private static final long POST_EXIT_GRACE_MS = 900;
    private volatile LeadIn leadIn; // the lead-in's single source of truth; volatile: inSpecialEvent() reads the
                                    // reference from onKey (main thread)

    /*
     * Recovery Mode intro cutscene (state == Mode.CUTSCENE): extracted to
     * early.screen.modes.CutsceneScreen
     * -- it owns the CutsceneTimeline + the
     * jingle/stabs/face-draw. The provider keeps the
     * cutsceneStartRequested drain flag + the shared face art (faceA/faceB, also
     * drawn by the SETUP
     * achievement panel, read by the cutscene via ctx.recoveryFaceA/B). The song
     * itself opens on a brief idle
     * game-screen lead-in (see leadIn).
     */
    private volatile boolean cutsceneStartRequested;
    private final CutsceneScreen cutsceneScreen = new CutsceneScreen();
    private long errorFlashUntilNanos; // deadline to show the red "ERROR" when a menu is blocked by a special event
    private volatile boolean eventKeyBlockedRequested; // onKey -> render thread: a key was blocked by a special event
    private String[] faceA = new String[0];
    private String[] faceB = new String[0];

    @Override
    public String name() {
        return "bamfmenu_terminal";
    }

    @Override
    public Runnable initialize(String[] arguments) {
        parseArgs(arguments);
        loadBiosConfig();
        cacheSystemInfo();
        bamfosArt = Art.loadArt("/ui/bamfos.txt");
        sixxArt = Art.loadArt("/ui/sixxhands.txt");
        faceA = Art.loadArt("/songs/recovery/cutscene/face_a.txt"); // shared: cutscene reveal (ctx.recoveryFaceA) +
                                                                    // SETUP achievement panel
        faceB = Art.loadArt("/songs/recovery/cutscene/face_b.txt");
        StartupNotificationManager.modLoaderConsumer().ifPresent(c -> c.accept("NeoForge loading " + forgeVersion));
        this.renderScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("bamfmenu-early-render");
            t.setDaemon(true);
            return t;
        });
        createWindow();
        this.initFuture = renderScheduler.schedule(this::initRender, 1, TimeUnit.MILLISECONDS);
        return this::periodicTick;
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--width" -> winWidth = parseInt(args[i + 1], winWidth);
                case "--height" -> winHeight = parseInt(args[i + 1], winHeight);
                case "--fml.mcVersion" -> mcVersion = args[i + 1];
                case "--fml.neoForgeVersion" -> forgeVersion = args[i + 1];
                case "--gameDir" -> gameDir = args[i + 1];
                default -> {
                }
            }
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /*
     * Real device info for the POST screen, gathered without any platform-specific
     * calls so it works on
     * Windows, Linux and macOS (the values just read differently, e.g. os.name =
     * "Linux").
     */
    private void cacheSystemInfo() {
        cpuInfo = System.getProperty("os.arch", "x86") + "   x" + Runtime.getRuntime().availableProcessors();
        osInfo = (System.getProperty("os.name", "Unknown") + " " + System.getProperty("os.version", "")).trim();
        javaInfo = System.getProperty("java.version", "");
        long max = Runtime.getRuntime().maxMemory();
        memTotalKb = (max <= 0 || max == Long.MAX_VALUE) ? 65536L : max / 1024L;
        try {
            LocalDate d = LocalDate.now();
            biosDate = String.format("%02d/%02d/%02d", d.getMonthValue(), d.getDayOfMonth(), d.getYear() % 100);
        } catch (Throwable ignored) {
        }
    }

    /*
     * Persist the BIOS's saved state across reboots. The early window runs before
     * Minecraft, so we keep
     * our own store -- now an OBFUSCATED BINARY blob under
     * <gameDir>/BAMFOS/cache/bios.dat (see BiosCache),
     * out of the config folder and not a plaintext file a player edits in Notepad.
     * Read once at startup;
     * written whenever something changes. BiosCache migrates the old plaintext
     * config on first run. The
     * in-memory shape stays java.util.Properties so this load/save logic is
     * unchanged apart from the
     * source/sink.
     */
    private void loadBiosConfig() {
        try {
            Properties props = BiosCache.load(gameDir);
            blueTheme = Boolean.parseBoolean(props.getProperty("blue", "false"));
            safeForRetro = Boolean.parseBoolean(props.getProperty("safeForRetro", "false"));
            gameBeaten = Boolean.parseBoolean(props.getProperty("gameBeaten", "false"));
            biosAchievement = Boolean.parseBoolean(props.getProperty("biosAchievement", "false"));
            tetrisHighScore = parseInt(props.getProperty("tetrisHighScore", "0"), 0);
            volume = Math.max(0, Math.min(100, parseInt(props.getProperty("volume", "100"), 100)));
            // Data-driven: load every "rhythmHigh.<id>.<token>" key directly (so built-in AND user/imported songs
            // with any difficulty token persist). A single-segment "rhythmHigh.<id>" is a pre-difficulty legacy
            // score -> migrate it to that song's Normal.
            for (String pk : props.stringPropertyNames()) {
                if (!pk.startsWith("rhythmHigh.")) {
                    continue;
                }
                String key = pk.substring("rhythmHigh.".length());
                int sc = parseInt(props.getProperty(pk, "0"), 0);
                if (sc <= 0) {
                    continue;
                }
                if (key.indexOf('.') < 0) {
                    rhythmHigh.putIfAbsent(key + "." + Difficulty.NORMAL.token, sc); // legacy per-song score -> Normal
                } else {
                    rhythmHigh.put(key, sc);
                    int pct = parseInt(props.getProperty("rhythmPerf." + key, "0"), 0);
                    if (pct > 0) {
                        rhythmPerf.put(key, pct);
                    }
                }
            }
            for (int i = 0; i < laneKeys.length; i++) {
                laneKeys[i] = parseInt(props.getProperty("key" + i, Integer.toString(laneKeys[i])), laneKeys[i]);
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveBiosConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("blue", Boolean.toString(blueTheme));
            props.setProperty("safeForRetro", Boolean.toString(safeForRetro));
            props.setProperty("gameBeaten", Boolean.toString(gameBeaten));
            props.setProperty("biosAchievement", Boolean.toString(biosAchievement));
            props.setProperty("tetrisHighScore", Integer.toString(tetrisHighScore));
            props.setProperty("volume", Integer.toString(volume));
            for (var e : rhythmHigh.entrySet()) { // iterate the live map -> persists ALL scores (built-in + user songs)
                int sc = e.getValue() != null ? e.getValue() : 0;
                if (sc > 0) {
                    props.setProperty("rhythmHigh." + e.getKey(), Integer.toString(sc));
                    props.setProperty("rhythmPerf." + e.getKey(), Integer.toString(rhythmPerf.getOrDefault(e.getKey(), 0)));
                }
            }
            for (int i = 0; i < laneKeys.length; i++) {
                props.setProperty("key" + i, Integer.toString(laneKeys[i]));
            }
            BiosCache.save(gameDir, props); // obfuscated binary under BAMFOS/cache -- not config/*.properties
        } catch (Throwable ignored) {
        }
    }

    /* ---- window creation (main thread; context is NOT made current here) ---- */

    private void createWindow() {
        if (!glfwInit()) {
            throw new IllegalStateException("bamfmenu early window: glfwInit failed");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        int[][] versions = { { 4, 6 }, { 4, 5 }, { 4, 3 }, { 4, 2 }, { 4, 1 }, { 4, 0 }, { 3, 3 }, { 3, 2 } };
        for (int[] v : versions) {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, v[0]);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, v[1]);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            window = glfwCreateWindow(winWidth, winHeight, APP_TITLE, 0L, 0L);
            if (window != 0L) {
                glVersion = v[0] + "." + v[1];
                break;
            }
        }
        if (window == 0L) {
            throw new IllegalStateException(
                    "bamfmenu early window: failed to create a GLFW window with any GL profile");
        }

        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor != 0L ? glfwGetVideoMode(monitor) : null;
        if (mode != null) {
            int[] mx = new int[1];
            int[] my = new int[1];
            glfwGetMonitorPos(monitor, mx, my);
            int[] ww = new int[1];
            int[] wh = new int[1];
            glfwGetWindowSize(window, ww, wh);
            glfwSetWindowPos(window, (mode.width() - ww[0]) / 2 + mx[0], (mode.height() - wh[0]) / 2 + my[0]);
        }

        this.fbCallback = GLFWFramebufferSizeCallback.create((w, width, height) -> {
            if (w == window && width > 0 && height > 0) {
                fbWidth = width;
                fbHeight = height;
            }
        });
        this.sizeCallback = GLFWWindowSizeCallback.create((w, width, height) -> {
            if (w == window && width > 0 && height > 0) {
                winWidth = width;
                winHeight = height;
            }
        });
        this.posCallback = GLFWWindowPosCallback.create((w, x, y) -> {
            if (w == window) {
                winX = x;
                winY = y;
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback);
        glfwSetWindowSizeCallback(window, sizeCallback);
        glfwSetWindowPosCallback(window, posCallback);
        /*
         * Keyboard for the DEL-to-enter BIOS SETUP. We own the window until hand-off,
         * after which we
         * free this so Minecraft's KeyboardHandler takes over.
         */
        this.keyCallback = GLFWKeyCallback.create(this::onKey);
        glfwSetKeyCallback(window, keyCallback);
        this.charCallback = GLFWCharCallback.create(this::onChar);
        glfwSetCharCallback(window, charCallback);
        /*
         * mouse + scroll for the chart editor. cursorPos ALWAYS records the
         * latest position (cheap,
         * harmless); button + scroll only ENQUEUE while state == EDITOR, so no other
         * screen is affected.
         */
        this.cursorPosCallback = GLFWCursorPosCallback.create((w, x, y) -> {
            if (w == window) {
                mouseWinX = x;
                mouseWinY = y;
            }
        });
        glfwSetCursorPosCallback(window, cursorPosCallback);
        this.mouseButtonCallback = GLFWMouseButtonCallback.create((w, button, action, mods) -> {
            if (w == window && state == Mode.EDITOR) {
                editorMouse.offer(new double[] { button, action, mouseWinX, mouseWinY });
            }
        });
        glfwSetMouseButtonCallback(window, mouseButtonCallback);
        this.scrollCallback = GLFWScrollCallback.create((w, dx, dy) -> {
            if (w == window && state == Mode.EDITOR) {
                editorScroll.offer(new double[] { dx, dy });
            }
        });
        glfwSetScrollCallback(window, scrollCallback);

        glfwShowWindow(window);
        int[] a = new int[1];
        int[] b = new int[1];
        glfwGetWindowPos(window, a, b);
        winX = a[0];
        winY = b[0];
        glfwGetWindowSize(window, a, b);
        winWidth = a[0];
        winHeight = b[0];
        glfwGetFramebufferSize(window, a, b);
        fbWidth = a[0];
        fbHeight = b[0];
        glfwPollEvents();
    }

    /* ---- GL setup (render thread; absorbs the context) ---- */

    private void initRender() {
        boolean contextCurrent = false;
        try {
            glfwMakeContextCurrent(window);
            contextCurrent = true;
            glfwSwapInterval(1);
            GL.createCapabilities();
            captureGpuName();

            shader = new ElementShader();
            shader.init();
            /*
             * Our own atlas (box-drawing + shades + arrows) baked from the master-setting
             * font, not FML's
             * ASCII-only SimpleFont.
             */
            font = new RichFont(ACTIVE_FONT.resource(), ACTIVE_FONT.size(), FONT_TEX, FONT_ATLAS, FONT_ATLAS,
                    ACTIVE_FONT.smooth());
            buffer = new SimpleBufferBuilder(200000);
            lineSpacing = font.lineSpacing();
            charWidth = Math.max(1, font.stringWidth("0"));
            canvas = new TerminalCanvas(shader, font, buffer, FONT_TEX); // STEP 1: text rendering moves here

            glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } catch (Throwable t) {
            initFailed = true;
            System.err.println(
                    "[bamfmenu] early terminal init failed; the boot window will be blank but the game still loads: "
                            + t);
            t.printStackTrace();
        } finally {
            /*
             * Release the context from the render thread on EVERY path, even on failure: a
             * context left
             * current here would be taken by the main thread at hand-off too, which is the
             * macOS-fatal
             * two-threads-current crash.
             */
            if (contextCurrent) {
                glfwMakeContextCurrent(0L);
            }
        }
        /*
         * Only tick frames if GL came up. A failed init leaves a blank window that
         * still hands off
         * cleanly (the context was released above, windowTick stays null).
         */
        if (!initFailed) {
            windowTick = renderScheduler.scheduleAtFixedRate(this::renderFrame, 50, 50, TimeUnit.MILLISECONDS);
        }
    }

    /*
     * The real GPU name (GL_RENDERER) for the "Display Adapter" POST line. Render
     * thread only (context
     * must be current), so no synchronization needed; drawTerminal runs on the same
     * thread.
     */
    private void captureGpuName() {
        try {
            String r = glGetString(GL_RENDERER);
            gpuName = (r == null || r.isBlank()) ? "Generic Display" : r;
        } catch (Throwable t) {
            gpuName = "Generic Display";
        }
    }

    /* ---- per-frame render (render thread) ---- */

    private void renderFrame() {
        if (handedOff || shader == null || font == null) {
            return;
        }
        if (!renderLock.tryAcquire()) {
            return;
        }
        boolean madeCurrent = false;
        try {
            glfwMakeContextCurrent(window);
            madeCurrent = true;
            int[] w = new int[1];
            int[] h = new int[1];
            glfwGetFramebufferSize(window, w, h);
            if (w[0] > 0 && h[0] > 0) {
                fbWidth = w[0];
                fbHeight = h[0];
            }
            ensureOffscreen(fbWidth, fbHeight);
            canvas.setFrame(fbWidth, fbHeight, blueTheme); // STEP 2: refresh the canvas per-frame fb size + theme

            /* Render into the offscreen framebuffer... */
            glBindFramebuffer(GL_FRAMEBUFFER, offscreenFbo);
            glViewport(0, 0, fbWidth, fbHeight);
            float[] bg = blueTheme ? BG_BLUE : BG_BLACK;
            glClearColor(bg[0], bg[1], bg[2], 1.0F);
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            if (volumeToggleRequested) {
                volumeToggleRequested = false;
                volumeOverlay.toggle(biosCtx); // OpenAL pause/resume stays on the render thread
            }
            if (eventKeyBlockedRequested) {
                eventKeyBlockedRequested = false;
                errorFlashUntilNanos = System.nanoTime() + 1_000_000_000L; // ~1s red ERROR (Esc during a special event)
            }
            if (volumeOverlay.active()) {
                volumeOverlay.step(biosCtx);
            }
            if (pauseOverlay.active()) {
                pauseOverlay.step(biosCtx);
            }
            if (rhythmStartRequested) {
                rhythmStartRequested = false;
                startRhythm();
            }
            if (tetrisStartRequested) {
                tetrisStartRequested = false;
                tetrisScreen.onEnter(biosCtx);
            }
            if (cutsceneStartRequested) {
                cutsceneStartRequested = false;
                cutsceneScreen.onEnter(biosCtx);
            }
            if (specialStartRequested) {
                specialStartRequested = false;
                specialScreen.onEnter(biosCtx);
            }
            if (editorStartRequested) {
                editorStartRequested = false;
                editorScreen.onEnter(biosCtx);
            }
            if (snakeStartRequested) {
                snakeStartRequested = false;
                snakeScreen.onEnter(biosCtx);
            }
            if (galagaStartRequested) {
                galagaStartRequested = false;
                galagaScreen.onEnter(biosCtx);
            }
            if (doomStartRequested) {
                doomStartRequested = false;
                doomScreen.onEnter(biosCtx);
            }
            switch (state) {
                case CUTSCENE -> {
                    if (!volumeOverlay.active()) {
                        cutsceneScreen.step(biosCtx);
                    }
                    if (state == Mode.CUTSCENE) {
                        cutsceneScreen.render(canvas, biosCtx); // skip if step just handed off to the song this frame
                    }
                }
                case RHYTHM -> {
                    if (!volumeOverlay.active() && !pauseOverlay.active()) {
                        rhythmScreen.step(biosCtx); // frozen while the volume or pause menu is up
                    }
                    rhythmScreen.render(canvas, biosCtx); // engine==null after an exit -> no-op (matches the old
                                                          // drawRhythm guard)
                }
                case TETRIS -> {
                    if (!volumeOverlay.active() && !pauseOverlay.active()) {
                        tetrisScreen.step(biosCtx); // frozen while the volume or pause menu is up
                    }
                    if (state == Mode.TETRIS) {
                        tetrisScreen.render(canvas, biosCtx);
                    }
                }
                case SPECIAL -> {
                    if (!volumeOverlay.active() && !pauseOverlay.active()) {
                        specialScreen.step(biosCtx); // frozen while the volume or pause menu is up
                    }
                    if (state == Mode.SPECIAL) {
                        specialScreen.render(canvas, biosCtx); // skip if step just exited this frame
                    }
                }
                case RESULTS -> {
                    if (!volumeOverlay.active()) {
                        resultsScreen.step(biosCtx); // ENTER retry / ESC BIOS
                    }
                    if (state == Mode.RESULTS) {
                        resultsScreen.render(canvas, biosCtx); // skip if step just transitioned out this frame
                    }
                }
                case EDITOR -> {
                    if (!volumeOverlay.active() && !pauseOverlay.active()) {
                        editorScreen.step(biosCtx); // frozen while the volume menu is up
                    }
                    if (state == Mode.EDITOR) {
                        editorScreen.render(canvas, biosCtx); // skip if step just exited to SETUP this frame
                    }
                }
                case SNAKE -> {
                    if (!volumeOverlay.active()) {
                        snakeScreen.step(biosCtx); // frozen while the volume menu is up
                    }
                    if (state == Mode.SNAKE) {
                        snakeScreen.render(canvas, biosCtx); // skip if step just exited to SETUP this frame
                    }
                }
                case GALAGA -> {
                    if (volumeOverlay.active()) {
                        galagaScreen.clearHeld(); // frozen by the volume menu: drop held movement so a key-RELEASE the overlay swallowed can't leave the ship drifting on resume
                    } else {
                        galagaScreen.step(biosCtx);
                    }
                    if (state == Mode.GALAGA) {
                        galagaScreen.render(canvas, biosCtx); // skip if step just exited to SETUP this frame
                    }
                }
                case DOOM -> {
                    if (!volumeOverlay.active()) {
                        doomScreen.step(biosCtx); // DOOM keeps running on its daemon thread; we just pause input while the menu is up
                    }
                    if (state == Mode.DOOM) {
                        doomScreen.render(canvas, biosCtx); // skip if step just exited to SETUP this frame
                    }
                }
                case SETUP -> drawSetup();
                default -> drawTerminal(); // POST
            }
            drawControlBar(); // the single shared, centered bottom control bar (per-state hints)
            if (volumeOverlay.active()) {
                volumeOverlay.render(canvas, biosCtx); // overlay on top of the frozen activity
            }
            if (pauseOverlay.active()) {
                pauseOverlay.render(canvas, biosCtx); // Resume/Options/Quit (or the key-remap sub-screen) over the
                                                      // frozen game
            }
            drawErrorFlash(); // brief red ERROR when an action was blocked (no-op unless recently triggered)

            /*
             * ...then blit it to the window, flipping Y (src top->bottom) so the text is
             * right-side up.
             */
            glBindFramebuffer(GL_READ_FRAMEBUFFER, offscreenFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glBlitFramebuffer(0, fbHeight, fbWidth, 0, 0, 0, fbWidth, fbHeight, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glfwSwapBuffers(window);
        } catch (Throwable t) {
            System.err.println("[bamfmenu] early terminal frame error: " + t);
        } finally {
            /*
             * Always release the context this frame made current, independent of handedOff:
             * gating on
             * handedOff would let a hand-off that flips the flag mid-frame leave the
             * context stuck on
             * this thread, so the main thread could never take it.
             */
            if (madeCurrent) {
                glfwMakeContextCurrent(0L);
            }
            renderLock.release();
        }
        frame++;
    }

    /*
     * Create / resize the offscreen colour framebuffer to match the window
     * framebuffer.
     */
    private void ensureOffscreen(int w, int h) {
        if (w < 1 || h < 1) {
            return;
        }
        if (offscreenFbo == 0) {
            offscreenFbo = glGenFramebuffers();
            offscreenTex = glGenTextures();
        }
        if (w != offscreenW || h != offscreenH) {
            glBindTexture(GL_TEXTURE_2D, offscreenTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, offscreenFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, offscreenTex, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            offscreenW = w;
            offscreenH = h;
        }
    }

    private void drawTerminal() {
        int logicalW = LOGICAL_W;
        int logicalH = Math.max(1, LOGICAL_W * fbHeight / Math.max(1, fbWidth));
        int maxChars = Math.max(16, logicalW / charWidth - 1);

        pumpLog(maxChars);

        canvas.beginFrameShader(logicalW, logicalH);

        /*
         * Header: the BAMFOS wordmark (gold, left) and the SixxHands logo (white,
         * right) at ONE shared
         * glyph size so they read as a single terminal banner, then a rule line across
         * the full width
         * underneath both. The POST runs below the rule.
         */
        int headerBottom = drawHeader(logicalW, logicalH);
        int ruleY = headerBottom; // sits just under the logos; headerBottom already clears their last row
        drawText(MARGIN, ruleY, rule(maxChars), cFg());
        int headTop = ruleY + lineSpacing * 2;
        List<String> head = biosHeader(maxChars);
        int revealed = Math.min(head.size(), Math.max(0, frame) / 3);
        for (int i = 0; i < revealed; i++) {
            int colour = i == 0 ? cDim() : cFg(); // BIOS version line dimmed, hardware lines normal
            drawText(MARGIN, headTop + i * lineSpacing, head.get(i), colour);
        }

        int logTop = headTop + (head.size() + 1) * lineSpacing;
        int logBottom = logicalH - lineSpacing * 3;
        int logLines = Math.max(1, (logBottom - logTop) / lineSpacing);
        List<String> recent = recentLog(logLines);
        for (int i = 0; i < recent.size(); i++) {
            drawText(MARGIN, logTop + i * lineSpacing, recent.get(i), i == recent.size() - 1 ? cBright() : cFg());
        }

        /* Boot progress (spinner + ASCII bar) and the classic SETUP-prompt footer. */
        drawText(MARGIN, logicalH - lineSpacing * 2, statusLine(maxChars), cBright());
        drawText(MARGIN, logicalH - lineSpacing, footerLine(maxChars), cDim());
    }

    /*
     * STEP 1: text rendering moved to TerminalCanvas; this forwards so the many
     * existing call sites are
     * unchanged (they inline to canvas.text as their screens are extracted in later
     * steps).
     */
    private void drawText(int x, int y, String text, int colour) {
        canvas.text(x, y, text, colour);
    }

    /*
     * The detected-hardware POST block. The memory line counts up over the first
     * ~2s then reports OK,
     * and device detection flips to OK once the count finishes, mimicking a real
     * power-on self test.
     */
    private List<String> biosHeader(int maxChars) {
        boolean memDone = frame >= MEM_FRAMES;
        long shownKb = memDone ? memTotalKb : memTotalKb * Math.max(0, frame) / MEM_FRAMES;
        List<String> h = new ArrayList<>();
        h.add("BAMF-BIOS v4.51PG    " + biosDate);
        h.add("Main Processor   : " + cpuInfo);
        h.add("Memory Testing   : " + shownKb + "K" + (memDone ? "  OK" : ""));
        h.add("Display Adapter  : " + (gpuName == null ? "Detecting ..." : gpuName));
        h.add("System           : " + osInfo + "   Java " + javaInfo);
        List<String> out = new ArrayList<>(h.size());
        for (String s : h) {
            out.add(clip(s, maxChars));
        }
        return out;
    }

    /*
     * Draws the BAMFOS wordmark (gold, left) and the SixxHands logo (white, right)
     * at ONE shared glyph
     * size, so the header reads as a single terminal banner. Both render in the
     * SAME wide logical canvas
     * -- sized from the BAMFOS art so the banner spans ~HEADER_FW of the width --
     * and using one canvas for
     * both is exactly what makes their font size identical; because it is
     * width-fraction based they scale
     * together with the window. Returns the body-coordinate Y of the lower logo so
     * the caller can place
     * the rule line + POST beneath them.
     */
    private int drawHeader(int bodyW, int bodyH) {
        String[] bamf = bamfosArt.length > 0 ? bamfosArt : new String[] { "B A M F O S" };
        int artWbamf = Math.max(1, blockWidth(bamf));
        double aspect = (double) fbHeight / Math.max(1, fbWidth);
        double bw = artWbamf / HEADER_FW; // shared canvas width -> identical glyph size for both logos
        double bh = Math.max(1.0, bw * aspect);
        double y = lineSpacing + lineSpacing; // top margin + first-line baseline

        canvas.logicalSize((int) Math.ceil(bw), (int) Math.ceil(bh));
        for (int i = 0; i < bamf.length; i++) {
            drawText((int) (bw * 0.02), (int) (y + i * lineSpacing), bamf[i], cGold()); // left
        }
        double bottomLogo = y + bamf.length * lineSpacing;
        if (sixxArt.length > 0 && font != null) {
            int artWsixx = Math.max(1, blockWidth(sixxArt));
            double xSixx = bw - artWsixx - bw * 0.02; // right-aligned, drawn at the SAME glyph size
            for (int i = 0; i < sixxArt.length; i++) {
                drawText((int) xSixx, (int) (y + i * lineSpacing), sixxArt[i], cFg());
            }
            bottomLogo = Math.max(bottomLogo, y + sixxArt.length * lineSpacing);
        }
        canvas.logicalSize(bodyW, bodyH); // restore the body coordinate space

        return (int) (bottomLogo * bodyW / bw); // logo-canvas Y -> body Y (both canvases share the aspect)
    }

    /*
     * The body-coordinate Y just under the header logos, WITHOUT drawing -- mirrors
     * drawHeader's return so a
     * layout pass can budget the space the header will take before choosing the
     * canvas size. The logos are
     * width-anchored (HEADER_FW of the screen), so this is linear in bodyW and
     * headerBottomBodyY(w)/w is a
     * constant, aspect-independent fraction.
     */
    private int headerBottomBodyY(int bodyW) {
        String[] bamf = bamfosArt.length > 0 ? bamfosArt : new String[] { "B A M F O S" };
        int artWbamf = Math.max(1, blockWidth(bamf));
        double bw = artWbamf / HEADER_FW;
        double y = lineSpacing + lineSpacing;
        double bottomLogo = y + bamf.length * lineSpacing;
        if (sixxArt.length > 0 && font != null) {
            bottomLogo = Math.max(bottomLogo, y + sixxArt.length * lineSpacing);
        }
        return (int) (bottomLogo * bodyW / bw);
    }

    /*
     * A full-width terminal rule the body font draws across the screen under the
     * header (real box-drawing
     * horizontal now that the font is Consolas).
     */
    private static String rule(int maxChars) {
        return TerminalCanvas.rule(maxChars);
    }

    /* ---- BIOS SETUP (input + rendering) ---- */

    /*
     * Keyboard for the early window (main thread, fired during glfwPollEvents).
     * Before SETUP, DEL opens
     * it; inside SETUP the arrows move, Enter activates, ESC leaves. Everything
     * else is ignored (after
     * hand-off this callback is freed and Minecraft's own keyboard handler takes
     * over).
     */
    private void onKey(long w, int key, int scancode, int action, int mods) {
        if (handedOff) {
            return;
        }
        if (pauseOverlay.active()) {
            /*
             * While the in-game pause menu is up, EVERY key goes to it (the render thread
             * interprets nav vs
             * the remap key-capture); the game underneath stays frozen, and F2 is
             * suppressed.
             */
            if (action == GLFW_PRESS) {
                pauseInput.offer(key);
            }
            return;
        }
        /*
         * F2 = global volume menu, handled before any game routing so it works during
         * Tetris and the
         * rhythm game too. It pauses the current activity; F2 / Enter / Esc closes it
         * and resumes.
         */
        if (key == GLFW_KEY_F2 && action == GLFW_PRESS) {
            volumeToggleRequested = true;
            return;
        }
        if (volumeOverlay.active()) {
            /*
             * While the volume menu is up, nav keys go to it (the render thread applies
             * them) and the
             * activity beneath it stays frozen.
             */
            if (action == GLFW_PRESS) {
                volumeInput.offer(key);
            }
            return;
        }
        if (inSpecialEvent()) {
            /*
             * Locked transition (the cutscene + the pre-song lead-in): no menus open here
             * and the sequence
             * cannot be aborted. Esc just flashes a red ERROR (bottom-left); every other
             * key is ignored so
             * the sequence runs uninterrupted. (F2 is handled above and also flashes via
             * inSpecialEvent.)
             */
            if (action == GLFW_PRESS && key == GLFW_KEY_ESCAPE) {
                eventKeyBlockedRequested = true;
            }
            return;
        }
        if (state == Mode.RHYTHM) {
            /*
             * The rhythm game needs key-UP for hold notes, so it takes PRESS and RELEASE
             * (not REPEAT). We
             * only enqueue the event + its timestamp here; the render thread judges it
             * against the audio
             * clock.
             */
            if (action == GLFW_PRESS || action == GLFW_RELEASE) {
                rhythmInput.offer(new long[] { key, action, System.nanoTime() });
            }
            return;
        }
        if (state == Mode.SPECIAL) {
            /*
             * Like the rhythm game: enqueue PRESS/RELEASE + timestamp; the render thread
             * interprets them
             * (ESC = quit, handled in stepSpecial).
             */
            if (action == GLFW_PRESS || action == GLFW_RELEASE) {
                specialInput.offer(new long[] { key, action, System.nanoTime() });
            }
            return;
        }
        if (state == Mode.RESULTS) {
            /*
             * End card: only ENTER (retry/play again) + ESC (BIOS) matter; the render
             * thread interprets them.
             */
            if (action == GLFW_PRESS) {
                resultsInput.offer(key);
            }
            return;
        }
        if (state == Mode.EDITOR) {
            /*
             * The chart editor: enqueue key + action + mods (Ctrl/Shift) on PRESS/REPEAT;
             * the render thread
             * interprets them. Mouse/scroll arrive via the GLFW callbacks
             * (editorMouse/editorScroll).
             */
            if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                editorKey.offer(new int[] { key, action, mods });
            }
            return;
        }
        if (state == Mode.GALAGA) {
            /*
             * Galaga tracks HELD left/right, so it takes PRESS and RELEASE (not REPEAT); the
             * render thread drains {key, action} and interprets. Enqueue-only here (main thread).
             */
            if (action == GLFW_PRESS || action == GLFW_RELEASE) {
                galagaInput.offer(new int[] { key, action });
            }
            return;
        }
        if (state == Mode.DOOM) {
            /* DOOM needs HELD movement + key-up, so PRESS and RELEASE (not REPEAT); the render thread drains
               {key, action} and feeds the engine. Enqueue-only here (main thread). */
            if (action == GLFW_PRESS || action == GLFW_RELEASE) {
                doomInput.offer(new int[] { key, action });
            }
            return;
        }
        if (action != GLFW_PRESS && action != GLFW_REPEAT) {
            return;
        }
        if (key == GLFW_KEY_F3 && action == GLFW_PRESS) {
            rhythmStartRequested = true; // dev trigger: launch the rhythm test
            return;
        }
        if (state == Mode.TETRIS) {
            /*
             * In the game every key belongs to it; the render thread drains and interprets
             * them. We must
             * not touch game state here (this is the main thread), only enqueue.
             */
            tetrisInput.offer(key);
            return;
        }
        if (state == Mode.SNAKE) {
            snakeInput.offer(key); // the render thread drains + interprets
            return;
        }
        if (state != Mode.SETUP) {
            if (key == GLFW_KEY_DELETE && !biosUsed) { // BIOS is one-way: no re-entry once you've left it
                setupSel = 0;
                setupNotice = "";
                confirmFor = -1;
                passwordFor = -1;
                passwordInput = "";
                state = Mode.SETUP; // POST -> SETUP: a plain volatile store (both run at 50fps, no period change)
            }
            return;
        }
        if (confirmFor >= 0) {
            /*
             * A confirmation prompt is up: Enter confirms the action, Esc cancels (without
             * leaving SETUP).
             */
            switch (key) {
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                    int target = confirmFor;
                    confirmFor = -1;
                    confirmAction(target);
                }
                case GLFW_KEY_ESCAPE -> {
                    confirmFor = -1;
                    setupNotice = "Cancelled.";
                }
                default -> {
                }
            }
            return;
        }
        if (passwordFor >= 0) {
            /*
             * The secret-entry box is up: typed characters arrive via onChar (the phrase buffer); here we handle the
             * controls + feed every other key PRESS to the live key-sequence matcher (Konami-style secrets).
             */
            switch (key) {
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> submitSecret();
                case GLFW_KEY_ESCAPE -> {
                    passwordFor = -1;
                    passwordInput = "";
                    secrets.resetKeys();
                    setupNotice = "Cancelled.";
                }
                case GLFW_KEY_BACKSPACE -> {
                    if (!passwordInput.isEmpty()) {
                        passwordInput = passwordInput.substring(0, passwordInput.length() - 1);
                    }
                }
                default -> {
                    // a key-sequence secret matched -> it fired (set its own notice); close the box
                    if (action == GLFW_PRESS && secrets.feedKey(key, secretCtx)) {
                        passwordFor = -1;
                        passwordInput = "";
                    }
                }
            }
            return;
        }
        switch (key) {
            case GLFW_KEY_UP -> {
                if (difficultyMenu) {
                    SongDef js = jukeboxSong(songSel);
                    int n = js != null ? availableDifficulties(js).length : 0;
                    if (n > 0)
                        difficultySel = (difficultySel - 1 + n) % n;
                } else if (songMenuOpen) {
                    int sn = jukebox.size();
                    if (sn > 0)
                        songSel = (songSel - 1 + sn) % sn; // guard % 0 if discovery ever yields empty
                } else {
                    setupSel = (setupSel - 1 + SETUP_OPTIONS.length) % SETUP_OPTIONS.length;
                }
                recoveryRequiredError = false;
                setupNotice = "";
            }
            case GLFW_KEY_DOWN -> {
                if (difficultyMenu) {
                    SongDef js = jukeboxSong(songSel);
                    int n = js != null ? availableDifficulties(js).length : 0;
                    if (n > 0)
                        difficultySel = (difficultySel + 1) % n;
                } else if (songMenuOpen) {
                    int sn = jukebox.size();
                    if (sn > 0)
                        songSel = (songSel + 1) % sn;
                } else {
                    setupSel = (setupSel + 1) % SETUP_OPTIONS.length;
                }
                recoveryRequiredError = false;
                setupNotice = "";
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (difficultyMenu) {
                    confirmDifficulty(); // play the chosen difficulty (Special routes to its session)
                } else if (songMenuOpen) {
                    openDifficultyMenu(); // song picked -> choose difficulty
                } else {
                    activateSetup(setupSel);
                }
            }
            case GLFW_KEY_ESCAPE -> {
                if (difficultyMenu) {
                    difficultyMenu = false; // back to the song list
                } else if (songMenuOpen) {
                    songMenuOpen = false; // back out of the jukebox to the option list (not out of SETUP)
                } else {
                    recoveryRequiredError = false;
                    biosUsed = true; // one-way: POST now refuses DEL re-entry and drops the prompt
                    state = Mode.POST; // SETUP -> POST (the loading terminal)
                }
            }
            default -> {
            }
        }
    }

    /*
     * Text input for the password dialog (main thread, during glfwPollEvents). Only
     * collects printable
     * ASCII while a password prompt is up; Enter / Backspace / Esc are handled in
     * onKey.
     */
    private void onChar(long w, int codepoint) {
        if (handedOff || passwordFor < 0) {
            return;
        }
        if (codepoint >= 32 && codepoint < 127 && passwordInput.length() < 32) {
            passwordInput = passwordInput + (char) codepoint;
        }
    }

    /*
     * Act on the chosen row: the functional options recolor the boot screen or pop
     * a joke, the rest are
     * cosmetic Enabled/Disabled toggles, and "Exit Bios" leaves SETUP (releasing
     * the held hand-off).
     */
    private void activateSetup(int i) {
        String opt = SETUP_OPTIONS[i];
        if (opt.equals("Roll it back to BAMFOS v1")) {
            /*
             * Info-only: the "first version, silly" line is its help text; selecting it
             * does nothing for
             * now (a real roll-back confirmation can hook in here later).
             */
        } else if (opt.equals("Reset Cache")) {
            confirmFor = i; // pop the are-you-sure prompt; the real reset runs in confirmAction
        } else if (opt.equals("Safe For Retro Mode")) {
            passwordInput = "";
            passwordFor = i; // gate this dev mode behind the secret-entry dialog
            secrets.resetKeys(); // fresh key-sequence buffer each time the box opens
        } else if (opt.equals("Enable Mechanics")) {
            tetrisStartRequested = true; // render thread builds the game + decodes the song
        } else if (opt.equals("Select Song")) {
            /*
             * The jukebox: open it only once you've recovered (beat Recovery) or you're in
             * Safe For Retro;
             * otherwise the panel shows the terse "recovery required" error rather than the
             * list.
             */
            if (gameBeaten || safeForRetro) {
                rebuildJukebox(); // merge bundled + on-disk user songs so drop-ins/forks/converts appear live
                songMenuOpen = true;
                difficultyMenu = false;
                songSel = 0;
            } else {
                recoveryRequiredError = true;
            }
        } else if (opt.equals("Enter Recovery Mode")) {
            enterRecoveryMode();
        } else if (opt.equals("You're Blue Now")) {
            blueTheme = !blueTheme;
            optionOn[i] = blueTheme;
            setupNotice = blueTheme ? "You're blue now. (saved)" : "Back to black. (saved)";
            saveBiosConfig(); // persist so the next boot keeps this scheme
        } else if (opt.equals("Unlock 'Bios Screen' Achievement")) {
            unlockAchievement();
        } else if (opt.equals("Exit Bios")) {
            biosUsed = true; // one-way: POST now refuses DEL re-entry and drops the prompt
            state = Mode.POST; // leave SETUP for the loading terminal; releases the held hand-off
        } else if (opt.equals("Open Chart Editor")) {
            editorStartRequested = true; // render thread builds + enters the editor screen
        } else if (opt.equals("Open Stage Editor") || opt.equals("Party Fun Time")) {
            setupNotice = "Coming soon!"; // not-yet-built placeholders: no enable/disable toggle, just a notice
        } else {
            optionOn[i] = !optionOn[i];
            setupNotice = optionOn[i] ? "Enabled." : "Disabled.";
        }
    }

    /*
     * "Enter Recovery Mode" -> the intro cutscene, then the Recovery rhythm song.
     * The render thread runs
     * the cutscene (timing + the jingle) via the request flag.
     */
    private void enterRecoveryMode() {
        if (SongRegistry.SONGS.length == 0) {
            setupNotice = "ERROR: no songs loaded."; // discovery should never leave this empty (built-in fallback), but
                                                     // never crash
            return;
        }
        currentSong = SongRegistry.SONGS[0]; // the cutscene leads into the Recovery song (banks under "recovery")
        currentDifficulty = currentSong.difficulty("normal"); // Recovery plays at Normal (data-driven; null-safe in RhythmScreen)
        cutsceneStartRequested = true;
    }

    /*
     * Song-list ENTER: open the difficulty sub-menu for the highlighted song,
     * defaulting the cursor to Normal.
     */
    private void openDifficultyMenu() {
        SongDef song = jukeboxSong(songSel);
        if (song == null) {
            return;
        }
        SongDifficulty[] diffs = availableDifficulties(song);
        if (diffs.length == 0) {
            setupNotice = "Uncharted song."; // nothing playable -> don't open an empty difficulty menu
            return;
        }
        difficultySel = 0;
        for (int i = 0; i < diffs.length; i++) {
            if ("normal".equalsIgnoreCase(diffs[i].token())) {
                difficultySel = i;
                break;
            }
        }
        difficultyMenu = true;
    }

    /*
     * Difficulty-menu ENTER: play the highlighted song at the chosen difficulty.
     * Special routes to the song's
     * SpecialSession; the others start the rhythm game at that difficulty. Render
     * thread starts it via the
     * request flag (currentSong/currentDifficulty set first so it sees them).
     */
    private void confirmDifficulty() {
        SongDef song = jukeboxSong(songSel);
        if (song == null) {
            return;
        }
        SongDifficulty[] diffs = availableDifficulties(song);
        if (difficultySel < 0 || difficultySel >= diffs.length) {
            return;
        }
        SongDifficulty d = diffs[difficultySel];
        currentSong = song;
        songMenuOpen = false;
        difficultyMenu = false;
        if (d.isSpecial()) {
            specialStartRequested = true;
        } else {
            currentDifficulty = d;
            rhythmStartRequested = true;
        }
    }

    /* ---- F2 volume menu ---- */

    /*
     * A "special event" is a scripted/transitional sequence that takes over the
     * screen and locks out ALL
     * menus (volume + pause): the Recovery intro cutscene, and the pre-song lead-in
     * (the "settle on the UI"
     * beat) while it holds. During one, F2 and Esc do nothing but flash a red ERROR
     * -- the sequence runs
     * uninterrupted. Kept as a single gate so future events opt in by being
     * reported here.
     */
    private boolean inSpecialEvent() {
        return state == Mode.CUTSCENE || leadIn != null;
    }

    /*
     * ---- F2 volume overlay: extracted to early.screen.overlay.VolumeOverlay
     *.
     * The provider keeps the `volume` value (persisted) + the volumeInput queue +
     * the volumeOverlay field;
     * renderFrame's drain/draw/gating + onKey + the hand-off loop read
     * volumeOverlay.active(). ----
     */

    /*
     * ---- in-game pause overlay + key remap: extracted to
     * early.screen.overlay.PauseOverlay (.75
     * STEP 5b). The provider keeps the pauseInput queue (onKey enqueues) + the
     * pauseOverlay field +
     * exitCurrentGame (routes Quit to the active screen's onExit) + keyName;
     * renderFrame drain/draw/gating +
     * onKey + the hand-off loop read pauseOverlay.active(); ctx.openPauseMenu()
     * delegates to
     * pauseOverlay.open(). F2/volume stays global here (the volume-in-pause UX
     * rework is deferred, ). ----
     */

    /* Quit from the pause menu leaves whichever game mode is active. */
    private void exitCurrentGame() {
        switch (state) {
            case TETRIS -> tetrisScreen.onExit(biosCtx);
            case SPECIAL -> specialScreen.onExit(biosCtx);
            case EDITOR -> editorScreen.onExit(biosCtx);
            case SNAKE -> snakeScreen.onExit(biosCtx);
            case GALAGA -> galagaScreen.onExit(biosCtx);
            case DOOM -> doomScreen.onExit(biosCtx);
            default -> exitRhythm();
        }
    }

    /*
     * (the pause-overlay drain / nav / remap / draw live in PauseOverlay -- see the
     * STEP 5b note above)
     */

    /*
     * A short display name for a GLFW key code (the remap UI): arrows + common
     * keys, else GLFW's printable
     * name, else the raw code.
     */
    private static String keyName(int key) {
        switch (key) {
            case GLFW_KEY_LEFT:
                return "LEFT";
            case GLFW_KEY_RIGHT:
                return "RIGHT";
            case GLFW_KEY_UP:
                return "UP";
            case GLFW_KEY_DOWN:
                return "DOWN";
            case GLFW_KEY_SPACE:
                return "SPACE";
            case GLFW_KEY_ENTER:
                return "ENTER";
            default:
                try {
                    String n = glfwGetKeyName(key, 0);
                    if (n != null && !n.isBlank()) {
                        return n.toUpperCase();
                    }
                } catch (Throwable ignored) {
                }
                return "KEY " + key;
        }
    }

    /*
     * A brief red ERROR in the bottom-left corner when an action was blocked (e.g.
     * F2 during a special
     * event). Self-contained: sets up its own logical projection and draws over
     * whatever is on screen.
     * A no-op unless the deadline set when something was blocked is still in the
     * future.
     */
    private void drawErrorFlash() {
        if (errorFlashUntilNanos == 0L || System.nanoTime() >= errorFlashUntilNanos) {
            return;
        }
        int logicalW = LOGICAL_W;
        int logicalH = Math.max(1, logicalW * fbHeight / Math.max(1, fbWidth));
        canvas.beginFrameShader(logicalW, logicalH);
        drawText(MARGIN, logicalH - lineSpacing, "ERROR", 0xFF0000FF); // ABGR red
    }

    /*
     * The single bottom control bar: the KEY-action hints for wherever you are,
     * drawn ONCE per frame
     * (centered) by renderFrame instead of every screen re-drawing its own legend.
     * Content comes from
     * controlHints(); an empty string draws nothing, so POST keeps its own serial
     * footer. Always rendered in
     * the LOGICAL_W space so the bar is a consistent global element regardless of
     * the active screen.
     */
    private void drawControlBar() {
        String hints = controlHints();
        if (hints.isEmpty()) {
            return;
        }
        int logicalW = LOGICAL_W;
        int logicalH = Math.max(1, logicalW * fbHeight / Math.max(1, fbWidth));
        int maxChars = Math.max(16, logicalW / charWidth - 1);
        canvas.beginFrameShader(logicalW, logicalH);
        String line = clip(hints, maxChars);
        int cols = Math.max(1, logicalW / charWidth);
        int x = MARGIN + Math.max(0, (cols - line.length()) / 2) * charWidth; // centered
        drawText(x, logicalH - lineSpacing, line, cDim());
    }

    /*
     * The KEY-action hints for the current state + sub-state -- the ONE place the
     * bottom bar's content is
     * decided, in the BIOS menu's "KEY  action" style. POST/CUTSCENE/RESULTS return
     * "" (no bar).
     */
    private String controlHints() {
        switch (state) {
            case SETUP:
                if (difficultyMenu) {
                    return "UP/DOWN  Difficulty      ENTER  Play      ESC  Back      F2  Volume";
                }
                if (songMenuOpen) {
                    return "UP/DOWN  Song      ENTER  Difficulty      ESC  Back      F2  Volume";
                }
                return "UP/DOWN  Select      ENTER  Choose      ESC  Exit Bios      F2  Volume";
            case RHYTHM:
                return rhythmScreen.controlHints(biosCtx);
            case TETRIS:
                return tetrisScreen.controlHints(biosCtx);
            case SPECIAL:
                return specialScreen.controlHints(biosCtx);
            case RESULTS:
                return resultsScreen.controlHints(biosCtx);
            case EDITOR:
                return editorScreen.controlHints(biosCtx);
            case SNAKE:
                return snakeScreen.controlHints(biosCtx);
            case GALAGA:
                return galagaScreen.controlHints(biosCtx);
            case DOOM:
                return doomScreen.controlHints(biosCtx);
            default:
                return "";
        }
    }

    /*
     * ---- Recovery Mode intro cutscene: extracted to
     * early.screen.modes.CutsceneScreen.
     * It owns the CutsceneTimeline + the jingle/stabs/face-draw. The provider keeps
     * the cutsceneStartRequested
     * drain + the shared faceA/faceB (ctx.recoveryFaceA/B) + ctx.renderSetupUnder
     * (the opening wipe draws
     * SETUP underneath); renderFrame's CUTSCENE arm + the drain delegate to
     * cutsceneScreen. ----
     */

    /*
     * Reschedule the frame task to a new period -- ~60fps (16ms) for smooth
     * note-scroll while playing,
     * 50ms (~20fps) otherwise. No-op if the tick never started (failed init).
     * Render thread only: every
     * caller routes through enterMode on the render (scheduler) thread, so onKey
     * never touches the executor
     * from the main thread (the load-bearing rule).
     */
    private void setRenderPeriod(long ms) {
        if (windowTick == null || renderScheduler == null) {
            return;
        }
        try {
            windowTick.cancel(false);
            windowTick = renderScheduler.scheduleAtFixedRate(this::renderFrame, 0, ms, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }

    /*
     * Switch the active base screen (render thread). The single volatile `state`
     * write is atomic, so the
     * hand-off hold loop can never observe "no screen up" mid-transition -- the
     * structural replacement for
     * the old "flip the new flag true before clearing the old" dance. The frame
     * period follows the target:
     * the scrolling games run at ~60fps (16ms), every menu/terminal screen at
     * ~20fps (50ms). enterMode
     * itself never touches audio; a caller that wants to swap the song calls
     * closeAudio() first (e.g.
     * startRhythm closes the cutscene jingle before playing the song).
     */
    private void enterMode(Mode m) {
        setRenderPeriod(m == Mode.DOOM ? 33 : (m == Mode.RHYTHM || m == Mode.CUTSCENE || m == Mode.SPECIAL || m == Mode.EDITOR || m == Mode.SNAKE || m == Mode.GALAGA ? 16 : 50));
        state = m; // written last, exactly once
    }

    /*
     * The single owner of the pre-Minecraft OpenAL teardown: a mode change is the
     * only thing that frees the
     * song, so individual transitions no longer hand-juggle the `audio` field.
     * Render thread (it owns the
     * context).
     */
    private void closeAudio() {
        if (audio != null) {
            audio.close();
            audio = null;
        }
    }

    /* ---- shared transitional lead-in (RHYTHM + SPECIAL) ---- */

    /*
     * Arm the lead-in: call right after pausing the freshly-played song, before
     * enterMode. The mode's screen
     * then shows for LEAD_IN_MS with the song frozen at ~0.
     */
    private void armLeadIn() {
        LeadIn li = new LeadIn(LEAD_IN_MS);
        li.start(System.nanoTime());
        leadIn = li; // publish fully-built (volatile write; onKey reads the reference via
                     // inSpecialEvent)
    }

    /*
     * True while a transitional lead-in is still holding: keeps the (paused-at-~0)
     * song frozen and tells the
     * caller to hold its step. At the deadline it resumes playback and returns
     * false; with no lead-in armed it
     * returns false immediately. The re-pause guards anything that resumed the
     * source mid-lead-in (e.g.
     * closing the F2 volume menu). The caller clears its own input queue so an
     * early key is not a fail.
     */
    private boolean holdingLeadIn(AudioPlayer a) {
        LeadIn li = leadIn;
        if (li == null) {
            return false;
        }
        if (!li.done(System.nanoTime())) {
            if (a != null && a.isPlaying()) {
                a.pause();
            }
            return true;
        }
        leadIn = null;
        if (a != null) {
            a.resume(); // begin playback now (the source was PAUSED when the lead-in was armed)
        }
        return false;
    }

    /*
     * ---- .75: the BiosContext seam the extracted screens call into (STEP 0
     * scaffold) ----
     * An inner adapter so the provider's own methods stay PRIVATE; screens hold
     * this `biosCtx`, never the
     * provider type, so they cannot reach the render-thread executor / renderLock /
     * GL handles. The
     * cross-cutting CORE stays provider-owned: transitions funnel through enterMode
     * (the single `state`
     * writer, write-last), the one AudioPlayer + closeAudio, the lead-in, the
     * overlays, persistence, and
     * the jukebox selection. Calls to the colliding interface names are qualified
     * with
     * TerminalWindowProvider.this so they hit the provider's method, not this
     * adapter's. Not yet consumed
     * (nothing dispatches through Screen until later steps).
     */
    private final BiosContext biosCtx = new BiosContext() {
        @Override
        public void enterMode(Mode mode) {
            TerminalWindowProvider.this.enterMode(mode);
        }

        @Override
        public Mode mode() {
            return state;
        }

        @Override
        public AudioPlayer audio() {
            return audio;
        }

        @Override
        public void closeAudio() {
            TerminalWindowProvider.this.closeAudio();
        }

        @Override
        public void armLeadIn() {
            TerminalWindowProvider.this.armLeadIn();
        }

        @Override
        public boolean holdingLeadIn() {
            return TerminalWindowProvider.this.holdingLeadIn(audio);
        }

        @Override
        public boolean inSpecialEvent() {
            return TerminalWindowProvider.this.inSpecialEvent();
        }

        @Override
        public boolean volumeMenuOpen() {
            return volumeOverlay.active();
        }

        @Override
        public boolean pauseMenuOpen() {
            return pauseOverlay.active();
        }

        @Override
        public void openPauseMenu() {
            pauseOverlay.open(biosCtx);
        }

        @Override
        public void clearGameInputQueues() {
            rhythmInput.clear();
            tetrisInput.clear();
            specialInput.clear();
        }

        @Override
        public void save() {
            saveBiosConfig();
        }

        @Override
        public SongDef currentSong() {
            return currentSong;
        }

        @Override
        public SongDifficulty currentDifficulty() {
            return currentDifficulty;
        }

        @Override
        public String loadChartText(String songId, String token) {
            return TerminalWindowProvider.this.loadChartText(songId, token);
        }

        @Override
        public boolean saveChart(String songId, String token, String text) {
            return TerminalWindowProvider.this.saveChart(songId, token, text);
        }

        @Override
        public double editorLogicalX(double winX) {
            return windowToLogicalX(winX);
        }

        @Override
        public double editorLogicalY(double winY) {
            return windowToLogicalY(winY);
        }

        @Override
        public double editorMouseWinX() {
            return mouseWinX;
        }

        @Override
        public double editorMouseWinY() {
            return mouseWinY;
        }

        @Override
        public void startRhythmTest(java.util.List<com.retro.bamfmenu.early.core.rhythm.RhythmEngine.Note> notes,
                int startMs, int approachMs) {
            testStartMs = Math.max(0, startMs);
            testApproach = Math.max(0, approachMs);
            java.util.List<com.retro.bamfmenu.early.core.rhythm.RhythmEngine.Note> copy = new java.util.ArrayList<>();
            if (notes != null) {
                for (com.retro.bamfmenu.early.core.rhythm.RhythmEngine.Note n : notes) {
                    if (n.timeMs >= testStartMs) {
                        copy.add(
                                new com.retro.bamfmenu.early.core.rhythm.RhythmEngine.Note(n.lane, n.timeMs, n.holdMs));
                    }
                }
            }
            testChart = copy;
            editorTestReturn = true;
            rhythmStartRequested = true; // render thread launches RhythmScreen next frame; it picks up testChart
        }

        @Override
        public java.util.List<com.retro.bamfmenu.early.core.rhythm.RhythmEngine.Note> consumeTestChart() {
            java.util.List<com.retro.bamfmenu.early.core.rhythm.RhythmEngine.Note> c = testChart;
            testChart = null;
            return c;
        }

        @Override
        public int testStartMs() {
            return testStartMs;
        }

        @Override
        public int testApproachMs() {
            return testApproach;
        }

        @Override
        public boolean editorTestActive() {
            return editorTestReturn;
        }

        @Override
        public void returnToEditor() {
            editorTestReturn = false;
            testChart = null;
            editorScreen.resumeAfterTest(biosCtx);
        }
        @Override public java.util.List<SongDef> userSongs() { return TerminalWindowProvider.this.userSongs(); }
        @Override public SongDef forkSong(SongDef bundled) { return TerminalWindowProvider.this.forkSong(bundled); }
        @Override public SongDef normalizeUserSong(SongDef s) { return TerminalWindowProvider.this.normalizeUserSong(s); }
        @Override public String createNewSong() { return TerminalWindowProvider.this.createNewSong(); }

        @Override
        public AudioPlayer startSongAudio(SongDef song, DetectorConfig det) {
            TerminalWindowProvider.this.closeAudio();
            AudioPlayer p = new AudioPlayer();
            boolean playing = p.play(song.ogg(), det);
            audio = p; // keep it either way so closeAudio frees it
            if (!playing) {
                return null;
            }
            p.pause(); // freeze at ~0 for the lead-in
            p.setVolume(volume / 100f);
            return p;
        }

        @Override
        public void setCurrentSong(SongDef song) {
            currentSong = song;
        }

        @Override
        public boolean safeForRetro() {
            return safeForRetro;
        }

        @Override
        public boolean bankRhythm(SongDef song, SongDifficulty diff, int score, int perfectPct, boolean completed) {
            return TerminalWindowProvider.this.bankRhythm(song, diff, score, perfectPct, completed);
        }

        @Override
        public void showResults(boolean completed, int score, int grade, boolean newBest) {
            resultsScreen.show(completed, score, grade, newBest);
            TerminalWindowProvider.this.closeAudio(); // the song is over -> no music on the results screen
            TerminalWindowProvider.this.enterMode(Mode.RESULTS);
        }

        @Override
        public String gradeLetter(int perfectPct) {
            return TerminalWindowProvider.gradeLetter(perfectPct);
        }

        @Override
        public void startRhythm() {
            TerminalWindowProvider.this.startRhythm();
        }

        @Override
        public void exitRhythm() {
            TerminalWindowProvider.this.exitRhythm();
        }

        @Override
        public void renderSetupUnder() {
            drawSetup();
        } // the cutscene's opening wipe draws SETUP underneath

        @Override
        public String[] recoveryFaceA() {
            return faceA;
        }

        @Override
        public String[] recoveryFaceB() {
            return faceB;
        }

        @Override
        public void setNotice(String notice) {
            setupNotice = notice;
        }

        @Override
        public void clearLeadIn() {
            leadIn = null;
        }

        @Override
        public void playMusic(String resource) {
            TerminalWindowProvider.this.closeAudio();
            AudioPlayer p = new AudioPlayer();
            p.play(resource, DetectorConfig.defaults()); // not charted; config only feeds the decode-time analysis
            p.setVolume(volume / 100f);
            audio = p;
        }

        @Override
        public int tetrisHighScore() {
            return tetrisHighScore;
        }

        @Override
        public void setTetrisHighScore(int score) {
            tetrisHighScore = score;
        }

        @Override
        public void exitToSetup(int row, String notice) {
            if (row >= 0) {
                setupSel = row;
            }
            if (notice != null) {
                setupNotice = notice;
            }
            confirmFor = -1;
            passwordFor = -1;
            TerminalWindowProvider.this.enterMode(Mode.SETUP);
        }

        @Override
        public int volume() {
            return volume;
        }

        @Override
        public void setVolume(int v) {
            volume = Math.max(0, Math.min(100, v));
            if (audio != null) {
                audio.setVolume(volume / 100f);
            }
        }

        @Override
        public void flashError() {
            errorFlashUntilNanos = System.nanoTime() + 1_000_000_000L;
        }

        @Override
        public void exitCurrentGame() {
            TerminalWindowProvider.this.exitCurrentGame();
        }

        @Override
        public int[] laneKeys() {
            return laneKeys;
        }

        @Override
        public String keyName(int key) {
            return TerminalWindowProvider.keyName(key);
        }

        @Override
        public int frame() {
            return frame;
        }
    };

    /* ---- difficulty + grade helpers ---- */

    /* */

    /*
     * The difficulties offered for a song: only those with a committed
     * <difficulty>.chart (-- "the
     * jukebox offers only the difficulties whose charts exist"; today that is
     * Normal, with Easy/Hard appearing
     * once authored in the editor), plus Special when the song defines one (it
     * launches a session, not a
     * chart). Empty => the song is uncharted and unplayable.
     */
    private static SongDifficulty[] availableDifficulties(SongDef song) {
        if (song == null || song.difficulties() == null) {
            return new SongDifficulty[0];
        }
        return song.difficulties().toArray(new SongDifficulty[0]); // DATA-DRIVEN: the song's declared/derived set
    }

    /* The save-map key for a (song, difficulty-token) score/grade. */
    private static String scoreKey(String id, String token) {
        return id + "." + token;
    }

    /* Perfect-% -> letter grade. 100% perfect = S. Tunable ladder. */
    private static String gradeLetter(int perfectPct) {
        if (perfectPct >= 100)
            return "S";
        if (perfectPct >= 90)
            return "A";
        if (perfectPct >= 80)
            return "B";
        if (perfectPct >= 70)
            return "C";
        if (perfectPct >= 60)
            return "D";
        return "F";
    }

    /*
     * The cycling high-score cell: alternates the numeric score and its grade
     * letter on a ~1.5s timer, so one
     * slot shows both over time. "--" when the (song, difficulty) was never
     * finished.
     */
    private String scoreGradeCell(String id, String token) {
        String key = scoreKey(id, token);
        int score = rhythmHigh.getOrDefault(key, 0);
        if (score <= 0) {
            return "--";
        }
        boolean showGrade = (System.nanoTime() / 1_500_000_000L) % 2L == 1L;
        if (!showGrade) {
            return Integer.toString(score);
        }
        /*
         * A legacy-migrated score has no recorded perfect-% -> show "?" rather than a
         * pessimistic "F"; it
         * self-corrects to a real grade on the next completion at this difficulty.
         */
        return rhythmPerf.containsKey(key) ? gradeLetter(rhythmPerf.get(key)) : "?";
    }

    /*
     * Start the rhythm game (RhythmScreen.onEnter): clear the jukebox sub-state
     * first (so a later return to
     * SETUP lands on the option list, the old startRhythm behavior), then hand off
     * to the extracted host. The
     * single funnel for all rhythm starts -- the jukebox-launch drain, the cutscene
     * hand-off, and RESULTS
     * retry all route through here.
     */
    private void startRhythm() {
        songMenuOpen = false;
        rhythmScreen.onEnter(biosCtx);
    }

    /*
     * Bank a finished rhythm run (the old bankRun + new-best logic, now invoked by
     * RhythmScreen via ctx). Safe
     * For Retro logs NOTHING. Otherwise the HIGH SCORE is banked on ANY run
     * (completed OR game-over); the GRADE
     * (perfect-%) only on a full completion, paired to the record run (a failed
     * record clears the grade ->
     * "?"); gameBeaten (Recovery unlock) is set only on a completion at any
     * difficulty. Returns whether this
     * run set a new high score (drives the RESULTS "- NEW BEST -" line).
     */
    private boolean bankRhythm(SongDef song, SongDifficulty diff, int score, int perfectPct, boolean completed) {
        boolean scored = !safeForRetro && song != null && diff != null && !diff.isSpecial();
        if (!scored) {
            return false; // Safe For Retro, no song/diff, or a SPECIAL session -> nothing banked
        }
        String key = scoreKey(song.id(), diff.token());
        int prevHigh = rhythmHigh.getOrDefault(key, 0);
        boolean changed = false;
        if (score > prevHigh) {
            rhythmHigh.put(key, score);
            if (completed) {
                rhythmPerf.put(key, perfectPct); // the grade of this record run
            } else {
                rhythmPerf.remove(key); // a failed record has no grade -> "?"
            }
            changed = true;
        }
        if (completed && song.id().equals("recovery") && !gameBeaten) {
            gameBeaten = true; // beaten (cleared) on ANY difficulty unlocks the jukebox + achievement
            changed = true;
        }
        if (changed) {
            saveBiosConfig();
        }
        return score > prevHigh && score > 0;
    }

    /*
     * (finishRhythm / enterResults / stepResults / drawResults / drawCentered /
     * bankRun moved into
     * RhythmScreen + ResultsScreen with the game, .75 STEP 6. The run is
     * banked via bankRhythm above
     * and shown via ctx.showResults() -> ResultsScreen.)
     */

    /*
     * Leave the rhythm game back to SETUP (RhythmScreen.onExit frees audio + engine
     * + panel, clears the
     * lead-in, and enterMode(SETUP) does the single atomic state write + restores
     * the 50ms period).
     */
    private void exitRhythm() {
        rhythmScreen.onExit(biosCtx);
    }

    /*
     * ---- per-song SPECIAL mode: extracted to early.screen.modes.SpecialScreen
     *.
     * The provider keeps only the specialInput queue (onKey enqueues) + the
     * specialScreen field; the
     * renderFrame SPECIAL arm + the specialStartRequested drain + exitCurrentGame
     * delegate to it. ----
     */

    /*
     * stepRhythm / animatedScore / drawRhythm / drawLeftPanel / boxRule /
     * judgmentColor / drawWaveform /
     * sampleWaveform moved into RhythmScreen with the game.
     */

    /*
     * The 'Bios Screen' achievement only unlocks once the Recovery Mode game has
     * been beaten (gameBeaten).
     * Until that game exists, gameBeaten is always false, so this stays locked.
     */
    private void unlockAchievement() {
        if (biosAchievement) {
            return; // already unlocked; the panel shows the face + UNLOCKED
        }
        if (gameBeaten) {
            biosAchievement = true;
            saveBiosConfig(); // the panel now renders the cutscene smile + UNLOCKED
        } else {
            recoveryRequiredError = true; // terse "recovery required" error, same as the locked jukebox
        }
    }

    /*
     * ENTER in the Safe-For-Retro secret box: run the first data-driven PHRASE secret matching what was typed
     * (case-insensitive). The classic "password" -> Safe For Retro is now just one such secret (see
     * core.secret.defs.SafeForRetroSecret); a non-match shows "Access denied." KEY-SEQUENCE secrets fire live in
     * onKey via secrets.feedKey instead. The action sets its own notice through secretCtx.
     */
    private void submitSecret() {
        int i = passwordFor;
        String typed = passwordInput;
        passwordFor = -1;
        passwordInput = "";
        secrets.resetKeys();
        if (i < 0) {
            return;
        }
        if (!secrets.submit(typed, secretCtx)) {
            setupNotice = "Access denied.";
        }
    }

    /* Find a song (built-in or on-disk user song) by id, or null -- the seam a secret's launchSong acts through. */
    private SongDef findSongById(String id) {
        if (id == null) {
            return null;
        }
        for (SongDef s : SongRegistry.SONGS) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        for (SongDef s : userSongs()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /*
     * One jukebox row: the song name, a dot leader, and the high score (or "--" if
     * never finished), fit to
     * width.
     */
    private String songRow(SongDef s, int width) {
        /*
         * The song-list cell shows the song's BEST score across its scored difficulties
         * (cycling score/grade);
         * the per-difficulty breakdown is in the difficulty sub-menu.
         */
        String bestToken = null;
        int bestScore = 0;
        if (s.difficulties() != null) {
            for (SongDifficulty d : s.difficulties()) {
                if (d.isSpecial()) {
                    continue;
                }
                int sc = rhythmHigh.getOrDefault(scoreKey(s.id(), d.token()), 0);
                if (sc > bestScore) {
                    bestScore = sc;
                    bestToken = d.token();
                }
            }
        }
        String cell = bestToken != null ? scoreGradeCell(s.id(), bestToken) : "--";
        String name = s.name();
        int dots = width - name.length() - cell.length() - 2;
        if (dots < 1) {
            return clip(name + " " + cell, width);
        }
        return name + " " + ".".repeat(dots) + " " + cell;
    }

    /*
     * One difficulty-submenu row body (no cursor prefix): the difficulty name, a dot leader, and its cycling
     * score/grade cell ("play" for a Special session). Shared by the fits-in-one-screen + scrolling-window paths.
     */
    private String diffRow(SongDef sd, SongDifficulty d, int width) {
        String cell = !d.isSpecial() ? scoreGradeCell(sd.id(), d.token()) : "play";
        int dots = width - d.name().length() - cell.length() - 2;
        return dots >= 1 ? d.name() + " " + ".".repeat(dots) + " " + cell : d.name() + " " + cell;
    }

    /*
     * The state line shown in the help panel for the highlighted option (instead of
     * a value on every
     * menu row): enabled/disabled for the toggles, or the action for the special
     * rows.
     */
    private String optionState(int i) {
        String opt = SETUP_OPTIONS[i];
        if (opt.equals("Roll it back to BAMFOS v1")) {
            return ""; // info-only row: no state line
        }
        if (opt.equals("Exit Bios")) {
            return "Press ENTER to exit.";
        }
        if (opt.equals("Enter Recovery Mode")) {
            return "Press ENTER to enter.";
        }
        if (opt.equals("Unlock 'Bios Screen' Achievement")) {
            return biosAchievement ? "[ Unlocked ]" : "[ Locked ]";
        }
        if (opt.equals("Enable Mechanics")) {
            return "High Score: " + tetrisHighScore;
        }
        if (opt.equals("Open Chart Editor")) {
            return "Press ENTER to open.";
        }
        if (opt.equals("Open Stage Editor") || opt.equals("Party Fun Time")) {
            return ""; // coming-soon placeholders: no Enabled/Disabled state line
        }
        boolean on;
        if (opt.equals("You're Blue Now")) {
            on = blueTheme;
        } else if (opt.equals("Safe For Retro Mode")) {
            on = safeForRetro;
        } else {
            on = optionOn[i];
        }
        return on ? "[ Enabled ]" : "[ Disabled ]";
    }

    /*
     * The question shown in the confirmation popup for an option that requires it.
     */
    private String confirmPrompt(int i) {
        if (SETUP_OPTIONS[i].equals("Reset Cache")) {
            return "Reset the BAMFOS cache?";
        }
        return "Are you sure?";
    }

    /*
     * Run an option's action once its confirmation was accepted. Real effects (e.g.
     * an actual cache
     * reset) can hook in here later; for now it just reports back.
     */
    private void confirmAction(int i) {
        if (SETUP_OPTIONS[i].equals("Reset Cache")) {
            /*
             * Actually reset the cache: drop the stored blob AND return every persisted
             * field to its factory
             * default, so the reset is total (the jukebox re-locks, the achievement
             * re-locks, scores clear,
             * theme/dev-mode/volume/keybinds revert) -- not a partial wipe that leaves you
             * "still recovered".
             */
            BiosCache.delete(gameDir);
            gameBeaten = false; // re-lock Select Song + the achievement gate (the one that used to survive)
            biosAchievement = false;
            tetrisHighScore = 0;
            rhythmHigh.clear();
            rhythmPerf.clear();
            blueTheme = false; // back to the black DOS theme
            safeForRetro = false; // dev mode off
            volume = 100;
            laneKeys[0] = GLFW_KEY_LEFT;
            laneKeys[1] = GLFW_KEY_DOWN;
            laneKeys[2] = GLFW_KEY_UP;
            laneKeys[3] = GLFW_KEY_RIGHT;
            java.util.Arrays.fill(optionOn, false);
            setupNotice = "Cache reset.";
        }
    }

    /*
     * A centered modal confirmation popup drawn over the SETUP screen. We
     * GL-scissor-clear its rectangle
     * first (the early text renderer cannot fill a background, so without this the
     * menu behind would
     * bleed through the box), then draw an ASCII-bordered box with the question +
     * Yes/No keys. The
     * scissor rect uses the SAME logical->framebuffer mapping the text shader does,
     * so the cleared area
     * lines up with the drawn box.
     */
    /*
     * Draws a centered modal box (scissor-cleared first, since the early text
     * renderer can't fill a
     * background) with the given content lines; colors[i] colours lines[i], an
     * empty string is a blank
     * row. The scissor rect uses the same logical->framebuffer mapping the text
     * shader does, so the
     * cleared area lines up with the drawn box.
     */
    private void drawPopup(int logicalW, int logicalH, String[] lines, int[] colors) {
        canvas.popup(logicalW, logicalH, lines, colors); // STEP 4: popup moved to TerminalCanvas
    }

    private void drawConfirmPopup(int logicalW, int logicalH) {
        drawPopup(logicalW, logicalH,
                new String[] { "", confirmPrompt(confirmFor), "[ENTER] Yes      [ESC] No" },
                new int[] { cBright(), cBright(), cGold() });
    }

    private void drawPasswordPopup(int logicalW, int logicalH) {
        String field = "[ " + "*".repeat(passwordInput.length()) + "_ ]";
        drawPopup(logicalW, logicalH,
                new String[] { "Enter Password", field, "", "[ENTER] OK      [ESC] Cancel" },
                new int[] { cBright(), cGold(), cBright(), cFg() });
    }

    private static String center(String s, int width) {
        return TerminalCanvas.center(s, width);
    }

    /*
     * The BAMFOS BIOS SETUP screen: shared header, a welcome line, then a
     * two-column body (option list
     * on the left -- selected row marked + brightened with its toggle state after
     * the colon -- and the
     * highlighted option's help on the right), with a key legend along the bottom.
     * Colours follow the
     * active theme so "You're Blue Now" turns it AMI-blue. Sized for ~16:9; very
     * short windows may clip
     * the lower rows.
     */
    private void drawSetup() {
        /*
         * Locked logical canvas STRETCHED to the window (same as every screen). The
         * header + 12-option box +
         * bottom legend is the densest screen; at a wide/short window (maximized) the
         * shorter logical height
         * can crowd the box into the legend, so when the box would not clear the legend
         * at the unified width
         * we widen the canvas a little (-> a slightly smaller SETUP font) just enough
         * to restore a one-row
         * gap. A window that already fits keeps the unified width unchanged, so the
         * layout stays locked.
         */
        int logicalW = LOGICAL_W;
        if (fbWidth > 0 && fbHeight > 0) {
            int optRows = SETUP_OPTIONS.length;
            int defH = LOGICAL_W * fbHeight / fbWidth;
            int boxBottom = headerBottomBodyY(LOGICAL_W) + (optRows + 3) * lineSpacing;
            if (boxBottom > defH - 2 * lineSpacing) { // the box would overlap / crowd the legend
                double a = (double) fbHeight / fbWidth;
                double c = (double) headerBottomBodyY(LOGICAL_W) / LOGICAL_W; // header fraction of body width
                if (a > c) {
                    double wNeed = (optRows + 5) * (double) lineSpacing / (a - c);
                    logicalW = (int) Math.max(LOGICAL_W, Math.ceil(wNeed));
                }
            }
        }
        int logicalH = Math.max(1, logicalW * fbHeight / Math.max(1, fbWidth));
        int maxChars = Math.max(40, logicalW / charWidth - 1);

        canvas.beginFrameShader(logicalW, logicalH);

        int border = cFg();
        int y = drawHeader(logicalW, logicalH);
        drawText(MARGIN, y, rule(maxChars), border);
        y += lineSpacing;
        drawText(MARGIN, y, clip(SETUP_TITLE, maxChars), cBright());

        /*
         * A bordered two-column box: just the option NAMES on the left (selected row
         * marked +
         * brightened), and on the right the highlighted option's help, a divider, then
         * its current
         * state / action. Showing the state only for the hovered option -- not a value
         * on every row --
         * keeps the list clean and never truncates the longer names. Borders use
         * box-drawing glyphs.
         */
        int leftInner = 36; // fits "> <longest option name>"
        int rightInner = Math.max(20, maxChars - leftInner - 3);
        int rows = SETUP_OPTIONS.length;

        int boxTop = y + lineSpacing;
        int leftX = MARGIN;
        int leftCellX = MARGIN + charWidth;
        int dividerX = MARGIN + (leftInner + 1) * charWidth;
        int rightCellX = dividerX + charWidth;
        int rightX = MARGIN + (leftInner + rightInner + 2) * charWidth;
        /*
         * The interior runs the full height of the box (down to ~1 line above the
         * legend). The jukebox song
         * list uses ALL of it, so a long list fills the whole right column instead of
         * only the top `rows`
         * lines (the dead space under the list was wasted). The left option column
         * still has only
         * SETUP_OPTIONS.length entries; interior rows below them are blank on the left
         * side.
         */
        int legendY = logicalH - lineSpacing;
        int capacity = Math.max(rows, (legendY - lineSpacing - boxTop) / lineSpacing - 1);
        String hbarTop = "┌" + "─".repeat(leftInner) + "┬" + "─".repeat(rightInner) + "┐";
        String hbarBot = "└" + "─".repeat(leftInner) + "┴" + "─".repeat(rightInner) + "┘";

        drawText(leftX, boxTop, hbarTop, border);

        /*
         * The right column's content depends on the highlighted option + jukebox state,
         * built as one
         * line+colour per box row. NORMAL keeps the help / divider / state / notice.
         * The jukebox shows the
         * song list; a locked Select Song / achievement shows the terse
         * "recovery required" error; "Select
         * Song" on hover shows NOTHING (blank until ENTER opens it); the achievement
         * shows the cutscene smile
         * + UNLOCKED once earned.
         */
        String selOpt = SETUP_OPTIONS[setupSel];
        boolean isSelectSong = selOpt.equals("Select Song");
        boolean isAchievement = selOpt.equals("Unlock 'Bios Screen' Achievement");
        String[] rl = new String[capacity];
        int[] rc = new int[capacity];
        java.util.Arrays.fill(rl, "");
        java.util.Arrays.fill(rc, cFg());
        int dividerRow = -1; // only NORMAL draws the ├─┤ split

        if (songMenuOpen && difficultyMenu) {
            /*
             * Difficulty sub-menu for the highlighted song: the song name, then a row per
             * difficulty with its
             * own cycling score/grade at the far right (Special shows "play").
             */
            SongDef sd = jukeboxSong(songSel);
            if (sd != null) {
                SongDifficulty[] diffs = availableDifficulties(sd);
                rl[0] = clip("Select difficulty:", rightInner);
                rc[0] = cGold();
                rl[1] = clip(sd.name(), rightInner);
                rc[1] = cFg();
                /*
                 * Difficulties start at row 3. An OSU-class import (osu set with >3 diffs) can list many custom
                 * difficulties, so when they don't all fit, draw a SCROLLING WINDOW centered on the selection --
                 * the same ▲/▼ "more" treatment the song list uses -- so none are undrawable + an off-screen
                 * selection stays visible. difficultySel nav is unchanged (it still wraps the full set).
                 */
                int diffTop = 3;
                int slots = Math.max(0, capacity - diffTop); // diff rows from row 3 down to the panel bottom (capacity)
                if (diffs.length <= slots) {
                    for (int i = 0; i < diffs.length; i++) {
                        boolean selrow = i == difficultySel;
                        rl[diffTop + i] = clip((selrow ? "> " : "  ") + diffRow(sd, diffs[i], rightInner - 2), rightInner);
                        rc[diffTop + i] = selrow ? cBright() : cFg();
                    }
                } else {
                    int view = slots - 2; // diff rows between the two reserved marker rows
                    int first = Math.max(0, Math.min(difficultySel - view / 2, diffs.length - view));
                    rl[diffTop] = first > 0 ? clip("  ▲ more", rightInner) : "";
                    rc[diffTop] = cDim();
                    rl[capacity - 1] = first + view < diffs.length ? clip("  ▼ more", rightInner) : "";
                    rc[capacity - 1] = cDim();
                    for (int k = 0; k < view; k++) {
                        int i = first + k;
                        boolean selrow = i == difficultySel;
                        rl[diffTop + 1 + k] = clip((selrow ? "> " : "  ") + diffRow(sd, diffs[i], rightInner - 2), rightInner);
                        rc[diffTop + 1 + k] = selrow ? cBright() : cFg();
                    }
                }
            }
        } else if (songMenuOpen) {
            /*
             * The jukebox song list. When it fits, draw it all; when there are MORE songs
             * than panel rows,
             * draw a SCROLLING WINDOW so the list never overflows + cuts off (the original
             * bug -- songs past
             * the last row were undrawable and an off-screen selection was invisible). The
             * window is centered
             * on the selection, and row 0 / the last row are reserved for ▲/▼ "more"
             * markers (kept fixed so
             * the rows don't jump as you scroll past the ends). songSel nav is unchanged --
             * it still wraps the
             * full list; this is purely how the visible slice is chosen.
             */
            java.util.List<SongDef> songs = jukebox;
            int n = songs.size();
            if (n <= capacity) {
                for (int s = 0; s < n; s++) {
                    boolean selrow = s == songSel;
                    rl[s] = clip((selrow ? "> " : "  ") + songRow(songs.get(s), rightInner - 2), rightInner);
                    rc[s] = selrow ? cBright() : cFg();
                }
            } else {
                int view = capacity - 2; // song rows between the two reserved marker rows
                int first = Math.max(0, Math.min(songSel - view / 2, n - view));
                rl[0] = first > 0 ? clip("  ▲ more", rightInner) : "";
                rc[0] = cDim();
                rl[capacity - 1] = first + view < n ? clip("  ▼ more", rightInner) : "";
                rc[capacity - 1] = cDim();
                for (int k = 0; k < view; k++) {
                    int s = first + k;
                    boolean selrow = s == songSel;
                    rl[1 + k] = clip((selrow ? "> " : "  ") + songRow(songs.get(s), rightInner - 2), rightInner);
                    rc[1 + k] = selrow ? cBright() : cFg();
                }
            }
        } else if (recoveryRequiredError && (isSelectSong || isAchievement)) {
            /*
             * The locked-state panel, written to read like a genuine system fault + restore
             * prompt (not a
             * playful hint). The bodies are wrapped to the panel width so the longer lines
             * don't truncate.
             */
            rl[0] = clip("ERROR: RECOVERY REQUIRED", rightInner);
            rc[0] = Art.LANE_COLORS[3]; // header in red
            int k = 2;
            for (String line : wrap("Unable to load song image data.", rightInner)) {
                if (k < rows) {
                    rl[k] = clip(line, rightInner);
                    rc[k] = cFg();
                    k++;
                }
            }
            k++; // blank spacer between the fault line and the restore prompt
            for (String line : wrap("Use a restore point recorded on your PC to restore BAMFOS.", rightInner)) {
                if (k < rows) {
                    rl[k] = clip(line, rightInner);
                    rc[k] = cFg();
                    k++;
                }
            }
        } else if (isSelectSong) {
            /* nothing on hover -- the panel stays blank until ENTER opens the jukebox */
        } else if (isAchievement) {
            rl[0] = clip("Recovery achievement.", rightInner);
            if (biosAchievement) {
                int base = 2;
                for (int i = 0; i < faceB.length && base + i < rows; i++) {
                    rl[base + i] = clip(faceB[i], rightInner);
                    rc[base + i] = cBright();
                }
                int ur = base + faceB.length;
                if (ur < rows) {
                    rl[ur] = "UNLOCKED"; // terse, no fanfare
                }
            } else {
                rl[2] = "[ LOCKED ]";
                rc[2] = cDim();
            }
        } else {
            List<String> help = wrap(SETUP_HELP[setupSel], rightInner);
            dividerRow = help.size();
            int stateRow = dividerRow + 1;
            int noticeRow = dividerRow + 2;
            for (int k = 0; k < help.size() && k < rows; k++) {
                rl[k] = clip(help.get(k), rightInner);
            }
            if (stateRow < rows) {
                String state = optionState(setupSel);
                if (!state.isEmpty()) {
                    rl[stateRow] = clip(state, rightInner);
                    rc[stateRow] = cBright();
                }
            }
            if (noticeRow < rows && !setupNotice.isEmpty()) {
                rl[noticeRow] = clip(setupNotice, rightInner);
                rc[noticeRow] = cGold();
            }
        }

        /*
         * Draw the box frame down to ~1 line above the bottom legend (no dead space
         * underneath). The left
         * column holds only the SETUP_OPTIONS rows at the top; the right column's
         * content spans the full
         * interior (`capacity`) so the jukebox list can use all of it. Empty right rows
         * draw as blank.
         */
        int boxRows = capacity;
        for (int r = 0; r < boxRows; r++) {
            int ry = boxTop + (r + 1) * lineSpacing;
            drawText(leftX, ry, "│", border);
            if (r < rows) {
                boolean sel = r == setupSel;
                drawText(leftCellX, ry, clip((sel ? "> " : "  ") + SETUP_OPTIONS[r], leftInner),
                        sel ? cBright() : cFg());
            }
            if (r == dividerRow) {
                drawText(dividerX, ry, "├", border);
                drawText(rightCellX, ry, "─".repeat(rightInner), border);
                drawText(rightX, ry, "┤", border);
            } else {
                drawText(dividerX, ry, "│", border);
                drawText(rightX, ry, "│", border);
                if (r < rl.length && !rl[r].isEmpty()) {
                    drawText(rightCellX, ry, rl[r], rc[r]);
                }
            }
        }
        drawText(leftX, boxTop + (boxRows + 1) * lineSpacing, hbarBot, border);

        /*
         * the bottom control bar (menu / jukebox hints) is drawn globally by
         * renderFrame -> drawControlBar
         */

        if (confirmFor >= 0) {
            drawConfirmPopup(logicalW, logicalH);
        } else if (passwordFor >= 0) {
            drawPasswordPopup(logicalW, logicalH);
        }
    }

    /* ---- "Enable Mechanics" terminal Tetris ---- */

    /*
     * Launch the game from BIOS SETUP. Runs on the RENDER thread (via
     * tetrisStartRequested) so the song
     * decode happens there. Build the game + start the music first, then
     * enterMode(TETRIS) last, so the
     * render branch only runs once everything is constructed.
     */
    /*
     * ---- "Enable Mechanics" Tetris: extracted to early.screen.modes.TetrisScreen
     *.
     * The provider keeps the tetrisInput queue (onKey enqueues) + tetrisHighScore
     * (persisted) + the
     * tetrisScreen field; renderFrame's TETRIS arm + the tetrisStartRequested drain
     * + exitCurrentGame
     * delegate to it. ----
     */

    /*
     * Word-wrap text (honouring explicit '\n' breaks) to at most `width` columns,
     * for the help panel.
     */
    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (line.length() == 0) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= width) {
                    line.append(' ').append(word);
                } else {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /*
     * Theme-aware colours: the default DOS palette, or white-on-blue when
     * "You're Blue Now" is on.
     */
    /*
     * STEP 2: theme colours moved to TerminalCanvas; these forward so the
     * provider's not-yet-extracted
     * draws are unchanged (extracted screens read canvas.cFg() etc. directly).
     */
    private int cFg() {
        return canvas.cFg();
    }

    private int cBright() {
        return canvas.cBright();
    }

    private int cDim() {
        return canvas.cDim();
    }

    private int cGold() {
        return canvas.cGold();
    }

    private int blockWidth(String[] lines) {
        return canvas.blockWidth(lines); // STEP 1: moved to TerminalCanvas
    }

    /*
     * Load ASCII art from a bundled classpath resource, sanitized to the early
     * font's printable ASCII
     * range (any non-ASCII, e.g. smart quotes, becomes a space) and
     * trailing/leading blank lines
     * trimmed. Returns an empty array if the resource is missing so the splash
     * falls back to text.
     */
    /* loadArt + sanitizeAscii + isDrawable moved to Art (shared public home). */

    private String footerLine(int maxChars) {
        String serial = biosDate.replace("/", "") + "-BAMFOS-440BX-6A69VPADC";
        // The DEL prompt shows only on the FIRST POST; once BIOS has been used it's a
        // one-way door, so the
        // post-exit POST just shows the serial (no misleading "Press DEL" hint).
        String prompt = biosUsed ? "" : "Press DEL to enter SETUP      ";
        return clip(prompt + serial, maxChars);
    }

    /*
     * Accumulate the real FancyModLoader status messages into a rolling console
     * buffer (newest last),
     * deduping against the most recent so a repeated status does not spam the log.
     */
    /*
     * Accumulate the actual mod-setup progress into a rolling log: NeoForge updates
     * the active progress
     * meter's LABEL per step ("Constructing <mod>", "Setting up <mod>",
     * "EveryCompat: ..."), so we track
     * that label and append each new distinct one. This is the real setup activity,
     * not the generic
     * FancyModLoader message feed.
     */
    private void pumpLog(int maxChars) {
        try {
            String text = stepText(activeMeter(StartupNotificationManager.getCurrentProgress()));
            if (text.isEmpty() || text.equals(lastSeen)) {
                return;
            }
            lastSeen = text;
            log.addLast(clip("> " + text, maxChars));
            while (log.size() > MAX_LOG) {
                log.removeFirst();
            }
        } catch (Throwable ignored) {
        }
    }

    /*
     * The most specific active progress meter: prefer one whose label has been
     * updated to a real step
     * (label != bar name), else the last one. Order-agnostic, so it works however
     * NeoForge stacks bars.
     */
    private static ProgressMeter activeMeter(List<ProgressMeter> bars) {
        ProgressMeter chosen = null;
        for (ProgressMeter pm : bars) {
            chosen = pm;
        }
        for (ProgressMeter pm : bars) {
            String l = pm.label() != null ? pm.label().getText() : null;
            if (l != null && !l.isBlank() && !l.equals(pm.name())) {
                chosen = pm;
            }
        }
        return chosen;
    }

    private static String stepText(ProgressMeter pm) {
        if (pm == null) {
            return "";
        }
        String l = pm.label() != null ? pm.label().getText() : null;
        if (l != null && !l.isBlank()) {
            return l.trim();
        }
        return pm.name() != null ? pm.name().trim() : "";
    }

    private List<String> recentLog(int count) {
        int size = log.size();
        if (size <= count) {
            return List.copyOf(log);
        }
        return List.copyOf(log).subList(size - count, size);
    }

    private String statusLine(int maxChars) {
        char spin = SPINNER[(frame / 3) % SPINNER.length];
        String step = "loading";
        int pct = -1;
        try {
            ProgressMeter pm = activeMeter(StartupNotificationManager.getCurrentProgress());
            if (pm != null) {
                String s = stepText(pm);
                if (!s.isEmpty()) {
                    step = s;
                }
                if (pm.steps() > 0) {
                    pct = (int) (pm.progress() * 100.0F);
                }
            }
        } catch (Throwable ignored) {
        }
        String bar = asciiBar(pct, 18);
        String pctText = pct >= 0 ? " " + pct + "%" : "";
        return clip("[" + spin + "] " + step + "  " + bar + pctText, maxChars);
    }

    /*
     * A progress/level bar drawn with real block-element glyphs (Consolas): full
     * block for the filled part,
     * light shade for the empty part (a medium shade for the negative /
     * indeterminate case).
     */
    private static String asciiBar(int pct, int width) {
        return TerminalCanvas.asciiBar(pct, width); // STEP 5a: moved to TerminalCanvas
    }

    private static String clip(String s, int maxChars) {
        return TerminalCanvas.clip(s, maxChars);
    }

    /* ---- the SPI lifecycle Minecraft drives ---- */

    @Override
    public void periodicTick() {
        if (!handedOff) {
            glfwPollEvents();
        }
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title,
            LongSupplier monitorSupplier) {
        try {
            if (initFuture != null) {
                initFuture.get(30, TimeUnit.SECONDS);
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] early terminal init wait failed: " + t);
        }
        /*
         * If the user opened BIOS SETUP (or launched the Mechanics game from it), hold
         * the hand-off (and
         * thus the game appearing) until they leave it. Loading has finished by now, so
         * only the
         * transition into the game waits; we keep pumping input so ESC / "Exit Bios"
         * still register,
         * while the render thread keeps drawing SETUP / Tetris. This is the safe
         * "pause": we never
         * freeze FML's loader, we just delay the hand-off.
         */
        while ((state.holdsHandoff() || volumeOverlay.active() || pauseOverlay.active()) && !initFailed) {
            glfwPollEvents();
            try {
                Thread.sleep(16L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        /*
         * If BIOS was opened and then left, loading has finished but we just exited
         * onto the POST terminal.
         * Keep the render thread drawing that LIVE POST screen (spinner + final loading
         * log, no DEL prompt)
         * for a brief grace before tearing down, so the hand-off shows POST -- not a
         * frozen BIOS frame --
         * ahead of the main mod's loading overlay. biosUsed also disables DEL, so state
         * stays POST here.
         * Skipped entirely on the normal boot (biosUsed == false), so launch time is
         * unchanged.
         */
        if (biosUsed && !initFailed) {
            long graceEnd = System.nanoTime() + POST_EXIT_GRACE_MS * 1_000_000L;
            while (System.nanoTime() < graceEnd && !initFailed) {
                glfwPollEvents();
                try {
                    Thread.sleep(16L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        /* Stop scheduling new frames. cancel(false) lets any in-flight frame finish. */
        if (windowTick != null) {
            int guard = 0;
            while (!windowTick.isDone() && guard++ < 1000) {
                windowTick.cancel(false);
            }
        }
        /*
         * Wait for any in-flight frame to finish (and release the context, which it
         * always does now)
         * BEFORE flipping handedOff, so the render thread is provably idle when the
         * main thread takes
         * the context. Frames are sub-millisecond, so this returns almost immediately;
         * the generous
         * budget only matters if a GL call has wedged, in which case we proceed as a
         * logged last resort
         * rather than hanging the boot.
         */
        boolean acquired = false;
        for (int tries = 0; tries < 100 && !acquired; tries++) {
            try {
                acquired = renderLock.tryAcquire(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        handedOff = true;
        if (acquired) {
            renderLock.release();
        } else if (!initFailed) {
            System.err.println(
                    "[bamfmenu] early terminal: a render frame did not yield within budget; proceeding with hand-off");
        }

        /*
         * Take the context onto the main thread for Minecraft and release our input
         * hooks. By now the
         * render thread has released it: every frame releases the context it made
         * current, and a failed
         * init released it in its finally.
         */
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        /*
         * Keep the brand through hand-off; the main mod's WindowTitleMixin owns it from
         * here in-game.
         */
        glfwSetWindowTitle(window, APP_TITLE);
        freeCallbacks();
        shutdownRenderThread();
        /*
         * Free our OpenAL device now that the render thread is stopped, BEFORE
         * Minecraft initialises its
         * own sound system, so the two never contend for the audio device.
         */
        if (audio != null) {
            audio.close();
            audio = null;
        }
        return window;
    }

    /*
     * The render thread has done its job after hand-off; shut the executor down so
     * its daemon thread
     * does not linger for the whole session. The window + GL objects now belong to
     * Minecraft, so we do
     * not destroy them.
     */
    private void shutdownRenderThread() {
        try {
            if (renderScheduler != null) {
                renderScheduler.shutdownNow();
            }
        } catch (Throwable ignored) {
        }
    }

    private void freeCallbacks() {
        try {
            glfwSetFramebufferSizeCallback(window, null);
            glfwSetWindowSizeCallback(window, null);
            glfwSetWindowPosCallback(window, null);
            glfwSetKeyCallback(window, null);
            if (keyCallback != null) {
                keyCallback.free();
                keyCallback = null;
            }
            glfwSetCharCallback(window, null);
            if (charCallback != null) {
                charCallback.free();
                charCallback = null;
            }
            if (fbCallback != null) {
                fbCallback.free();
                fbCallback = null;
            }
            if (sizeCallback != null) {
                sizeCallback.free();
                sizeCallback = null;
            }
            if (posCallback != null) {
                posCallback.free();
                posCallback = null;
            }
            glfwSetMouseButtonCallback(window, null);
            if (mouseButtonCallback != null) {
                mouseButtonCallback.free();
                mouseButtonCallback = null;
            }
            glfwSetCursorPosCallback(window, null);
            if (cursorPosCallback != null) {
                cursorPosCallback.free();
                cursorPosCallback = null;
            }
            glfwSetScrollCallback(window, null);
            if (scrollCallback != null) {
                scrollCallback.free();
                scrollCallback = null;
            }
        } catch (Throwable ignored) {
        }
    }

    /*
     * convert a WINDOW-space pixel (GLFW cursor coords) to the LOGICAL_W
     * drawing space the screens use.
     * cursorPos is in window pixels (== glfwGetWindowSize, NOT the framebuffer,
     * which differs on hi-DPI), and the
     * canvas maps the full logical space across the full framebuffer, so the
     * window/framebuffer ratio cancels:
     * logicalX = winX / winWidth * LOGICAL_W. logicalH tracks the framebuffer
     * aspect. (Used by the editor in 5B+.)
     */
    private double windowToLogicalX(double winPxX) {
        return winPxX / Math.max(1, winWidth) * LOGICAL_W;
    }

    private double windowToLogicalY(double winPxY) {
        int logicalH = Math.max(1, LOGICAL_W * fbHeight / Math.max(1, fbWidth));
        return winPxY / Math.max(1, winHeight) * logicalH;
    }

    /*
     * the chart editor's load/save. File I/O stays provider-side (it owns
     * gameDir, like BiosCache).
     * Load: the on-disk BAMFOS save first (where the editor's saves land), else the
     * bundled classpath chart
     * (the seed to remap), else null (a new chart).
     */
    private String loadChartText(String songId, String token) {
        try {
            java.nio.file.Path p = chartFile(songId, token);
            if (java.nio.file.Files.isReadable(p)) {
                return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] editor external chart read failed: " + t);
        }
        try (java.io.InputStream in = TerminalWindowProvider.class
                .getResourceAsStream("/songs/" + songId + "/" + token + ".chart")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] editor bundled chart read failed: " + t);
        }
        return null;
    }

    /*
     * Best-effort write to <gameDir>/BAMFOS/songs/<id>/<token>.chart (mirrors
     * BiosCache's degrade-don't-crash
     * policy). Returns whether the write succeeded.
     */
    private boolean saveChart(String songId, String token, String text) {
        try {
            java.nio.file.Path p = chartFile(songId, token);
            java.nio.file.Files.createDirectories(p.getParent());
            SongLoader.ensureSongScaffold(p.getParent()); // every save guarantees a complete data-driven folder
            java.nio.file.Files.writeString(p, text, java.nio.charset.StandardCharsets.UTF_8);
            return true;
        } catch (Throwable t) {
            System.err.println("[bamfmenu] editor chart save failed: " + t);
            return false;
        }
    }

    private java.nio.file.Path bamfosSongsDir() {
        return java.nio.file.Path.of(gameDir, "BAMFOS", "songs");
    }

    private java.nio.file.Path chartFile(String songId, String token) {
        return bamfosSongsDir().resolve(songId).resolve(token + ".chart");
    }

    /* the on-disk user songs the editor browses (forks + drop-ins under BAMFOS/songs/). */
    private java.util.List<SongDef> userSongs() {
        return SongLoader.discoverDisk(bamfosSongsDir());
    }

    /* PHASE B: rebuild the live jukebox list = the bundled songs followed by the user's on-disk songs (forks,
       drop-ins, converted osu!mania maps). A user folder whose id collides with a bundled song is skipped (the
       bundled one already represents it; its chart edits live in BAMFOS and are picked up by loadChartText), so
       the list never shows the same id twice. Audio-less / multi-ogg user folders are still listed (the jukebox
       just can't launch them) -- discovery decides that, not this. Logs the count so a mass-test can confirm in
       the boot log that the pack's songs reached the menu. Never throws on the render thread. */
    private void rebuildJukebox() {
        java.util.List<SongDef> merged = new java.util.ArrayList<>(java.util.Arrays.asList(SongRegistry.SONGS));
        int userCount = 0;
        try {
            for (SongDef u : userSongs()) {
                if (u == null || isReservedSongId(u.id())) {
                    continue;
                }
                merged.add(u);
                userCount++;
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] jukebox user-song scan failed: " + t);
        }
        jukebox = java.util.List.copyOf(merged);
        System.err.println("[bamfmenu] jukebox: " + merged.size() + " song(s) loaded to menu (" + userCount + " user)");
    }

    /* Bounds-safe read of the live jukebox list (the list is replaced wholesale by rebuildJukebox, so capture it
       once); null if the index is out of range. */
    private SongDef jukeboxSong(int idx) {
        java.util.List<SongDef> j = jukebox;
        return (idx >= 0 && idx < j.size()) ? j.get(idx) : null;
    }

    /* Fork a bundled song to the next free numbered id (recovery -> recovery-2/-3/...), copying its whole folder
       to disk, then return the new on-disk SongDef (or null on failure). */
    private SongDef forkSong(SongDef bundled) {
        if (bundled == null) {
            return null;
        }
        java.nio.file.Path songs = bamfosSongsDir();
        String newId = null;
        for (int n = 2; n <= 999; n++) {
            String cand = bundled.id() + "-" + n;
            if (!java.nio.file.Files.isDirectory(songs.resolve(cand))) {
                newId = cand;
                break;
            }
        }
        if (newId == null || !SongLoader.copyBundledSong(bundled.id(), songs.resolve(newId))) {
            return null;
        }
        SongLoader.ensureSongScaffold(songs.resolve(newId)); // complete the fork's folder (empty panels/, guaranteed normal)
        for (SongDef sd : SongLoader.discoverDisk(songs)) {
            if (sd.id().equals(newId)) {
                return sd;
            }
        }
        return null;
    }

    /* Mirror the on-disk layout: if a user song's folder name doesn't already match its OGG's base name, rename the
       folder to that name (folder == <ogg-basename>, our <id>/<id>.ogg convention) -- SYNCHRONOUSLY, then re-scaffold
       + return the renamed SongDef. Fix-then-load: the whole thing happens in one ordered step on the render thread
       (a brief freeze before the editor opens), so nothing recreates the old folder / a stray chart mid-rename.
       Guards: never rename onto an existing folder or a bundled default id, sanitizes the name, and on any failure
       falls back to the current folder (never throws). */
    private SongDef normalizeUserSong(SongDef s) {
        try {
            if (s == null || s.ogg() == null || !s.ogg().startsWith("file:")) {
                return s;
            }
            java.nio.file.Path oggPath = java.nio.file.Path.of(s.ogg().substring("file:".length()));
            java.nio.file.Path dir = oggPath.getParent();
            if (dir == null) {
                return s;
            }
            String oggBase = oggPath.getFileName().toString();
            int dot = oggBase.lastIndexOf('.');
            String desired = SongLoader.sanitizeId(dot > 0 ? oggBase.substring(0, dot) : oggBase);
            String current = dir.getFileName().toString();
            if (desired.isEmpty() || desired.equals(current)) {
                return s; // already mirrored (or nothing usable to rename to)
            }
            java.nio.file.Path target = dir.resolveSibling(desired);
            if (isReservedSongId(desired) || java.nio.file.Files.exists(target)) {
                return s; // would shadow a built-in or clobber another folder -> keep the current name
            }
            java.nio.file.Path moved = java.nio.file.Files.move(dir, target);
            SongLoader.ensureSongScaffold(moved); // re-complete the renamed folder (idempotent)
            SongLoader.patchOggField(moved);      // point song.json's ogg at the real file
            for (SongDef sd : SongLoader.discoverDisk(bamfosSongsDir())) {
                if (sd.id().equals(desired)) {
                    return sd;
                }
            }
            return s;
        } catch (Throwable t) {
            System.err.println("[bamfmenu] user-song normalize failed: " + t);
            return s; // never throw on the render thread; fall back to the current folder
        }
    }

    /* True if id collides with a bundled (in-jar) song -- a user folder by that name would hijack its chart lookup
       (loadChartText checks BAMFOS first), so the rename must avoid it. */
    private boolean isReservedSongId(String id) {
        for (SongDef d : SongRegistry.SONGS) {
            if (d.id().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /* Create a fresh empty user song: the next free new-song / new-song-N folder under BAMFOS/songs/, fully
       scaffolded (complete song.json + blank normal.chart + empty panels/) but with NO audio yet -- the user drops
       an OGG in, after which opening it auto-mirrors the folder to the song's name. Returns the new id, or null. */
    private String createNewSong() {
        try {
            java.nio.file.Path songs = bamfosSongsDir();
            java.nio.file.Files.createDirectories(songs);
            String newId = null;
            if (!java.nio.file.Files.exists(songs.resolve("new-song"))) {
                newId = "new-song";
            } else {
                for (int n = 2; n <= 999; n++) {
                    if (!java.nio.file.Files.exists(songs.resolve("new-song-" + n))) {
                        newId = "new-song-" + n;
                        break;
                    }
                }
            }
            if (newId == null) {
                return null;
            }
            SongLoader.ensureSongScaffold(songs.resolve(newId)); // song.json + blank normal.chart + empty panels/ (no ogg)
            return newId;
        } catch (Throwable t) {
            System.err.println("[bamfmenu] create new song failed: " + t);
            return null;
        }
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter,
            IntConsumer xSetter, IntConsumer ySetter) {
        widthSetter.accept(winWidth);
        heightSetter.accept(winHeight);
        xSetter.accept(winX);
        ySetter.accept(winY);
        return true;
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {
        width.accept(fbWidth);
        height.accept(fbHeight);
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
        Module minecraft = layer.findModule("minecraft").orElseThrow();
        getClass().getModule().addReads(minecraft);
        try {
            Class<?> overlay = Class.forName(minecraft, "net.minecraft.client.gui.screens.LoadingOverlay");
            Class<?> mc = Class.forName(minecraft, "net.minecraft.client.Minecraft");
            Class<?> reload = Class.forName(minecraft, "net.minecraft.server.packs.resources.ReloadInstance");
            if (overlay == null || mc == null || reload == null) {
                throw new IllegalStateException("missing Minecraft loading-overlay classes");
            }
            this.loadingOverlayCtor = overlay.getConstructor(mc, reload, Consumer.class, boolean.class);
        } catch (Throwable t) {
            throw new IllegalStateException("bamfmenu: could not resolve vanilla LoadingOverlay constructor", t);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> loadingOverlay(Supplier<?> mc, Supplier<?> ri, Consumer<Optional<Throwable>> ex,
            boolean fade) {
        return () -> {
            try {
                return (T) loadingOverlayCtor.newInstance(mc.get(), ri.get(), ex, fade);
            } catch (Throwable t) {
                throw new IllegalStateException("bamfmenu: could not build the loading overlay", t);
            }
        };
    }

    @Override
    public String getGLVersion() {
        return glVersion;
    }

    @Override
    public void crash(String message) {
        System.err.println("[bamfmenu] early terminal crash: " + message);
    }
}
