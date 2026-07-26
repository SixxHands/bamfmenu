package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.jar.JarInfo;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.reconcile.PinMatcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Consumes the kick breadcrumb on next boot and disables (never deletes) untracked mods named in the kick reason.
public final class KickGuard {

    /** Result of handling a kick breadcrumb, used for history and updater logs. */
    public record Outcome(String fingerprint, List<String> quarantined) {
        public String action() {
            return quarantined.isEmpty() ? "none" : "quarantined " + String.join(", ", quarantined);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,63}");
    private static final Pattern CHANNEL = Pattern.compile("([a-z][a-z0-9_]{1,63}):([a-z0-9_./-]+)");
    /** URLs look like mod channels (scheme:path), and plain-text proxy kicks often include links. */
    private static final Set<String> URL_SCHEMES = Set.of("http", "https", "ftp");
    /** Also matches host:port (with the last hostname label as namespace, e.g. play.example.net:25565
     *  gives `net`); real channel paths are resource paths and are never all digits. */
    private static final Pattern PORT_PATH = Pattern.compile("[0-9]+");

    /** Reads + deletes the breadcrumb; quarantines culprits; saves the ledger. Empty = no crumb. */
    public static java.util.Optional<Outcome> consume(Path dir, List<String> pinGlobs, BootBus bus) throws IOException {
        var crumb = Breadcrumb.consume(dir);
        if (crumb.isEmpty()) {
            return java.util.Optional.empty();
        }
        Set<String> tokens = tokens(crumb.get().reason());
        List<String> hit = new ArrayList<>();
        int matched = 0; // Jars matched by the kick reason, whether or not we could move them.
        Ledger ledger = Ledger.load(Ledger.pathIn(dir));
        PinMatcher pins = new PinMatcher(pinGlobs);
        Path mods = dir.resolve("mods");
        if (Files.isDirectory(mods) && !tokens.isEmpty()) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path jar : ds) {
                    String path = "mods/" + jar.getFileName();
                    if (ledger.files.containsKey(path) || pins.pinned(path)) {
                        continue; // Skip tracked or pinned jars.
                    }
                    List<String> named;
                    try {
                        named = JarInfo.read(jar).modids().stream().filter(tokens::contains).toList();
                    } catch (IOException unreadable) {
                        continue; // Skip corrupt untracked jars.
                    }
                    if (named.isEmpty()) {
                        continue;
                    }
                    matched++;
                    Path disabled = jar.resolveSibling(jar.getFileName() + ".disabled");
                    if (Files.exists(disabled)) {
                        // Don't overwrite an existing .disabled file; let the duplicate guard flag it instead.
                        bus.line("integrity: cannot quarantine " + path + ", a .disabled twin exists");
                        continue;
                    }
                    try {
                        Files.move(jar, disabled);
                    } catch (IOException locked) {
                        // Don't abort the loop if one jar is locked by AV, or earlier renames won't be recorded in the ledger.
                        bus.line("integrity: cannot quarantine " + path + ", file locked, next kick retries");
                        continue;
                    }
                    Ledger.Entry en = new Ledger.Entry("");
                    en.quarantined = true;
                    en.disabled = true;
                    en.quarantineReason = "named in a server kick (" + String.join(", ", named) + ")";
                    ledger.files.put(path, en);
                    hit.add(path);
                    bus.line("integrity: quarantined " + path);
                }
            }
        }
        if (!hit.isEmpty()) {
            ledger.save(Ledger.pathIn(dir));
            // Print the kick reason once for the whole batch instead of repeating it under every jar.
            bus.line("reason: server integrity policy");
        } else if (matched == 0) {
            // Only show this when no jar matched at all; if we matched a jar but couldn't move it, we already
            // printed a separate error for it above.
            bus.line("integrity: kick reason named no untracked modules, nothing quarantined");
        }
        return java.util.Optional.of(new Outcome(crumb.get().fingerprint(), Collections.unmodifiableList(hit)));
    }

    /**
     * Extracts culprit candidates from kick text; channel namespaces win over the whole-word fallback.
     * Strip URL and host:port false namespaces first so they don't shadow a modid named in plain prose.
     */
    static Set<String> tokens(String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        Set<String> channels = new HashSet<>();
        Matcher c = CHANNEL.matcher(lower);
        while (c.find()) {
            String ns = c.group(1);
            if (!URL_SCHEMES.contains(ns) && !PORT_PATH.matcher(c.group(2)).matches()) {
                channels.add(ns);
            }
        }
        if (!channels.isEmpty()) {
            return channels;
        }
        Set<String> out = new HashSet<>();
        Matcher m = TOKEN.matcher(lower);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private KickGuard() {
    }
}
