package com.retro.bamfmenu.early.core.secret.secrets.doom.game;

import doom.DoomMain;
import g.Signals.ScanCode;
import mochadoom.Engine;
import v.DoomGraphicSystem;
import v.renderers.DoomScreen;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/* Headless bridge to the vendored pure-Java DOOM engine (Mocha Doom, patched for embedding, see the EMBEDDED notes
   in mochadoom.Engine / doom.DoomMain / i.DoomSystem). It runs DOOM's blocking game loop on a daemon thread with NO
   AWT window and NO sound; the host snapshots the framebuffer (indexed bytes + the live palette) for the ASCII
   renderer and feeds key events. DOOM advances on its own thread; the render thread only reads.

   start() is NON-BLOCKING (it must never stall the BIOS render thread): it launches the daemon and returns; ready()/
   frame() report when frames are available. stop() clears Engine.RUN so the patched loop ends cleanly; the patched
   engine never calls System.exit, so it can't take the Minecraft JVM down. start()/stop()/start() is restart-safe:
   after a previous run, Engine.instance still points at the old engine, so we ignore it until our fresh one replaces it.
   core/ layer: imports the vendored engine (allowed), never bios/. */
public final class DoomEngine {

    /** Unchangeable snapshot of one rendered frame: palette-index bytes (width*height) + the live 256-entry RGB palette. */
    public record Frame(byte[] indices, int width, int height, int[] palette) { }

    private Thread loop;
    private volatile DoomMain<?, ?> dm;
    private volatile DoomGraphicSystem<?, ?> gs;
    private volatile boolean started;
    private volatile boolean dead;
    private Engine staleInstance; // the pre-existing engine (if any) to ignore until our fresh one replaces it
    private int[] palCache;       // the real 256-entry RGB palette (reflected from the renderer's PLAYPAL), built once
    private Path wadFile;

