package com.retro.bamfmenu.client;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a disconnect reason is a pack mismatch at all: the front door to the blue screen AND to the
 * breadcrumb the engine acts on. No Minecraft imports on purpose, same rule as Bugcheck, because this gate arms a
 * destructive next-boot quarantine and it has to be unit-testable.
 * IMPORTANT: the plain-text branch demands a channel id, NEVER a keyword. An operator typing
 * `/kick Jordy restarting to fix a registry mismatch` used to pass on the word `registry`, which wrote the
 * breadcrumb, and the engine's whole-word sweep then disables ANY untracked jar whose modid is a word in that
 * sentence (KickGuard.tokens has no channel to narrow by). The keyed branch already covers every reason
 * NeoForge and vanilla actually send, so the narrow text gate costs no real coverage.
 */
final class KickReason {

    /** A channel id, the shape NeoForge's own channel dump carries and the only plain-text evidence that
     *  names something on disk. */
    private static final Pattern CHANNEL = Pattern.compile("([a-z][a-z0-9_]{1,63}):([a-z0-9_./-]+)");
    /** A url matches the channel shape with its scheme as the namespace, and a proxy kick routinely carries a
     *  link. Same two false shapes the engine's harvest drops (KickGuard), for the same reason. */
    private static final Set<String> URL_SCHEMES = Set.of("http", "https", "ftp");
    /** `play.example.net:25565` matches too, with the last host label as the namespace. A real channel path is
     *  a resource path and is NEVER all digits. */
    private static final Pattern PORT_PATH = Pattern.compile("[0-9]+");

    /** The keys the reason carried, outermost first, plus its rendered text for the literal case. */
    static boolean isMismatch(List<String> keys, String text) {
        for (String key : keys) {
            if (mismatchKey(key)) {
                return true;
            }
        }
        // a reason that carried ANY key was built by code we can read, so its keys are the whole answer and
        // its prose gets no second vote: only a literal kick falls through to the text
        return keys.isEmpty() && namesAChannel(text);
    }

    /** The reasons NeoForge and vanilla build for incompatible sets. Ordinary kicks (ban, timeout, shutdown,
     *  whitelist) carry their own keys and stay on the vanilla screen. */
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
