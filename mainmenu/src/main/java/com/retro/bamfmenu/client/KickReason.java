package com.retro.bamfmenu.client;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks whether a disconnect reason is a pack mismatch (gates the blue screen and the updater breadcrumb).
 * Kept free of Minecraft imports so we can unit-test every case.
 * IMPORTANT: the plain-text branch demands a channel id, NEVER a keyword. An operator typing
 * `/kick Jordy restarting to fix a registry mismatch` used to pass on the word `registry`, which wrote the
 * breadcrumb, and the engine's whole-word sweep then disables ANY untracked jar whose modid is a word in that
 * sentence (KickGuard.tokens has no channel to narrow by). The keyed branch already covers every reason
 * NeoForge and vanilla actually send, so the narrow text gate costs no real coverage.
 */
final class KickReason {

    /** Matches NeoForge channel IDs in plain-text kick messages so we can extract mod namespaces. */
    private static final Pattern CHANNEL = Pattern.compile("([a-z][a-z0-9_]{1,63}):([a-z0-9_./-]+)");
    /** Exclude URLs since their scheme looks like a channel namespace (matches KickGuard in the engine). */
    private static final Set<String> URL_SCHEMES = Set.of("http", "https", "ftp");
    /** Exclude host:port strings like `play.example.net:25565`, since channel paths aren't all digits. */
    private static final Pattern PORT_PATH = Pattern.compile("[0-9]+");

    /** Translation keys from the kick component (outermost first) plus rendered text for plain-text kicks. */
    static boolean isMismatch(List<String> keys, String text) {
        for (String key : keys) {
            if (mismatchKey(key)) {
                return true;
            }
        }
        // if the kick has translation keys, rely on those alone; only inspect raw text for plain-text kicks
        return keys.isEmpty() && namesAChannel(text);
    }

    /** Translation keys for mod/protocol mismatch kicks; normal kicks (ban, timeout, shutdown, whitelist)
     *  stay on the vanilla disconnect screen. */
    private static boolean mismatchKey(String key) {
        return key.startsWith("neoforge.network.")
                || key.equals("multiplayer.disconnect.incompatible")
                || key.equals("multiplayer.disconnect.outdated_client")
                || key.equals("multiplayer.disconnect.outdated_server")
                || key.equals("multiplayer.disconnect.missing_tags");
    }

    static boolean namesAChannel(String text) {
        Matcher m = CHANNEL.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (!URL_SCHEMES.contains(m.group(1)) && !PORT_PATH.matcher(m.group(2)).matches()) {
                return true;
            }
        }
        return false;
    }

    private KickReason() {
    }
}
