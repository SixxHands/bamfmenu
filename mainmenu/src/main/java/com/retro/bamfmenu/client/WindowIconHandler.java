package com.retro.bamfmenu.client;

import com.mojang.logging.LogUtils;
import com.retro.bamfmenu.BamfMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/* Picks a random PNG from assets/bamfmenu/icon/ (bundled in the jar) each launch and applies it as
   the GLFW window/taskbar icon. STB decodes straight to the tightly-packed RGBA8 buffer GLFW wants,
   which sidesteps NativeImage's byte-order quirks. The icon set is discovered at runtime via the mod
   file, so dropping more PNGs into that folder needs no code change. Runs once, on the render thread
   (FMLClientSetupEvent.enqueueWork), after the window exists; every failure path degrades to the
   default icon rather than crashing. No-op on Wayland/macOS where GLFW cannot set a window icon. */
public final class WindowIconHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /* The icon chosen this launch, exposed so the window chrome can draw it in the title bar (the
       same icon GLFW shows on the taskbar). Null until set / if none available. */
    private static volatile ResourceLocation chosenIcon;
    private static volatile int chosenWidth;
    private static volatile int chosenHeight;

    private WindowIconHandler() {}

    public static ResourceLocation chosenIcon() {
        return chosenIcon;
    }

    public static int chosenWidth() {
        return chosenWidth;
    }

    public static int chosenHeight() {
        return chosenHeight;
    }

    public static void applyRandomIcon() {
        if (!PlatformCapabilities.canSetWindowIcon()) {
            LOGGER.info("[bamfmenu] window icon unsupported on {}, keeping default", PlatformCapabilities.platformName());
            return;
        }
        long handle = Minecraft.getInstance().getWindow().getWindow();
        if (handle == 0L) {
            LOGGER.warn("[bamfmenu] window handle not ready; skipping icon");
            return;
        }

        Path pick;
        try {
            Path dir = ModList.get().getModFileById(BamfMenu.MOD_ID).getFile().findResource("assets", "bamfmenu", "icon");
            if (dir == null || !Files.isDirectory(dir)) {
                LOGGER.info("[bamfmenu] no bundled icon directory; keeping default");
                return;
            }
            List<Path> pngs;
            try (Stream<Path> s = Files.list(dir)) {
                pngs = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).sorted().toList();
            }
            if (pngs.isEmpty()) {
                LOGGER.info("[bamfmenu] no bundled icons; keeping default");
                return;
            }
            pick = pngs.get(ThreadLocalRandom.current().nextInt(pngs.size()));
        } catch (Exception e) {
            LOGGER.warn("[bamfmenu] failed to enumerate icons; keeping default", e);
            return;
        }

        try {
            if (setIcon(handle, Files.readAllBytes(pick))) {
                String file = pick.getFileName().toString();
                /* The GLFW/taskbar icon is set above straight from the decoded bytes (filename-agnostic).
                   The title-bar caption icon needs a valid resource path, which must be all lowercase
                   [a-z0-9/._-]; tryParse returns null (instead of throwing) for names like "BAMFDOOM.png"
                   so an oddly-named icon degrades to "taskbar icon only" rather than a stacktrace. */
                chosenIcon = ResourceLocation.tryParse(BamfMenu.MOD_ID + ":icon/" + file);
                if (chosenIcon == null) {
                    LOGGER.info("[bamfmenu] applied window icon: {} (rename it lowercase to also show it in the title bar)", file);
                } else {
                    LOGGER.info("[bamfmenu] applied window icon: {}", file);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[bamfmenu] failed to set window icon {}", pick.getFileName(), e);
        }
    }

    private static boolean setIcon(long handle, byte[] png) {
        ByteBuffer raw = MemoryUtil.memAlloc(png.length);
        ByteBuffer pixels = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            raw.put(png).flip();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            pixels = STBImage.stbi_load_from_memory(raw, w, h, channels, 4); // force RGBA
            if (pixels == null) {
                LOGGER.warn("[bamfmenu] could not decode icon ({})", STBImage.stbi_failure_reason());
                return false;
            }
            GLFWImage.Buffer icons = GLFWImage.malloc(1, stack);
            icons.position(0).width(w.get(0)).height(h.get(0)).pixels(pixels);
            GLFW.glfwSetWindowIcon(handle, icons);
            chosenWidth = w.get(0);
            chosenHeight = h.get(0);
            return true;
        } finally {
            if (pixels != null) {
                STBImage.stbi_image_free(pixels);
            }
            MemoryUtil.memFree(raw);
        }
    }
}
