package com.retro.bamfmenu.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ModMismatchDisconnectedScreen;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * Join-guard Q2's mod half: on a registry/channel-mismatch kick FROM THE PACK'S OWN
 * SERVER, write the one observation file the menu is allowed to write (last-disconnect.json) and
 * replace the disconnect screen with the XP blue screen. Every actual fix happens in the engine at
 * the NEXT boot -- this class renders theater and records one breadcrumb, nothing else.
 * Random third-party servers never trigger this; their kicks keep the vanilla screen.
 */
public final class KickWatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile String lastHost = "";

    /** Stashed by ConnectScreenMixin: the config-phase kick has no other address source. */
    public static void connectingTo(String host, int port) {
        lastHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        try {
            Screen s = event.getNewScreen();
            String reason = null;
            boolean mismatch = false;
            if (s instanceof ModMismatchDisconnectedScreen mm) {
                // NeoForge only builds this screen when the server sent per-channel failure data --
                // its existence IS the mismatch signal, and the channel ids name the culprit mods
                reason = mismatchReason(mm);
                mismatch = true;
            } else if (s instanceof DisconnectedScreen ds) {
                Component c = reasonOf(ds);
                reason = c == null ? "" : c.getString();
                mismatch = isMismatch(c, reason);
            }
            if (!mismatch || reason == null || reason.isEmpty() || !fromPackServer()) {
                return;
            }
            Path dir = FMLPaths.GAMEDIR.get();
            String fingerprint = fingerprint(reason);
            writeBreadcrumb(dir, reason, fingerprint);
            boolean unrepairable = recurredUnchanged(dir, fingerprint);
            LOGGER.info("[bamfmenu] mismatch kick from the pack server, blue screen up ({}): {}",
                    unrepairable ? "unrepairable" : "repairable", fingerprint);
            event.setNewScreen(new BsodScreen(reason, unrepairable));
        } catch (Throwable t) {
            // spirit: a broken interceptor must never eat the vanilla disconnect screen
            LOGGER.warn("[bamfmenu] kick interceptor skipped: {}", t.toString());
        }
    }

    /** Mismatch = the reason NeoForge/vanilla build for incompatible sets; ordinary kicks (ban,
     *  timeout, shutdown) stay vanilla. Keyed on translation ids; plain-text kicks get a contains
     *  fallback because proxy plugins send literals. */
    private static boolean isMismatch(Component c, String text) {
        if (c != null && c.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            return key.startsWith("neoforge.network.")
                    || key.equals("multiplayer.disconnect.incompatible")
                    || key.equals("multiplayer.disconnect.outdated_client")
                    || key.equals("multiplayer.disconnect.outdated_server")
                    || key.equals("multiplayer.disconnect.missing_tags");
        }
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("incompatible") || t.contains("neoforge") || t.contains("registry")
                || t.contains("channel") || t.contains("mismatch");
    }

    /** The kick came from OUR server? properties server_host first, else the first saved server --
     *  the same resolution order the engine pings. */
    private static boolean fromPackServer() {
        String host = lastHost;
        if (host.isEmpty()) {
            return false;
        }
        String pack = packServerHost();
        return !pack.isEmpty() && pack.equals(host);
    }

    private static String packServerHost() {
        try {
            Path props = FMLPaths.GAMEDIR.get().resolve(".bamfos-updater").resolve("bamfos-updater.properties");
            if (Files.isRegularFile(props)) {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.startsWith("server_host=")) {
                        String v = s.substring("server_host=".length()).trim();
                        if (!v.isEmpty()) {
                            return v.toLowerCase(Locale.ROOT);
                        }
                    }
                }
            }
            // mod floor has the real ServerList parser; the engine reads the same file with its own NBT straw
            ServerList list = new ServerList(Minecraft.getInstance());
            list.load();
            if (list.size() > 0) {
                String ip = list.get(0).ip.toLowerCase(Locale.ROOT);
                int colon = ip.lastIndexOf(':');
                return colon > 0 && ip.indexOf(':') == colon ? ip.substring(0, colon) : ip;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** DisconnectedScreen hides its details field; official mappings at runtime make the name
     *  stable, and the narration fallback covers a future rename. */
    private static Component reasonOf(DisconnectedScreen screen) {
        try {
            Field f = DisconnectedScreen.class.getDeclaredField("details");
            f.setAccessible(true);
            return ((DisconnectionDetails) f.get(screen)).reason();
        } catch (ReflectiveOperationException e) {
            String narration = screen.getNarrationMessage().getString();
            String title = screen.getTitle().getString();
            return Component.literal(narration.startsWith(title)
                    ? narration.substring(title.length()).replaceFirst("^[.,\\s]+", "") : narration);
        }
    }

    /** The per-channel failure map, channel ids included ON PURPOSE: their namespaces are the modids
     *  the engine's token harvest quarantines by. */
    @SuppressWarnings("unchecked")
    private static String mismatchReason(ModMismatchDisconnectedScreen screen) {
        StringBuilder b = new StringBuilder("Mod channels failed to negotiate with the server.");
        try {
            Field f = ModMismatchDisconnectedScreen.class.getDeclaredField("mismatchedChannelData");
            f.setAccessible(true);
            for (Map.Entry<ResourceLocation, Component> e
                    : ((Map<ResourceLocation, Component>) f.get(screen)).entrySet()) {
                b.append('\n').append(e.getKey()).append(": ").append(e.getValue().getString());
            }
        } catch (ReflectiveOperationException noData) {
            // the screen alone still proves the mismatch; the engine just has less to quarantine by
        }
        return b.toString();
    }

    private static void writeBreadcrumb(Path dir, String reason, String fingerprint) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("server", lastHost);
            o.addProperty("reason", reason);
            o.addProperty("fingerprint", fingerprint);
            o.addProperty("at", System.currentTimeMillis());
            Path p = dir.resolve(".bamfos-updater").resolve("last-disconnect.json");
            Files.createDirectories(p.getParent());
            Files.writeString(p, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[bamfmenu] could not write the kick breadcrumb: {}", e.toString());
        }
    }

    /** The anti-loop read: engine-written history says the last cycle for this exact
     *  fingerprint changed NOTHING -- no quarantine, no version move -- so a restart provably won't
     *  help and the darker screen is the honest one. */
    private static boolean recurredUnchanged(Path dir, String fingerprint) {
        try {
            Path p = dir.resolve(".bamfos-updater").resolve("disconnect-history.json");
            if (!Files.isRegularFile(p)) {
                return false;
            }
            var root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            var entries = root.getAsJsonObject("entries");
            if (entries == null || !entries.has(fingerprint)) {
                return false;
            }
            var e = entries.getAsJsonObject(fingerprint);
            boolean nothingQuarantined = e.has("last_action") && "none".equals(e.get("last_action").getAsString());
            boolean versionChanged = e.has("version_changed") && e.get("version_changed").getAsBoolean();
            return nothingQuarantined && !versionChanged;
        } catch (Exception e) {
            return false; // unreadable history = one extra repairable screen, never a wrong unrepairable
        }
    }

    private static String fingerprint(String reason) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(reason.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                b.append(String.format("%02x", h[i]));
            }
            return b.toString();
        } catch (Exception e) {
            return "fp-unavailable";
        }
    }

    private KickWatch() {
    }
}
