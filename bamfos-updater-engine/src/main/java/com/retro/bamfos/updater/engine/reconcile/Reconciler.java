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

    /** Suffix used when disabling or quarantining a mod jar. */
    static final String DISABLED_SUFFIX = ".disabled";

    public interface Fetcher {
        void fetch(FileEntry entry, Path dest) throws IOException;
    }

    /** Coarse progress callback for the apply phase; per-file events go through the Fetcher wrapper. */
    @FunctionalInterface
    public interface Pulse {
        Pulse NONE = () -> {
        };

        void applying();
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
        return plan(manifest, ledger, false);
    }

    /** repair mode: ignore ledger shortcut and hash-verify EVERY managed file --
     *  the mangled-install fix without reseeding. Pins, bits, seed-once, untracked all still honored. */
    public SyncPlan plan(Manifest manifest, Ledger ledger, boolean repair) throws IOException {
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
                } else if ("servers".equals(e.merge())) {
                    // For merge-servers, leave the player's other entries alone and only insert or refresh
                    // our entry when the seed changed, our entry is missing, or merge_name isn't recorded yet.
                    boolean seedChanged = led == null || !led.sha256.equals(e.sha256());
                    boolean entryGone = !seedChanged && led.mergeName != null
                            && (!disk.exists() || com.retro.bamfos.updater.engine.ping.ServersDat
                                    .entryIp(disk.file(), led.mergeName).isEmpty());
                    if (seedChanged || entryGone || (!seedChanged && led.mergeName == null)) {
                        plan.actions.add(new Action(Kind.MERGE_SERVERS, e.path(), e, false));
                    }
                }
                continue; // Rule D handles seed-once files; rules A-C skip them.
            }

            boolean targetDisabled = disk.exists() ? disk.disabled
                    : led != null ? led.disabled
                    : resolveFreshBit(e, ledger, manifestPaths);

            if (led != null && led.sha256.equals(e.sha256())) {
                if (disk.exists()) {
                    if (repair && !HashUtils.sha256(disk.file()).equals(e.sha256())) {
                        plan.actions.add(new Action(Kind.REPLACE_BAK, e.path(), e, disk.disabled));
                        continue; // During repair, restore mismatched files and keep a .bak copy of the old one.
                    }
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
                List<String> modids;
                try {
                    modids = JarInfo.read(jar).modids();
                } catch (IOException unreadable) {
                    continue; // Skip corrupt untracked jars.
                }
                if (java.util.Collections.disjoint(modids, killed)) {
                    continue;
                }
                // Intersect first so we only warn on jars Q1 actually tried to quarantine, not unrelated .disabled
                // twins. We refuse to overwrite the player's .disabled file and log why since dupeGuard won't.
                if (Files.exists(mods.resolve(jar.getFileName() + DISABLED_SUFFIX))) {
                    System.out.println("[updater] integrity: cannot quarantine " + path
                            + ", a .disabled twin exists");
                    continue;
                }
                plan.actions.add(new Action(Kind.QUARANTINE, path, null, true));
            }
        }
    }

    // ---------------------------------------------------------------- apply

    /**
     * Stages, verifies, runs dupe-modid guard, journals, replays, commits ledger, and deletes the journal.
     * A crash anywhere leaves either untouched disk or a completable local journal.
     */
    public void apply(SyncPlan plan, Manifest manifest, Ledger ledger, Fetcher fetcher) throws IOException {
        apply(plan, manifest, ledger, fetcher, Pulse.NONE, false);
    }

    /**
     * In staged mode we stop after writing the journal (no replay or ledger); next boot's step 0 finishes
     * from staged bytes. Skips MERGE_SERVERS because a running session rewrites servers.dat.
     */
    public void apply(SyncPlan plan, Manifest manifest, Ledger ledger, Fetcher fetcher, Pulse pulse, boolean staged) throws IOException {
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        Map<String, Path> stagedFiles = new HashMap<>();
        int stageIdx = 0;
        for (Action a : plan.downloads()) {
            // Stage one temp file per manifest path, not per hash. Otherwise two identical files at
            // different paths would share a staged file and moving the first one would consume it.
            Path part = staging.resolve(a.entry().sha256() + "-" + (stageIdx++) + ".part");
            fetcher.fetch(a.entry(), part);
            if (!HashUtils.sha256(part).equals(a.entry().sha256())) {
                throw new IOException("staged bytes for " + a.path() + " failed hash verification");
            }
            stagedFiles.put(a.path(), part);
        }

        dupeGuard(plan, manifest, ledger);

        // paths this apply gives up on, so the journal never carries their destructive op and the ledger
        // commit never speaks for them. Filled by the two courtesies that are allowed to fail below
        Set<String> deferred = new HashSet<>();

        // .bak courtesies happen before the journal: a crash after a bak is only litter, never damage
        for (Action a : plan.actions) {
            // Back up edited non-mod files before a rule-E delete (no replacement follows). Skip mods/ so we
            // don't hash and copy large removed jars.
            boolean bak = a.kind() == Kind.REPLACE_BAK
                    || (a.kind() == Kind.DELETE && !a.path().startsWith("mods/"));
            if (bak) {
                Disk d = disk(a.path());
                if (d.exists()) {
                    try {
                        Files.copy(d.file(), dir.resolve(a.path() + ".bak-" + safe(ledger.installedVersion)),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException held) {
                        // Don't let a failed .bak abort the whole sync, but defer this row: continuing would delete or
                        // overwrite the player's edited file with no backup.
                        deferred.add(a.path());
                        System.out.println("[updater] " + a.path()
                                + " left alone, no .bak could be taken, next sync retries");
                    }
                }
            }
        }

        Journal journal = new Journal();
        journal.targetVersion = manifest.pack().version();
        for (Action a : plan.actions) {
            if (deferred.contains(a.path())) {
                continue; // Skip journaling destructive ops if the .bak copy failed.
            }
            String target = a.targetDisabled() ? a.path() + ".disabled" : a.path();
            switch (a.kind()) {
                case INSTALL, REPLACE, REPLACE_BAK, RESTORE, SEED -> {
                    Disk d = disk(a.path());
                    if (d.exists() && !d.file().equals(dir.resolve(target))) {
                        journal.ops.add(new Journal.Op("delete", "", rel(d.file()), "")); // bit flip / stale twin
                    }
                    // Use "seed" (refuses occupied target): in staged mode the player may create this file before next
                    // boot, and refusing lets the next plan preserve or merge it.
                    String kind = a.kind() == Kind.SEED ? "seed" : "move";
                    journal.ops.add(new Journal.Op(kind, rel(stagedFiles.get(a.path())), target, a.entry().sha256()));
                }
                case DELETE -> {
                    Disk d = disk(a.path());
                    if (d.exists()) {
                        journal.ops.add(new Journal.Op("delete", "", rel(d.file()), ""));
                    }
                }
                case QUARANTINE -> {
                    if (staged) {
                        // Skip quarantine in staged mode (like MERGE_SERVERS): replaying without a ledger commit would
                        // lose the fires-once flag, so let next boot's normal plan handle it.
                        continue;
                    }
                    // Single in-tree rename op (never copy+delete, which risks losing the jar if staging vanishes).
                    // Don't backfill quarantined=true from a .disabled name alone; we can't tell it from a player's file.
                    String sha;
                    try {
                        sha = HashUtils.sha256(dir.resolve(a.path()));
                    } catch (IOException unreadable) {
                        // Defer this quarantine if hashing fails (AV lock or deleted since planning) instead of aborting
                        // sync or journaling a bogus sha; Q1 will retry next boot.
                        deferred.add(a.path());
                        System.out.println("[updater] integrity: cannot quarantine " + a.path()
                                + ", unreadable right now, next sync retries");
                        continue;
                    }
                    // "rename", not "move": replay refuses an occupied .disabled name for this kind, because the
                    // twin check above runs in plan and a STUCK quarantine is finished later by step 0 with
                    // no plan running, by which time the player may have parked their own jar there
                    journal.ops.add(new Journal.Op("rename", a.path(), a.path() + DISABLED_SUFFIX, sha));
                }
                case ADOPT, ORPHAN, MERGE_SERVERS -> {
                    // merge is a read-modify-write, not a replayable move; it runs below, atomically
                }
            }
        }
        journal.ops.addAll(0, carryOver(journal.targetVersion, deferred, journal.ops));
        journal.save(Journal.pathIn(dir));
        if (staged) {
            return;
        }
        pulse.applying();
        Journal.Result replayed = journal.replay(dir);
        if (!replayed.retryable().isEmpty()) {
            // pending semantics: the ops survive as the journal for a later step 0. The
            // ledger commit below SKIPS whatever did not land: this rule stops hashing a path once the
            // ledger carries the target sha, so an entry for bytes that never arrived is the one write
            // nothing walks back, the ledger swears the file is current and the old bytes go invisible.
            // The abandoned ones are dropped: no later boot can finish them, and the ledger gate below
            // is what makes the next plan re-derive those rows from scratch
            journal.ops.clear();
            journal.ops.addAll(replayed.retryable());
            journal.save(Journal.pathIn(dir));
        }
        Set<String> unfinished = new HashSet<>();
        for (Journal.Op op : replayed.retryable()) {
            unfinished.add(strip(op.to()));
        }
        for (Journal.Op op : replayed.abandoned()) {
            unfinished.add(strip(op.to()));
        }
        boolean skipped = false;

        for (Action a : plan.actions) {
            if (deferred.contains(a.path())) {
                // FIRST, because a deferred row journaled nothing at all: the replay report below cannot speak
                // for it and landed() would say true, since a deferred REPLACE_BAK left the player's file
                // exactly where it was and a stat at that name sees a regular file. The ledger would then
                // record the NEW sha over their OLD bytes and this rule would stop hashing that path, which is
                // the one write nothing walks back. Both defer sites already printed their own why-line
                skipped = true;
                continue;
            }
            if (unfinished.contains(a.path())) {
                // Gate every unfinished replay path before landed(): stat can't distinguish old bytes from new on
                // overwrites, and a stuck delete must keep its ledger row so it isn't misread as untracked.
                System.out.println("[updater] " + notRecorded(a));
                skipped = true;
                continue;
            }
            boolean placer = switch (a.kind()) {
                case ADOPT, INSTALL, REPLACE, REPLACE_BAK, RESTORE, SEED, QUARANTINE -> true;
                default -> false;
            };
            if (placer && !landed(a)) {
                System.out.println("[updater] " + notRecorded(a));
                skipped = true;
                continue;
            }
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
                    // Record on_remove=orphan now: rule E reads it after the manifest is gone, and existing seeds
                    // emit no action to backfill it later.
                    en.orphanOnRemove = a.entry().onRemove() == FileEntry.OnRemove.ORPHAN;
                    ledger.files.put(a.path(), en);
                }
                case DELETE, ORPHAN -> ledger.files.remove(a.path());
                case QUARANTINE -> {
                    Ledger.Entry en = new Ledger.Entry("");
                    en.quarantined = true;
                    en.disabled = true;
                    en.quarantineReason = "on the pack kill list (removed_modids)";
                    ledger.files.put(a.path(), en);
                }
                case MERGE_SERVERS -> {
                    // Don't fail the ledger commit if servers.dat is locked or the seed is bad since the
                    // other file moves already ran; just skip it and retry on the next sync.
                    try {
                        Ledger.Entry prev = ledger.files.get(a.path());
                        String prior = prev != null ? prev.mergeName : null;
                        String name = com.retro.bamfos.updater.engine.ping.ServersDat.mergeFirstEntry(
                                dir.resolve(a.path()), Files.readAllBytes(stagedFiles.get(a.path())), prior);
                        Ledger.Entry en = new Ledger.Entry(a.entry().sha256());
                        en.seedOnce = true;
                        en.orphanOnRemove = a.entry().onRemove() == FileEntry.OnRemove.ORPHAN;
                        en.mergeName = name;
                        ledger.files.put(a.path(), en);
                    } catch (IOException e) {
                        System.out.println("[updater] servers.dat merge skipped: " + e.getMessage());
                    }
                }
            }
        }
        if (replayed.allLanded() && !skipped) {
            // Only advance installed_version when every row landed: SyncCore.ledgerAgrees trusts it as the
            // pack-is-current shortcut even when bcc-common.toml updated ahead of a stuck sibling.
            ledger.installedVersion = manifest.pack().version();
        }
        ledger.save(Ledger.pathIn(dir));
        if (replayed.retryable().isEmpty()) {
            // Check the in-memory result rather than re-reading the saved journal (which still lists
            // all original ops). Abandoned ops don't keep the journal around since retries won't fix them.
            Files.deleteIfExists(Journal.pathIn(dir));
            pruneStaging(staging);
        }
    }

    /**
     * Dead downloads, swept only where the journal is already gone. Every row that was deferred, abandoned,
     * or cut short by a DupeModidAbort leaves its staged .part behind and NOTHING else ever removes one, so
     * an instance folder grows dead bytes for the life of the box with no line anywhere naming them (* step 6 promises otherwise).
     * IMPORTANT: this is called from the ONE exit that has just proven no journal survives. A pending op's
     * staged bytes are the whole of , the reason replay needs no network, so a sweep that runs while a
     * journal is kept turns a retryable op into an abandoned one and the box stops converging offline.
     */
    private static void pruneStaging(Path staging) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(staging, "*.part")) {
            for (Path part : ds) {
                Files.deleteIfExists(part);
            }
        } catch (IOException litter) {
            // housekeeping, never a reason to fail a sync that already committed its ledger
        }
    }

    /**
     * The ops a step 0 KEPT because they were still stuck, so journal.save cannot quietly destroy them
     *. They go in FRONT of this plan's ops, because replay
     * runs in order and our fresher op for the same name still wins.
     * Three things are left behind. An op this plan RE-DERIVED, because the fresh one owns freshly staged
     * bytes and replaying both would move the file and then report the second one abandoned, gating a
     * ledger row that actually landed. An op for a path this apply DEFERRED, because this run already decided
     * to leave that file alone. And every op at all when the target version moved, which is 's
     * rollback safety: after a rollback the new plan is authoritative and a leftover op would place bytes
     * from the version we just left.
     */
    private List<Journal.Op> carryOver(String targetVersion, Set<String> deferred, List<Journal.Op> fresh) throws IOException {
        Journal leftover = Journal.load(Journal.pathIn(dir));
        if (leftover.ops.isEmpty() || !leftover.targetVersion.equals(targetVersion)) {
            return List.of();
        }
        List<Journal.Op> carried = new ArrayList<>();
        for (Journal.Op kept : leftover.ops) {
            // Match by logical path rather than on-disk name (.jar vs .disabled) so a carried op and a fresh
            // op with a flipped enable bit don't both run and strand a duplicate.
            boolean rederived = fresh.stream()
                    .anyMatch(o -> o.kind().equals(kept.kind()) && strip(o.to()).equals(strip(kept.to())));
            if (!rederived && !deferred.contains(strip(kept.to()))) {
                carried.add(kept);
            }
        }
        return carried;
    }

    /** Maps an on-disk journal op path (possibly ending in .disabled) back to its manifest path. */
    private static String strip(String to) {
        return to.endsWith(DISABLED_SUFFIX) ? to.substring(0, to.length() - DISABLED_SUFFIX.length()) : to;
    }

    /** The honest one-liner for a row this apply is not going to record. Q1's jars get their own wording:
     *  the managed line names a ledger and old bytes an untracked third-party jar does not have. */
    private static String notRecorded(Action a) {
        return a.kind() == Kind.QUARANTINE
                ? a.path() + " could not be disabled, nothing recorded for it, next sync retries"
                : a.path() + " did not land, ledger left on the old bytes for the next boot";
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

    /**
     * Quick stat check for ADOPT and fresh paths. Must stay behind replay's unfinished-path gate, since
     * stat alone can't distinguish overwritten bytes or a player-owned .disabled target from success.
     */
    private boolean landed(Action a) {
        String name = a.targetDisabled() || a.kind() == Kind.QUARANTINE
                ? a.path() + DISABLED_SUFFIX : a.path();
        return Files.isRegularFile(dir.resolve(name));
    }

    private String rel(Path p) {
        return dir.relativize(p).toString().replace('\\', '/');
    }

    private static String safe(String v) {
        return v.isEmpty() ? "unknown" : v.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
