package com.retro.bamfos.updater.engine.ping;

import com.retro.bamfos.updater.engine.json.JsonWriter;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

// Builds the server-list status JSON served by PortHold while updating (omits betterStatus so clients don't sync to a half-started server).
public final class UpdatingNotice {

    /** Vanilla icons are 64x64 PNGs; skip oversized files instead of base64-encoding them into every ping. */
    static final int MAX_ICON_BYTES = 64 * 1024;

    // Target version lookup

    /**
     * Target tag this boot is updating to (from WatchdogState), or empty if unknown. Returns empty when
     * it matches the installed version so normal restarts don't advertise a fake update.
     */
    public static String target(String lastTarget, String localVersion) {
        String t = lastTarget.trim();
        return t.equals(localVersion.trim()) ? "" : t;
    }

    /** Reads last_target from .bamfos-updater/watchdog-state.properties (written by WatchdogState.save
     *  in the watchdog module; keep the filename and key in sync). */
    public static String lastTarget(Path dir) {
        Properties p = new Properties();
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("watchdog-state.properties");
            if (Files.isRegularFile(f)) {
                p.load(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
            }
        } catch (Exception unreadable) {
            // Fall back to a versionless notice instead of failing during startup.
        }
        return p.getProperty("last_target", "");
    }

    // Notice text

    // Status headline for updating vs starting up without a known target tag.
    public static String headline(String target) {
        return target.isEmpty() ? "Starting server..." : "Updating the server to " + target + "!";
    }

    /** Two lines, because that is what the server list draws. */
    public static String motd(String target) {
        return headline(target) + "\nBack in a minute";
    }

    public static String refusal(String target) {
        return headline(target) + "\nGive it a minute and try again.";
    }

    // Status and login packets

    /**
     * Builds the updating status JSON. Omits players (renders "???" instead of 0/20), uses Slp.PROTOCOL
     * (avoids a red INCOMPATIBLE row), and omits betterStatus so clients don't sync to an unapplied version.
     */
    public static String statusJson(String target, byte[] icon) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("name", Slp.MC_VERSION);
        version.put("protocol", Slp.PROTOCOL);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("description", text(motd(target)));
        root.put("version", version);
        root.put("isModded", true);
        if (icon != null && icon.length > 0) {
            root.put("favicon", "data:image/png;base64," + Base64.getEncoder().encodeToString(icon));
        }
        return JsonWriter.write(root);
    }

    /** Chat component for the login disconnect packet. Login state has no registry access, so this
     *  still uses plain JSON text instead of NBT components. */
    public static String refusalJson(String target) {
        return JsonWriter.write(text(refusal(target)));
    }

    private static Map<String, Object> text(String s) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("text", s);
        return c;
    }

    // Listen address and port

    /** Reads the server port from server.properties, not bamfos-updater.properties (where server_port
     *  is the port clients ping). 0 lets the OS pick an ephemeral port for tests. */
    public static int bindPort(Properties serverProps) {
        try {
            int p = Integer.parseInt(serverProps.getProperty("server-port", "25565").trim());
            return p >= 0 && p <= 65535 ? p : 25565;
        } catch (NumberFormatException hand) {
            return 25565;
        }
    }

    /** Reads server-ip from server.properties (empty means bind all interfaces). */
    public static String bindHost(Properties serverProps) {
        return serverProps.getProperty("server-ip", "").trim();
    }

    /** Reads server-icon.png bytes (or null) so the server list keeps its normal icon during updates. */
    static byte[] icon(Path dir) {
        try {
            Path f = dir.resolve("server-icon.png");
            if (Files.isRegularFile(f) && Files.size(f) <= MAX_ICON_BYTES) {
                return Files.readAllBytes(f);
            }
        } catch (Exception ignored) {
            // Still show the notice even if the server icon is missing or unreadable.
        }
        return null;
    }

    private UpdatingNotice() {
    }
}
