package com.retro.bamfmenu.early.bios.host;

/**
 * FML's own EARLY progress labels, rendered in the OS register, so the status row and the log
 * read like a kernel bringing modules up instead of a game loader talking about itself. Every case below is
 * a real label pushed through ImmediateWindowHandler.updateProgress, checked against the FML 4.0.42 source
 * NeoForge 21.1.235 ships, and the label IS the whole observable: no timers, no invented steps, and an
 * unknown label falls through to itself so a loader bump degrades to the truth and NEVER to a lie.
 * Deliberately NOT here, because nothing real backs them: "Mounting boot volume" (the handler labels the bar
 * in the same breath as it creates it, so no drawn frame ever has no label), "Loading bootstrap resources"
 * (the string lives in FML's BackgroundWaiter, which has zero referents in 4.0.42, so reviving it would
 * point a future debugger at dead code), and "Starting package manager" (the boot that has no shim prints
 * that it has no shim, so no package manager starts).
 */
public final class BootPhase {

    /** Set once FML asks for the window (setupMinecraftWindow), which is the last thing that happens on
     *  this screen. No label is pushed after that, so without this the log's last row would be a stale
     *  "Starting Minecraft" for the whole BIOS hold and the POST exit grace. */
    public static final String HANDOFF = "Handing off to game";

    /** The phase the terminal shows, hand-off included. handingOff OUTRANKS the label on purpose: FML pushes
     *  nothing after asking for the window, so the label still standing is the one that just finished. */
    public static String phase(boolean handingOff, String label) {
        return handingOff ? HANDOFF : render(label);
    }

    private static final String FOUND_PREFIX = "Found ";
    private static final String FOUND_SUFFIX = " mod candidates";
    private static final String PROVIDER_PREFIX = "Loaded language provider ";

    /** The label in the OS register, or the label itself when we do not know it. */
    public static String render(String label) {
        if (label == null) {
            return "";
        }
        String s = label.trim();
        switch (s) {
            case "Bootstrapping Minecraft":
                return "Loading NeoForge";
            case "Discovering mod files":
                // mods ARE the kernel modules in this register, same word the updater's own kmod lines use
                return "Scanning kernel modules";
            case "Loading language providers":
                return "Loading module loaders";
            case "Scanning mod candidates":
                return "Linking kernel modules";
            case "Launching minecraft":
                return "Starting Minecraft";
            default:
                break;
        }
        if (s.startsWith(FOUND_PREFIX) && s.endsWith(FOUND_SUFFIX)) {
            String n = s.substring(FOUND_PREFIX.length(), s.length() - FOUND_SUFFIX.length());
            return "Kernel modules found: " + n; // the count is FML's, we only re-word around it
        }
        if (s.startsWith(PROVIDER_PREFIX) && s.length() > PROVIDER_PREFIX.length()) {
            return "Module loader ready: " + s.substring(PROVIDER_PREFIX.length());
        }
        return s;
    }

    private BootPhase() {
    }
}
