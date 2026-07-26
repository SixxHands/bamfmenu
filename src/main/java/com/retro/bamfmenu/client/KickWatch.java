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

/**
 * Join-guard Q2's mod half: on a registry/channel-mismatch kick FROM THE PACK'S OWN
 * SERVER, write the one observation file the menu is allowed to write (last-disconnect.json) and
 * replace the disconnect screen with the blue screen. Every actual fix happens in the engine at the
 * NEXT boot, this class renders theater and records one breadcrumb, nothing else.
 * Random third-party servers never trigger this, their kicks keep the vanilla screen.
 */
public final class KickWatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile String lastHost = "";
    private static boolean demoFired;

    /* Armed by a real connect attempt, disarmed the moment the blue screen hands the vanilla screen back.
       Without this the restored mismatch table's Back button opens its parent DisconnectedScreen, whose
       details still carry multiplayer.disconnect.incompatible, so we would intercept a screen nobody was
       kicked to: a second blue screen, and last-disconnect.json rewritten with a reason that names no
       channels, which leaves the engine NOTHING to quarantine. */
    private static boolean armed = true;

    /** A server-supplied component tree walks itself; bound the walk rather than trust the sender. */
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
            /* The offline demo hook: BAMFMENU_BSOD_DEMO=extra|missing|skew|unclassified|platform|terminal|recurrence
               swaps the FIRST title screen for that variant, no server and no kick needed. Fires ONCE per
               session (ESC hands the title back, which must not re-trigger the swap). */
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
                // NeoForge only builds this screen when the server sent per-channel failure data,
                // its existence IS the mismatch signal, and the channel ids name the culprit mods
                Map<ResourceLocation, Component> data = channelData(mm);
                reason = flatten(data);
                failures = failures(data);
                mismatch = true;
            } else if (s instanceof DisconnectedScreen ds) {
                Component c = reasonOf(ds);
                reason = c == null ? "" : c.getString();
                // the whole key chain, not just the root's: NeoForge builds the mandatory-data-map refusal as
                // translatable SIBLINGS of an empty literal root, so the root alone reads as plain text
                List<String> keys = keys(c);
                failures = List.of(new Bugcheck.Failure("", keys));
                mismatch = KickReason.isMismatch(keys, reason);
            }
            if (!mismatch || reason == null || reason.isEmpty() || !fromPackServer()) {
                return;
            }
            Path dir = FMLPaths.GAMEDIR.get();
            String fingerprint = fingerprint(reason);
            boolean recorded = writeBreadcrumb(dir, reason, fingerprint); // RAW reason: the engine harvests culprit modids from it
            Bugcheck check = Bugcheck.of(failures, reason, recurredUnchanged(dir, fingerprint));
            if (!recorded) {
                // the crumb IS the change the CAPS order promises, so with no crumb the order is a lie: no
                // quarantine next boot, no history entry, and the recurrence escalation never arms either
                check = check.unrecorded();
            }
            LOGGER.info("[bamfmenu] mismatch kick from the pack server, blue screen up ({}): {}",
                    check.stopCode(), fingerprint);
            event.setNewScreen(new BsodScreen(check, s));
        } catch (Throwable t) {
            // spirit: a broken interceptor must never eat the vanilla disconnect screen
            LOGGER.warn("[bamfmenu] kick interceptor skipped: {}", t.toString());
        }
    }

    /** Hand back the exact screen the blue screen displaced, the one carrying the channel table and its
     *  own log and mods-folder buttons. Disarms first: re-entering this interceptor from a screen nobody
     *  was kicked to would overwrite the breadcrumb with a reason that names no channels. */
    static void restore(Screen back) {
        armed = false;
        Minecraft.getInstance().setScreen(back == null ? new TitleScreen() : back);
    }

    /** The kick came from OUR server? properties server_host first, else the first saved server,
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

    /** The per-channel failure map. Empty when the field is gone: the screen alone still proves the
     *  mismatch, the engine just has less to quarantine by and the screen reads unclassified. */
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

    /** The breadcrumb's raw text, channel ids included ON PURPOSE: their namespaces are the modids the
     *  engine's token harvest quarantines by. */
    private static String flatten(Map<ResourceLocation, Component> data) {
        StringBuilder b = new StringBuilder("Mod channels failed to negotiate with the server.");
        for (Map.Entry<ResourceLocation, Component> e : data.entrySet()) {
            b.append('\n').append(e.getKey()).append(": ").append(e.getValue().getString());
        }
        return b.toString();
    }

    /** Per-channel evidence for the classifier: the namespace is the modid, and the key chain is what
     *  says whether the server lacked the channel, lacked the version, or refused the flow. */
    private static List<Bugcheck.Failure> failures(Map<ResourceLocation, Component> data) {
        List<Bugcheck.Failure> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Component> e : data.entrySet()) {
            out.add(new Bugcheck.Failure(e.getKey().getNamespace(), keys(e.getValue())));
        }
        return out;
    }

    /** Every translation key in a reason component, outermost first: the classifying key hides under
     *  MOD_WRAPPER whenever ModList could name the mod, and a whole-tree walk beats trusting one arg
     *  index if NeoForge ever wraps it twice. */
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

    /** False = the one file this class is allowed to write did not land (a read-only instance folder, a
     *  synced-folder lock, a full disk), which the screen has to know: the CAPS restart order promises a
     *  change that only this crumb can deliver. */
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

    /** The anti-loop read: engine-written history says the last cycle for this exact
     *  fingerprint changed NOTHING, no quarantine, no version move, so a restart provably will not help
     *  and the screen says so instead of asking for the same restart again. */
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