    /** Point DOOM's savegames (doomsavN.dsg) at a real folder. Call before start(); without it saves fall back
        to the process working dir (vanilla behavior). Best-effort: an unmakeable dir just keeps the fallback. */
    public void setSaveDir(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            Engine.SAVE_DIR = dir.toString() + java.io.File.separator;
        } catch (Exception e) {
            System.err.println("[bamfmenu] doom save dir unavailable, saves fall back to the game dir: " + e);
        }
    }

    /** Launch DOOM on a daemon thread. NON-BLOCKING, returns immediately; poll ready()/failed(). Idempotent. */
    public synchronized void start() {
        if (started) {
            return;
        }
        try {
            wadFile = extractWad();
        } catch (Exception e) {
            throw new RuntimeException("DoomEngine: WAD extract failed", e);
        }
        gs = null;
        dm = null;
        dead = false;
        palCache = null;
        staleInstance = Engine.instanceOrNull(); // restart: ignore the old engine until ours replaces Engine.instance
        Engine.HEADLESS = true;
        Engine.RUN = true;
        loop = new Thread(() -> {
            try {
                // boot to DOOM's title/main menu (no autostart): the player opens the menu with Tab to start/load a
                // game; our Esc returns to the BIOS.
                Engine.main(new String[]{ "-nosound", "-iwad", wadFile.toString() });
            } catch (Throwable t) {
                System.out.println("[bamfmenu] DOOM loop ended: " + t);
                t.printStackTrace(System.out);
            } finally {
                dead = true;
            }
        }, "bamf-doom");
        loop.setDaemon(true);
        loop.start();
        started = true;
    }

    /** Stop the loop cleanly (clears Engine.RUN) and join the daemon thread. Safe to call repeatedly. */
    public synchronized void stop() {
        Engine.RUN = false;
        Thread l = loop;
        if (l != null) {
            try {
                l.join(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        loop = null;
        gs = null;
        dm = null;
        started = false;
        dead = false; // intentional stop, not a boot failure
    }

    /** True once DOOM has produced its graphics system (frames are available). */
    public boolean ready() {
        return resolve() != null;
    }

    /** True if the loop ended before ever producing a frame (e.g. a boot error), the host should bail out. */
    public boolean failed() {
        return dead && gs == null;
    }

    public boolean isRunning() {
        Thread l = loop;
        return started && l != null && l.isAlive();
    }

    /** Snapshot the current frame (copied indices + live palette), or null if not ready / unexpected format. */
    public Frame frame() {
        DoomGraphicSystem<?, ?> g = resolve();
        if (g == null) {
            return null;
        }
        Object s = g.getScreen(DoomScreen.FG);
        if (!(s instanceof byte[] b)) {
            return null; // unexpected (non-indexed) screen format
        }
        return new Frame(Arrays.copyOf(b, b.length), g.getScreenWidth(), g.getScreenHeight(), palette(g));
    }

    /** Live HUD/menu snapshot (health/armor/ammo/weapon + the active menu's items), read by reflection. Never null. */
    public DoomState.Snapshot hud() {
        return DoomState.read(dm);
    }

    /* The real 256-color DOOM palette as 0xRRGGBB, reflected from the renderer's PLAYPAL lump (cached). NOTE: the
       engine's getBaseColor(int) is NOT an index->color lookup, it transforms an already-packed color, so
       feeding it palette indices yielded a garbage near-blue ramp; reading PLAYPAL directly is the correct palette. */
    private int[] palette(DoomGraphicSystem<?, ?> g) {
        int[] p = palCache;
        if (p != null) {
            return p;
        }
        byte[] pp = reflectPlaypal(g);
        p = new int[256];
        if (pp != null && pp.length >= 768) {
            for (int i = 0; i < 256; i++) {
                p[i] = ((pp[i * 3] & 0xFF) << 16) | ((pp[i * 3 + 1] & 0xFF) << 8) | (pp[i * 3 + 2] & 0xFF);
            }
        } else {
            for (int i = 0; i < 256; i++) {
                p[i] = g.getBaseColor(i) & 0xFFFFFF; // fallback only (imperfect), never crash
            }
        }
        palCache = p;
        return p;
    }

    private static byte[] reflectPlaypal(Object gs) {
        for (Class<?> k = gs.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field fld = k.getDeclaredField("playpal");
                fld.setAccessible(true);
                Object v = fld.get(gs);
                if (v instanceof byte[] b && b.length >= 768) {
                    return b;
                }
            } catch (ReflectiveOperationException ignored) {
                // walk up to the superclass
            }
        }
        return null;
    }

    /** Push a key event into DOOM (a g.Signals.ScanCode + pressed/released). No-op until the engine resolves. */
    public void postKey(ScanCode sc, boolean down) {
        DoomMain<?, ?> d = dm;
        if (d != null && sc != null) {
            d.PostEvent(down ? sc.doomEventDown : sc.doomEventUp);
        }
    }

    /** Map a GLFW key code -> DOOM ScanCode and post it (pressed/released). Keeps g.Signals out of the bios layer. */
    public void postGlfwKey(int glfwKey, boolean down) {
        ScanCode sc = mapGlfw(glfwKey);
        if (sc != null) {
            postKey(sc, down);
        }
    }

    private static ScanCode mapGlfw(int k) {
        return switch (k) {
            case 263 -> ScanCode.SC_LEFT;
            case 262 -> ScanCode.SC_RIGHT;
            case 265 -> ScanCode.SC_UP;
            case 264 -> ScanCode.SC_DOWN;
            case 341 -> ScanCode.SC_LCTRL;   // fire
            case 32  -> ScanCode.SC_SPACE;   // use / open door
            case 257, 335 -> ScanCode.SC_ENTER;
            case 340 -> ScanCode.SC_LSHIFT;  // run
            case 342 -> ScanCode.SC_LALT;    // strafe modifier
            case 44  -> ScanCode.SC_COMMA;   // strafe left
            case 46  -> ScanCode.SC_PERIOD;  // strafe right
            case 258 -> ScanCode.SC_ESCAPE;  // TAB opens DOOM's in-game menu (Esc is reserved by the host for "back to BIOS")
            case 89  -> ScanCode.SC_Y;
            case 78  -> ScanCode.SC_N;
            case 49  -> ScanCode.SC_1;
            case 50  -> ScanCode.SC_2;
            case 51  -> ScanCode.SC_3;
            case 52  -> ScanCode.SC_4;
            case 53  -> ScanCode.SC_5;
            case 54  -> ScanCode.SC_6;
            case 55  -> ScanCode.SC_7;
            default  -> null;
        };
    }

    private DoomGraphicSystem<?, ?> resolve() {
        DoomGraphicSystem<?, ?> g = gs;
        if (g != null) {
            return g;
        }
        Engine e = Engine.instanceOrNull();
        if (e != null && e != staleInstance) {
            DoomMain<?, ?> d = e.doom();
            if (d != null && d.graphicSystem != null) {
                gs = d.graphicSystem;
                dm = d;
                return gs;
            }
        }
        return null;
    }

    private static Path extractWad() throws Exception {
        // DOOM identifies the IWAD by FILENAME, so the extracted file MUST be named exactly "doom1.wad"
        // (a random temp name reads as "Game mode indeterminate" + an empty WAD list).
        Path dir = Files.createTempDirectory("bamf-doom-");
        dir.toFile().deleteOnExit();
        Path wad = dir.resolve("doom1.wad");
        try (InputStream in = DoomEngine.class.getResourceAsStream("/doom/doom1.wad")) {
            if (in == null) {
                throw new IllegalStateException("bundled /doom/doom1.wad not found on the classpath");
            }
            Files.copy(in, wad, StandardCopyOption.REPLACE_EXISTING);
        }
        wad.toFile().deleteOnExit();
        return wad;
    }
}
