package com.retro.bamfmenu.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Watches for pack-server mismatch kicks, writes last-disconnect.json for the updater, and shows the BSOD. Ignores third-party servers.
public final class KickWatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile String lastHost = "";
    private static boolean demoFired;

    // Armed on connect and disarmed when returning to the original screen so pressing Back doesn't re-trigger the BSOD or overwrite the breadcrumb.
    private static boolean armed = true;

    /** Max nodes to visit when walking a server component tree so bad input can't loop forever. */
    private static final int MAX_DEPTH = 8;

    /** Stashed by ConnectScreenMixin: the config-phase kick has no other address source. */
    public static void connectingTo(String host, int port) {
        lastHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        armed = true;
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        try {
            Screen s = event.getNewScreen();
            if (!armed) {
                return;
            }
            // Offline demo hook: BAMFMENU_BSOD_DEMO=extra|missing|skew|unclassified|platform|terminal|recurrence replaces the first title screen once.
            String demo = System.getenv("BAMFMENU_BSOD_DEMO");
            if (demo != null && !demoFired && s instanceof TitleScreen) {
                demoFired = true;
                LOGGER.info("[bamfmenu] blue screen DEMO up ({})", demo);
                event.setNewScreen(new BsodScreen(Bugcheck.sample(demo), s));
                return;
            }
            String reason = null;
            List<Bugcheck.Failure> failures = List.of();
            boolean mismatch = false;
            if (s instanceof ModMismatchDisconnectedScreen mm) {
                // NeoForge only opens this screen when the server sent per-channel failure data,
                // so it's always a mismatch and the channel IDs give us the culprit mods
                Map<ResourceLocation, Component> data = channelData(mm);
                reason = flatten(data);
                failures = failures(data);
                mismatch = true;
            } else if (s instanceof DisconnectedScreen ds) {
                Component c = reasonOf(ds);
                reason = c == null ? "" : c.getString();
                // collect all translation keys, not just the root: NeoForge builds mandatory data map kicks as
                // translatable siblings under an empty literal root
                List<String> keys = keys(c);
                failures = List.of(new Bugcheck.Failure("", keys));
                mismatch = KickReason.isMismatch(keys, reason);
            }
            if (!mismatch || reason == null || reason.isEmpty() || !fromPackServer()) {
                return;
            }
            Path dir = FMLPaths.GAMEDIR.get();
            String fingerprint = fingerprint(reason);
            boolean recorded = writeBreadcrumb(dir, reason, fingerprint); // raw kick text so the engine can extract culprit mod IDs
            Bugcheck check = Bugcheck.of(failures, reason, recurredUnchanged(dir, fingerprint));
            if (!recorded) {
                // without writing the breadcrumb, restarting won't quarantine mods, record history, or escalate repeats
                check = check.unrecorded();
            }
            LOGGER.info("[bamfmenu] mismatch kick from the pack server, blue screen up ({}): {}",
                    check.stopCode(), fingerprint);
            event.setNewScreen(new BsodScreen(check, s));
        } catch (Throwable t) {
            // a broken interceptor must never eat the vanilla disconnect screen
            LOGGER.warn("[bamfmenu] kick interceptor skipped: {}", t.toString());
        }
    }

    // Restores the original disconnect screen (disarms first so Back doesn't re-enter this interceptor).
    static void restore(Screen back) {
        armed = false;
        Minecraft.getInstance().setScreen(back == null ? new TitleScreen() : back);
    }

    /** True if the kick came from the pack server (properties server_host first, else first saved
     *  server, matching the updater engine). */
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
            // use Minecraft's ServerList parser here; the updater engine parses the same file directly from NBT
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

    /** Per-channel failure map. If the field is missing we return empty, so we still show the mismatch
     *  screen (just unclassified, with less info for the engine to quarantine). */
    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, Component> channelData(ModMismatchDisconnectedScreen screen) {
        try {
            Field f = ModMismatchDisconnectedScreen.class.getDeclaredField("mismatchedChannelData");
            f.setAccessible(true);
            return (Map<ResourceLocation, Component>) f.get(screen);
        } catch (ReflectiveOperationException noData) {
            return Map.of();
        }
    }

    /** Breadcrumb raw text with channel ids included so the engine can match culprit modids by
     *  namespace. */
    private static String flatten(Map<ResourceLocation, Component> data) {
        StringBuilder b = new StringBuilder("Mod channels failed to negotiate with the server.");
        for (Map.Entry<ResourceLocation, Component> e : data.entrySet()) {
            b.append('\n').append(e.getKey()).append(": ").append(e.getValue().getString());
        }
        return b.toString();
    }

    /** Per-channel data for the classifier: the namespace is the mod ID, and the translation keys show
     *  whether the server was missing the channel, had a version mismatch, or rejected the flow. */
    private static List<Bugcheck.Failure> failures(Map<ResourceLocation, Component> data) {
        List<Bugcheck.Failure> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Component> e : data.entrySet()) {
            out.add(new Bugcheck.Failure(e.getKey().getNamespace(), keys(e.getValue())));
        }
        return out;
    }

    // Walks the full component tree for translation keys since NeoForge wraps channel reasons under MOD_WRAPPER.
    private static List<String> keys(Component c) {
        List<String> out = new ArrayList<>();
        collectKeys(c, out, 0);
        return out;
    }

    private static void collectKeys(Component c, List<String> out, int depth) {
        if (c == null || depth > MAX_DEPTH) {
            return;
        }
        if (c.getContents() instanceof TranslatableContents tc) {
            out.add(tc.getKey());
            for (Object arg : tc.getArgs()) {
                if (arg instanceof Component nested) {
                    collectKeys(nested, out, depth + 1);
                }
            }
        }
        for (Component sibling : c.getSiblings()) {
            collectKeys(sibling, out, depth + 1);
        }
    }

    // Returns false if writing last-disconnect.json failed so the BSOD omits the restart prompt.
    private static boolean writeBreadcrumb(Path dir, String reason, String fingerprint) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("server", lastHost);
            o.addProperty("reason", reason);
            o.addProperty("fingerprint", fingerprint);
            o.addProperty("at", System.currentTimeMillis());
            Path p = dir.resolve(".bamfos-updater").resolve("last-disconnect.json");
            Files.createDirectories(p.getParent());
            Files.writeString(p, o.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[bamfmenu] could not write the kick breadcrumb: {}", e.toString());
            return false;
        }
    }

    // Anti-loop check: true if the last recovery boot for this fingerprint changed nothing.
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
            return false; // unreadable history = one more restart asked for, never a wrong escalation
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
