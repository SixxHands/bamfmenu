package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.jar.JarInfo;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan.Action;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan.Kind;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Sync planner and applier: filters by side and pin globs, plans adds/updates/deletes, stages and journals downloads, then commits the ledger.
public final class Reconciler {

    public interface Fetcher {
        void fetch(FileEntry entry, Path dest) throws IOException;
    }

    public static final class DupeModidAbort extends RuntimeException {
        public DupeModidAbort(String msg) {
            super(msg);
        }
    }

    public enum Side { CLIENT, SERVER }

    private final Path dir;
    private final Side side;
    private final PinMatcher pins;

    public Reconciler(Path instanceDir, Side side, List<String> pinGlobs) {
        this.dir = instanceDir;
        this.side = side;
        this.pins = new PinMatcher(pinGlobs);
    }

    // ---------------------------------------------------------------- plan

    public SyncPlan plan(Manifest manifest, Ledger ledger) throws IOException {
        SyncPlan plan = new SyncPlan();
        Set<String> manifestPaths = new HashSet<>();

        for (FileEntry e : manifest.files()) {
            if (!applies(e.side()) || pins.pinned(e.path())) {
                continue;
            }
            manifestPaths.add(e.path());
            Ledger.Entry led = ledger.files.get(e.path());
            Disk disk = disk(e.path());

            if (e.policy() == FileEntry.Policy.SEED_ONCE) {
                if (led == null && !disk.exists()) {
                    plan.actions.add(new Action(Kind.SEED, e.path(), e, false));
                }
                continue; // Rule D handles seed-once files; rules A-C skip them.
            }

            boolean targetDisabled = disk.exists() ? disk.disabled
                    : led != null ? led.disabled
                    : resolveFreshBit(e, ledger, manifestPaths);

            if (led != null && led.sha256.equals(e.sha256())) {
                if (disk.exists()) {
                    if (disk.disabled != led.disabled) {
                        // User toggled enabled/disabled state; update the ledger via ADOPT without touching disk.
                        plan.actions.add(new Action(Kind.ADOPT, e.path(), e, disk.disabled));
                    }
                    continue; // A: skip, do not hash the disk file
                }
                plan.actions.add(new Action(Kind.RESTORE, e.path(), e, targetDisabled)); // A'
            } else if (disk.exists() && HashUtils.sha256(disk.file()).equals(e.sha256())) {
                plan.actions.add(new Action(Kind.ADOPT, e.path(), e, disk.disabled)); // A2
            } else if (!disk.exists()) {
                plan.actions.add(new Action(Kind.INSTALL, e.path(), e, targetDisabled)); // B, nothing on disk
            } else if (led != null && HashUtils.sha256(disk.file()).equals(led.sha256)) {
                plan.actions.add(new Action(Kind.REPLACE, e.path(), e, targetDisabled)); // B
            } else {
                plan.actions.add(new Action(Kind.REPLACE_BAK, e.path(), e, targetDisabled)); // C
            }
        }

        // this rule: in ledger, absent from the side-filtered pin-masked manifest view
        Set<String> manifestModids = new HashSet<>();
        for (FileEntry e : manifest.files()) {
            if (applies(e.side())) {
                manifestModids.addAll(e.modids());
            }
        }
        for (Map.Entry<String, Ledger.Entry> le : ledger.files.entrySet()) {
            String path = le.getKey();
            if (manifestPaths.contains(path) || pins.pinned(path) || le.getValue().quarantined) {
                continue; // Quarantined entries track third-party jars, so rule E never deletes them.
            }
            boolean orphan = le.getValue().orphanOnRemove;
            if (orphan && path.startsWith("mods/")) {
                // Treat a version rename as an upgrade, not a removal: if the same mod ID still exists
                // under a new manifest path, delete the old jar regardless of on_remove so it doesn't
                // trigger duplicate-mod conflicts.
                Disk old = disk(path);
                if (old.exists()) {
                    try {
                        if (!java.util.Collections.disjoint(JarInfo.read(old.file()).modids(), manifestModids)) {
                            orphan = false;
                        }
                    } catch (IOException unreadable) {
                        // can't prove it's an upgrade -> honor on_remove as recorded
                    }
                }
            }
            plan.actions.add(new Action(orphan ? Kind.ORPHAN : Kind.DELETE, path, null, false));
        }

        // join-guard Q1: untracked ENABLED jars carrying a killed modid
        quarantines(manifest, ledger, manifestPaths, plan);
        return plan;
    }

