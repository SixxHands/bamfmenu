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

/* Reskins NeoForge's early "red screen" into a black DOS-style terminal by extending FML's own
   DisplayWindow: the GLFW window + (very fragile) Minecraft hand-off stay inherited and untouched, we only
   swap what is DRAWN. Runs on the SERVICE module layer, BEFORE any @Mod/Minecraft/Mixin class exists, so
   nothing but early-display + the SPI + reflection may be touched here. All curation is best-effort
   try/catch: if FML's internals change, the stock window renders and the game still boots. */
public class TerminalEarlyWindow extends DisplayWindow {

    private boolean curated;
    private boolean colourTinted;

    @Override
    public String name() {
        /* Legacy fallback, kept selectable so config/fml.toml can point back here if the from-scratch
           TerminalWindowProvider ("bamfmenu_terminal") ever fails to hand off. Must be unique, NOT "fmlearlywindow". */
        return "bamfmenu_terminal_legacy";
    }

    /* The inherited updateModuleReads throws IllegalCallerException when subclassed: its addReads is
       caller-sensitive, and getClass() resolves to OUR module while the caller is fml_earlydisplay. Rerun
       the same logic from our own caller context, then wire DisplayWindow's private `loadingOverlay`
       exactly as the original does so the inherited phase-2 hand-off still works. */
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

    // Every tick: force the black scheme (cheap, idempotent); once the font/context exist, swap in the text-only elements a single time.
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
        /* FML captures the colour scheme BY VALUE into the render context at initRender, and by then it is
           the default RED: forceBlack() only flips the live field the background clear reads, so the
           background went black while the bars + log text (which read context.colourScheme()) stayed red.
           Rebuild the context with BLACK, every other field copied as-is. */
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

    /* Retint BLACK's pure-white foreground to classic DOS light gray (text attribute 0x07 ~ 0xAAAAAA).
       The flag is set before the attempt so a hopeless reflection call is not retried every tick. */
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
