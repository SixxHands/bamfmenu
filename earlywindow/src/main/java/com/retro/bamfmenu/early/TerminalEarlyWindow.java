package com.retro.bamfmenu.early;

import net.neoforged.fml.earlydisplay.ColourScheme;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleFont;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/* reskins NeoForge's early bootstrap window (the "red screen" shown while mods load, before
   Minecraft itself starts) into a black IBM/DOS-style terminal.

   This runs on FancyModLoader's SERVICE module layer, BEFORE any @Mod/Minecraft/Mixin class exists,
   so it must be completely self-contained: only FancyModLoader's early-display + the SPI + reflection,
   never a Minecraft or mod class.

   Rather than reimplement the GLFW window creation and (very fragile) hand-off to Minecraft, we
   extend FancyModLoader's own DisplayWindow and inherit all of that untouched. We only change what is
   DRAWN: force the background to black and replace its render-element list with the text-only elements
   (the real startup log messages + progress + performance, in the bundled Monocraft monospace font),
   dropping the Mojang logo and mascot. The result is a black terminal that reports exactly what is
   loading, matching the in-game loading screen. All of this is best-effort reflection wrapped in
   try/catch: if FancyModLoader's internals ever change, we silently fall back to the normal window and
   the game still boots, because the inherited hand-off is never touched.

   Discovery: META-INF/services/net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider names
   this class. Activation: the instance's config/fml.toml must set earlyWindowProvider="bamfmenu_terminal"
   (and earlyWindowControl=true). That file is per-instance and cannot ship in the jar, so the pack
   profile seeds it (see docs/BAMF_MENU_PLAN.md). DisplayWindow already implements ImmediateWindowProvider. */
public class TerminalEarlyWindow extends DisplayWindow {

    private boolean curated;
    private boolean colourTinted;

    @Override
    public String name() {
        /* Legacy fallback provider (DisplayWindow subclass with FML's own elements). The real text
           terminal is TerminalWindowProvider ("bamfmenu_terminal"); this one is kept selectable so that
           if the from-scratch provider ever fails to hand off, config/fml.toml can point back here.
           Must be unique and must NOT be "fmlearlywindow"; matched against config/fml.toml. */
        return "bamfmenu_terminal_legacy";
    }

    /* DisplayWindow.updateModuleReads does `getClass().getModule().addReads(neoforge)`, which is
       caller-sensitive: when subclassed, getClass() resolves to OUR module while the caller is the
       fml_earlydisplay module, so the inherited version throws IllegalCallerException. We override it
       and run the same logic from our own module's caller context (legal), then reflectively set
       DisplayWindow's private `loadingOverlay` method handle exactly as the original does so the
       inherited phase-2 hand-off still works. */
    @Override
    public void updateModuleReads(ModuleLayer layer) {
        Module neoforge = layer.findModule("neoforge").orElseThrow();
        getClass().getModule().addReads(neoforge);
        Class<?> overlay = Class.forName(neoforge, "net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay");
        Method newInstance = null;
        for (Method m : overlay.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("newInstance")) {
                newInstance = m;
                break;
            }
        }
        try {
            Field f = DisplayWindow.class.getDeclaredField("loadingOverlay");
            f.setAccessible(true);
            f.set(this, newInstance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("bamfmenu: could not wire early-window loadingOverlay", e);
        }
    }

    @Override
    public Runnable initialize(String[] arguments) {
        Runnable superTick = super.initialize(arguments);
        return () -> {
            try {
                curate();
            } catch (Throwable ignored) {
                /* leave the default window; the game still boots via the inherited hand-off */
            }
            superTick.run();
        };
    }

    /* Force the black scheme every tick (cheap, idempotent) and, once DisplayWindow has built its
       elements/font, swap in the text-only terminal set a single time. */
    private void curate() {
        forceBlack();
        forceTerminalForeground();
        if (curated) {
            return;
        }
        SimpleFont font = readField("font", SimpleFont.class);
        if (font == null) {
            return; // initRender hasn't run yet; try again next tick
        }
        /* FancyModLoader captures the colour scheme BY VALUE into the render context at initRender time,
           and by then it is the default RED scheme. forceBlack() only flips the live field that the
           background clear reads (DisplayWindow.render), which is why the background already goes black;
           the progress bars and log text instead read context.colourScheme(), so they stayed red. We
           rebuild the context with the BLACK scheme (every other field copied as-is) so the elements
           render on the terminal scheme too. ElementShader/PerformanceInfo are public, so this is a
           plain record copy, not reflection. */
        RenderElement.DisplayContext ctx = readField("context", RenderElement.DisplayContext.class);
        if (ctx == null) {
            return; // context not built yet; try again next tick
        }
        if (ctx.colourScheme() != ColourScheme.BLACK) {
            RenderElement.DisplayContext recoloured = new RenderElement.DisplayContext(
                    ctx.width(), ctx.height(), ctx.scale(), ctx.elementShader(), ColourScheme.BLACK, ctx.performance());
            writeField("context", recoloured);
        }
        List<RenderElement> terminal = new ArrayList<>();
        terminal.add(RenderElement.logMessageOverlay(font)); // the real "what's loading" log lines
        terminal.add(RenderElement.progressBars(font));      // mod-loading progress
        terminal.add(RenderElement.performanceBar(font));    // memory / fps, system-monitor vibe
        writeField("elements", terminal);
        curated = true;
    }

    private void forceBlack() {
        try {
            Field f = DisplayWindow.class.getDeclaredField("colourScheme");
            f.setAccessible(true);
            if (f.get(this) != ColourScheme.BLACK) {
                f.set(this, ColourScheme.BLACK);
            }
        } catch (Throwable ignored) {
        }
    }

    /* The BLACK scheme's foreground is pure white; classic DOS text mode is the softer light gray
       (text attribute 0x07 ~ 0xAAAAAA). Retint BLACK's foreground once so the terminal text and bars
       read as authentic gray-on-black. This is the same in-package reflection forceBlack() already
       relies on; best-effort, and on any failure the stock white foreground is still a clean
       white-on-black terminal. Done once (the flag is set before the attempt so a hopeless reflection
       call is not retried every tick). */
    private void forceTerminalForeground() {
        if (colourTinted) {
            return;
        }
        colourTinted = true;
        try {
            Field f = ColourScheme.class.getDeclaredField("foreground");
            f.setAccessible(true);
            f.set(ColourScheme.BLACK, new ColourScheme.Colour(170, 170, 170));
        } catch (Throwable ignored) {
        }
    }

    private <T> T readField(String name, Class<T> type) {
        try {
            Field f = DisplayWindow.class.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(this);
            return type.isInstance(v) ? type.cast(v) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeField(String name, Object value) {
        try {
            Field f = DisplayWindow.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(this, value);
        } catch (Throwable ignored) {
        }
    }
}