    /** Fresh install's bit: install_state, unless a rule-E casualty with a shared modid is disabled
     *  on disk -- the bit follows the MOD across version renames. */
    private boolean resolveFreshBit(FileEntry e, Ledger ledger, Set<String> manifestPaths) throws IOException {
        if (!e.path().startsWith("mods/") || e.modids().isEmpty()) {
            return e.installDisabled();
        }
        for (Map.Entry<String, Ledger.Entry> le : ledger.files.entrySet()) {
            if (manifestPaths.contains(le.getKey()) || !le.getKey().startsWith("mods/")) {
                continue; // Only jars removed by rule E can be treated as a predecessor.
            }
            Disk old = disk(le.getKey());
            if (old.exists() && old.disabled) {
                try {
                    if (!java.util.Collections.disjoint(JarInfo.read(old.file()).modids(), e.modids())) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // Can't read the old jar to check its previous state.
                }
            }
        }
        return e.installDisabled();
    }

    private void quarantines(Manifest manifest, Ledger ledger, Set<String> manifestPaths, SyncPlan plan) throws IOException {
        if (manifest.removedModids().isEmpty()) {
            return;
        }
        Path mods = dir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        Set<String> killed = new HashSet<>(manifest.removedModids());
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : ds) {
                String path = "mods/" + jar.getFileName();
                Ledger.Entry led = ledger.files.get(path);
                if (manifestPaths.contains(path) || pins.pinned(path)) {
                    continue;
                }
                if (led != null && led.quarantined) {
                    continue; // Already quarantined once; if the user re-enabled it, leave it alone.
                }
                if (led != null) {
                    continue; // Already managed by the pack, so skip quarantine check.
                }
                try {
                    if (!java.util.Collections.disjoint(JarInfo.read(jar).modids(), killed)) {
                        plan.actions.add(new Action(Kind.QUARANTINE, path, null, true));
                    }
                } catch (IOException unreadable) {
                    // Skip corrupt untracked jars.
                }
            }
        }
    }

    // ---------------------------------------------------------------- apply

    /**
     * Stages, verifies, runs dupe-modid guard, journals, replays, commits ledger, and deletes the journal.
     * A crash anywhere leaves either untouched disk or a completable local journal.
     */
    public void apply(SyncPlan plan, Manifest manifest, Ledger ledger, Fetcher fetcher) throws IOException {
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        Map<String, Path> staged = new HashMap<>();
        for (Action a : plan.downloads()) {
            Path part = staging.resolve(a.entry().sha256() + ".part");
            fetcher.fetch(a.entry(), part);
            if (!HashUtils.sha256(part).equals(a.entry().sha256())) {
                throw new IOException("staged bytes for " + a.path() + " failed hash verification");
            }
            staged.put(a.path(), part);
        }

        dupeGuard(plan, manifest, ledger);

        // .bak courtesies happen before the journal: a crash after a bak is only litter, never damage
        for (Action a : plan.actions) {
            if (a.kind() == Kind.REPLACE_BAK) {
                Disk d = disk(a.path());
                if (d.exists()) {
                    Files.copy(d.file(), dir.resolve(a.path() + ".bak-" + safe(ledger.installedVersion)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        Journal journal = new Journal();
        journal.targetVersion = manifest.pack().version();
        for (Action a : plan.actions) {
            String target = a.targetDisabled() ? a.path() + ".disabled" : a.path();
            switch (a.kind()) {
                case INSTALL, REPLACE, REPLACE_BAK, RESTORE, SEED -> {
                    Disk d = disk(a.path());
                    if (d.exists() && !d.file().equals(dir.resolve(target))) {
                        journal.ops.add(new Journal.Op("delete", "", rel(d.file()), "")); // bit flip / stale twin
                    }
                    journal.ops.add(new Journal.Op("move", rel(staged.get(a.path())), target, a.entry().sha256()));
                }
                case DELETE -> {
                    Disk d = disk(a.path());
                    if (d.exists()) {
                        journal.ops.add(new Journal.Op("delete", "", rel(d.file()), ""));
                    }
                }
                case QUARANTINE -> {
                    // rename via the journal too: copy then delete-old keeps replay idempotent
                    Path jar = dir.resolve(a.path());
                    Path parked = staging.resolve("q-" + jar.getFileName());
                    Files.copy(jar, parked, StandardCopyOption.REPLACE_EXISTING);
                    journal.ops.add(new Journal.Op("move", rel(parked), a.path() + ".disabled", HashUtils.sha256(parked)));
                    journal.ops.add(new Journal.Op("delete", "", a.path(), ""));
                }
                case ADOPT, ORPHAN -> {
                }
            }
        }
        journal.save(Journal.pathIn(dir));
        journal.replay(dir);

        for (Action a : plan.actions) {
            switch (a.kind()) {
                case ADOPT, INSTALL, REPLACE, REPLACE_BAK, RESTORE -> {
                    Ledger.Entry en = new Ledger.Entry(a.entry().sha256());
                    en.disabled = a.targetDisabled();
                    en.orphanOnRemove = a.entry().onRemove() == FileEntry.OnRemove.ORPHAN;
                    ledger.files.put(a.path(), en);
                }
                case SEED -> {
                    Ledger.Entry en = new Ledger.Entry(a.entry().sha256());
                    en.seedOnce = true;
                    ledger.files.put(a.path(), en);
                }
                case DELETE, ORPHAN -> ledger.files.remove(a.path());
                case QUARANTINE -> {
                    Ledger.Entry en = new Ledger.Entry("");
                    en.quarantined = true;
                    en.disabled = true;
                    ledger.files.put(a.path(), en);
                }
            }
        }
        ledger.installedVersion = manifest.pack().version();
        ledger.save(Ledger.pathIn(dir));
        Files.deleteIfExists(Journal.pathIn(dir));
    }

    /** Two ENABLED jars sharing a modid (or two service jars sharing a provider) after this sync
     *  would refuse to boot -- abort BEFORE any move, name the culprit and the two fixes. */
    private void dupeGuard(SyncPlan plan, Manifest manifest, Ledger ledger) throws IOException {
        Map<String, String> owners = new HashMap<>();
        Set<String> replacedPaths = new HashSet<>();
        Set<String> deadPaths = new HashSet<>();
        for (Action a : plan.actions) {
            if (a.entry() != null && a.path().startsWith("mods/")) {
                replacedPaths.add(a.path());
                if (!a.targetDisabled()) {
                    for (String mid : a.entry().modids()) {
                        claim(owners, mid, a.path() + " (incoming)");
                    }
                }
            }
            if (a.kind() == Kind.DELETE || a.kind() == Kind.QUARANTINE) {
                deadPaths.add(a.path());
            }
        }
        Path mods = dir.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path jar : ds) {
                    String path = "mods/" + jar.getFileName();
                    if (replacedPaths.contains(path) || deadPaths.contains(path)) {
                        continue; // post-sync this file is superseded
                    }
                    try {
                        for (String mid : JarInfo.read(jar).modids()) {
                            boolean tracked = ledger.files.containsKey(path);
                            claim(owners, mid, path + (tracked ? "" : " (untracked - delete it or pin= it)"));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static void claim(Map<String, String> owners, String modid, String owner) {
        String prev = owners.put(modid, owner);
        if (prev != null && !prev.equals(owner)) {
            throw new DupeModidAbort("two enabled jars provide modid '" + modid + "': " + prev + " vs " + owner);
        }
    }

    // Disk helpers

    private record Disk(Path enabled, Path disabledPath, boolean disabled, boolean present) {
        boolean exists() {
            return present;
        }

        Path file() {
            return disabled ? disabledPath : enabled;
        }
    }

    private Disk disk(String path) {
        Path en = dir.resolve(path);
        Path dis = dir.resolve(path + ".disabled");
        boolean enX = Files.isRegularFile(en);
        boolean disX = Files.isRegularFile(dis);
        // both names present: the ENABLED file is what loads, it wins as D
        return new Disk(en, dis, !enX && disX, enX || disX);
    }

    private boolean applies(FileEntry.Side s) {
        return s == FileEntry.Side.BOTH
                || (s == FileEntry.Side.CLIENT && side == Side.CLIENT)
                || (s == FileEntry.Side.SERVER && side == Side.SERVER);
    }

    private String rel(Path p) {
        return dir.relativize(p).toString().replace('\\', '/');
    }

    private static String safe(String v) {
        return v.isEmpty() ? "unknown" : v.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
