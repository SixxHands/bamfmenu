package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.jar.JarInfo;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Re-derives an initial ledger from matching on-disk files on a fresh install so renamed mods can be retired cleanly without tripping duplicate-mod checks.
public final class LedgerBootstrap {

    private LedgerBootstrap() {
    }

    /**
     * Returns the loaded ledger if non-empty, else derives one from disk. Not saved here; apply() commits
     * atomically so an abort re-derives cleanly next boot.
     */
    public static Ledger ensure(Path dir, Ledger loaded, Manifest sideView, Reconciler.Side side,
                                PinMatcher pins) throws IOException {
        if (!loaded.files.isEmpty()) {
            return loaded; // Already have a ledger from a previous sync, so don't rebuild it.
        }
        // Collect all mod IDs provided by the pack on this side, including bundled ones (like
        // immersive portals inside bamfnether and derelict inside bamfcat).
        Set<String> provided = new HashSet<>();
        Set<String> optionalIds = new HashSet<>();
        for (FileEntry e : sideView.files()) {
            if (!applies(e.side(), side)) {
                continue;
            }
            for (String id : e.modids()) {
                provided.add(id);
                if (e.onRemove() == FileEntry.OnRemove.ORPHAN) {
                    optionalIds.add(id);
                }
            }
        }

        Ledger derived = new Ledger();
        derived.packId = loaded.packId;
        // Leave installed_version empty: only apply() sets it after every row lands, so an unconverged
        // install won't skip repair (Doors falls back to bcc when empty).

        Path mods = dir.resolve("mods");
        if (Files.isDirectory(mods)) {
            // *.jar* also matches .disabled jars (the reconciler only checks known paths). Ignore temp files
            // like .part, .new, and .bak below.
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar*")) {
                for (Path jar : ds) {
                    String name = jar.getFileName().toString();
                    boolean disabled;
                    String enabledName;
                    if (name.endsWith(".jar")) {
                        disabled = false;
                        enabledName = name;
                    } else if (name.endsWith(".jar.disabled")) {
                        disabled = true;
                        enabledName = name.substring(0, name.length() - ".disabled".length());
                    } else {
                        continue;
                    }
                    // Ledger keys always use the enabled manifest path, with disabled stored as a flag.
                    String key = "mods/" + enabledName;
                    if (pins.pinned(key)) {
                        continue; // a pinned jar is the player's to hold, never our row and never this rules
                    }
                    List<String> ids;
                    try {
                        ids = JarInfo.read(jar).modids();
                    } catch (IOException unreadable) {
                        continue; // Not confirmed as a pack mod, so leave it untracked.
                    }
                    if (ids.isEmpty() || !provided.containsAll(ids)) {
                        // Only claim a jar if every modid it declares is ours; any third-party modid means we leave it alone.
                        continue;
                    }
                    Ledger.Entry en = new Ledger.Entry(HashUtils.sha256(jar));
                    en.disabled = disabled;
                    en.orphanOnRemove = !java.util.Collections.disjoint(ids, optionalIds);
                    derived.files.put(key, en);
                }
            }
        }

        if (derived.files.isEmpty()) {
            return loaded; // Nothing from the pack on disk yet; treat as a fresh install.
        }
        // Don't save here. Let apply() commit the ledger atomically so a mid-sync failure leaves
        // no partial ledger on disk and we re-derive it on the next boot.
        return derived;
    }

    private static boolean applies(FileEntry.Side s, Reconciler.Side side) {
        return s == FileEntry.Side.BOTH
                || (s == FileEntry.Side.CLIENT && side == Reconciler.Side.CLIENT)
                || (s == FileEntry.Side.SERVER && side == Reconciler.Side.SERVER);
    }
}
