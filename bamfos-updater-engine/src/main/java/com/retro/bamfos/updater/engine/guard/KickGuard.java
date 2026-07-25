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

/**
 * Q2's engine half: consume the mod's kick breadcrumb and quarantine EXACTLY the
 * untracked enabled jars the kick reason names, disable-only, never delete. The evidence test is an
 * intersection, not a parse: harvest every modid-shaped token from the reason text (channel ids like
 * ns:path split into tokens at the colon for free) and match against what untracked jars actually
 * declare -- so ANY reason format works, and a reason that names nothing on disk quarantines
 * nothing. Managed jars are never targets; the reconciler already converges those.
 */
public final class KickGuard {

    /** Result of handling a kick breadcrumb, used for history and updater logs. */
    public record Outcome(String fingerprint, List<String> quarantined) {
        public String action() {
            return quarantined.isEmpty() ? "none" : "quarantined " + String.join(", ", quarantined);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,63}");

    /** Reads + deletes the breadcrumb; quarantines culprits; saves the ledger. Empty = no crumb. */
    public static java.util.Optional<Outcome> consume(Path dir, List<String> pinGlobs, BootBus bus) throws IOException {
        var crumb = Breadcrumb.consume(dir);
        if (crumb.isEmpty()) {
            return java.util.Optional.empty();
        }
        Set<String> tokens = tokens(crumb.get().reason());
        List<String> hit = new ArrayList<>();
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
                    Path disabled = jar.resolveSibling(jar.getFileName() + ".disabled");
                    if (Files.exists(disabled)) {
                        // Don't overwrite an existing .disabled file; let the duplicate guard flag it instead.
                        bus.line("updater: cannot quarantine " + path + " (a .disabled twin exists)");
                        continue;
                    }
                    try {
                        Files.move(jar, disabled);
                    } catch (IOException locked) {
                        // Don't abort the loop if one jar is locked by AV, or earlier renames won't be recorded in the ledger.
                        bus.line("updater: cannot quarantine " + path + " (file locked), next kick retries");
                        continue;
                    }
                    Ledger.Entry en = new Ledger.Entry("");
                    en.quarantined = true;
                    en.disabled = true;
                    en.quarantineReason = "named in a server kick (" + String.join(", ", named) + ")";
                    ledger.files.put(path, en);
                    hit.add(path);
                    bus.line("updater: quarantined " + path + " (named in the server kick)");
                }
            }
        }
        if (!hit.isEmpty()) {
            ledger.save(Ledger.pathIn(dir));
        } else {
            bus.line("updater: kick breadcrumb named no untracked mods, nothing quarantined");
        }
        return java.util.Optional.of(new Outcome(crumb.get().fingerprint(), Collections.unmodifiableList(hit)));
    }

    static Set<String> tokens(String reason) {
        Set<String> out = new HashSet<>();
        Matcher m = TOKEN.matcher(reason);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private KickGuard() {
    }
}
