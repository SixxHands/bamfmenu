package com.retro.bamfmenu.client;

import org.lwjgl.glfw.GLFW;

/* Central place to decide which OS-touching cosmetic features are safe on the current platform, so
   each one can self-disable instead of crashing. Detection is via GLFW's runtime platform query
   (LWJGL 3.3.3 ships GLFW 3.4, which has glfwGetPlatform). The value is read once, lazily, the first
   time any capability is queried; by then GLFW is initialized (the window already exists). */
public final class PlatformCapabilities {

    private static final int PLATFORM = detect();

    private PlatformCapabilities() {}

    private static int detect() {
        try {
            return GLFW.glfwGetPlatform();
        } catch (Throwable t) {
            return GLFW.GLFW_PLATFORM_NULL;
        }
    }

    public static boolean isWindows() {
        return PLATFORM == GLFW.GLFW_PLATFORM_WIN32;
    }

    public static boolean isWayland() {
        return PLATFORM == GLFW.GLFW_PLATFORM_WAYLAND;
    }

    public static boolean isMacOS() {
        return PLATFORM == GLFW.GLFW_PLATFORM_COCOA;
    }

    /* glfwSetWindowIcon emits FEATURE_UNAVAILABLE (a no-op) on Wayland and macOS; it works on Win32
       and X11 (including XWayland). */
    public static boolean canSetWindowIcon() {
        return !isWayland() && !isMacOS();
    }

    public static String platformName() {
        return switch (PLATFORM) {
            case GLFW.GLFW_PLATFORM_WIN32 -> "win32";
            case GLFW.GLFW_PLATFORM_COCOA -> "macos";
            case GLFW.GLFW_PLATFORM_X11 -> "x11";
            case GLFW.GLFW_PLATFORM_WAYLAND -> "wayland";
            default -> "unknown";
        };
    }
}
