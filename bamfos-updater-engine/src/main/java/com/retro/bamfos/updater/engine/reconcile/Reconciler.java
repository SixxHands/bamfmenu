package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.factory.DiskSpace;
import com.retro.bamfos.updater.engine.guard.HeldStrikes;
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
import java.util.LinkedHashMap;
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

    /**
     * Initial enabled state for a new mod: carries over the player's enabled/disabled bit from any
     * superseded ledger entry with the same modid, otherwise falls back to install_state.
     */
    private boolean resolveFreshBit(FileEntry e, Ledger ledger, Set<String> manifestPaths) throws IOException {
        if (!e.path().startsWith("mods/") || e.modids().isEmpty()) {
            return e.installDisabled();
        }
        for (Map.Entry<String, Ledger.Entry> le : ledger.files.entrySet()) {
            if (manifestPaths.contains(le.getKey()) || !le.getKey().startsWith("mods/")) {
                continue; // Only jars removed by rule E can be treated as a predecessor.
            }
            Disk old = disk(le.getKey());
            if (old.exists()) {
                try {
                    if (!java.util.Collections.disjoint(JarInfo.read(old.file()).modids(), e.modids())) {
                        return old.disabled; // Keep the player's last enabled/disabled choice instead of the pack default.
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
     * Outcome of one apply(), exported so callers don't guess from version comparisons. `converged` is the
     * exact predicate gating installedVersion; `recorded` counts vouched downloads, `deferred` untouched rows.
     */
    public record Applied(boolean converged, int recorded, int deferred) {
    }

    /**
     * Stages, verifies, runs dupe-modid guard, journals, replays, commits ledger, and deletes the journal.
     * A crash anywhere leaves either untouched disk or a completable local journal.
     */
    public Applied apply(SyncPlan plan, Manifest manifest, Ledger ledger, Fetcher fetcher) throws IOException {
        return apply(plan, manifest, ledger, fetcher, Pulse.NONE, false);
    }

    /**
     * In staged mode we stop after writing the journal (no replay or ledger); next boot's step 0 finishes
     * from staged bytes. Skips MERGE_SERVERS because a running session rewrites servers.dat.
     */
    public Applied apply(SyncPlan plan, Manifest manifest, Ledger ledger, Fetcher fetcher, Pulse pulse, boolean staged) throws IOException {
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        // Paths skipped in this run (already hit the failure limit, or backup failed). We don't
        // download them, journal destructive ops for them, or update their ledger entries.
        Set<String> deferred = new HashSet<>();
        // the strike-seeded subset, kept apart because a seeded row never RAN this boot: there is no new
        // evidence to count against it, and a probe run is exactly the boot where a row is NOT in this set
        Set<String> seeded = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Action a : plan.actions) {
            String key = strikeSha(a);
            if (key == null || !HeldStrikes.exhausted(dir, a.path(), key, a.kind().name(), now)) {
                continue;
            }
            // Since the .bak filename stays the same on a fixed installedVersion, once the blocking
            // file is gone we can retry immediately instead of waiting for the cooldown.
            if (a.kind() == Kind.REPLACE_BAK
                    && !Files.exists(dir.resolve(a.path() + ".bak-" + safe(ledger.installedVersion)))) {
                continue;
            }
            // Seed inside apply instead of masking out of the plan, or installedVersion could commit over
            // unlanded bytes.
            deferred.add(a.path());
            seeded.add(a.path());
            System.out.println("[updater] " + a.path() + " held back, its " + Journal.MAX_ATTEMPTS
                    + " retries are spent: run the repair pass to retry it now, or the next release re-arms it");
        }
        Map<String, Path> stagedFiles = new HashMap<>();
        Map<Action, Path> toFetch = new LinkedHashMap<>();
        int stageIdx = 0;
        int reused = 0;
        long owed = 0;
        for (Action a : plan.downloads()) {
            if (deferred.contains(a.path())) {
                continue; // Skip downloading files we already gave up on.
            }
            // Stage one temp file per manifest path, not per hash. Otherwise two identical files at
            // different paths would share a staged file and moving the first one would consume it.
            Path part = staging.resolve(a.entry().sha256() + "-" + (stageIdx++) + ".part");
            // Resume interrupted downloads: finished .part files survive failed runs (only pruned after a
            // completed apply), so any part matching its target sha skips re-downloading.
            if (Files.isRegularFile(part) && HashUtils.sha256(part).equals(a.entry().sha256())) {
                stagedFiles.put(a.path(), part);
                reused++;
                continue;
            }
            toFetch.put(a, part);
            long missing = a.entry().size();
            Path dl = part.resolveSibling(part.getFileName() + ".dl");
            if (Files.isRegularFile(dl)) {
                missing -= Math.min(Files.size(dl), missing); // the fetcher resumes that tail with Range
            }
            owed += missing;
        }
        if (owed > 0) {
            // Check free space before downloading so we fail cleanly upfront instead of throwing mid-pack
            // and stranding partial downloads.
            DiskSpace.Room room = DiskSpace.check(dir, owed);
            if (room.blocks()) {
                throw new IOException("not enough free space for this update: it needs about "
                        + DiskSpace.mb(room.needBytes()) + " free to stage its downloads and this drive has "
                        + DiskSpace.mb(room.freeBytes()) + ". Nothing was changed.");
            }
        }
        for (Map.Entry<Action, Path> f : toFetch.entrySet()) {
            Action a = f.getKey();
            Path part = f.getValue();
            fetcher.fetch(a.entry(), part);
            if (!HashUtils.sha256(part).equals(a.entry().sha256())) {
                throw new IOException("staged bytes for " + a.path() + " failed hash verification");
            }
            stagedFiles.put(a.path(), part);
        }
        if (reused > 0) {
            System.out.println("[updater] resuming an interrupted sync: " + reused + " of "
                    + plan.downloads().size() + " files were already downloaded and still verify");
        }

        dupeGuard(plan, manifest, ledger, stagedFiles);

        // Copy .bak files before writing the journal so a crash only leaves extra backup files. Must copy
        // instead of rename, otherwise aborting before the journal is written would leave the target missing.
        for (Action a : plan.actions) {
            if (deferred.contains(a.path())) {
                continue; // Don't create a .bak copy for a path we're already skipping.
            }
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
        if (journal.ops.isEmpty()) {
            // Delete any leftover journal.json when there are no ops, otherwise an empty ops:[] file
            // makes later boots think a journal is still pending.
            Files.deleteIfExists(Journal.pathIn(dir));
        } else {
            journal.save(Journal.pathIn(dir));
        }
        if (staged) {
            // Staged runs don't apply changes yet, so report converged=false and 0 applied (callers
            // summarize staged runs from the plan). The journal saved above will apply on reboot.
            return new Applied(false, 0, deferred.size());
        }
        if (!journal.ops.isEmpty()) {
            pulse.applying(); // Only emit the apply phase when there are files to apply.
        }
        Journal.Result replayed = journal.replay(dir);
        if (!replayed.retryable().isEmpty()) {
            // Keep retryable ops in the journal (counting this run as an attempt) and drop abandoned ones.
            // Gate the ledger on the original retryable+abandoned sets so we never record unlanded bytes.
            Journal.Stuck stuck = Journal.afterAttempt(replayed.retryable());
            for (Journal.Op op : stuck.gaveUp()) {
                System.out.println("[updater] " + gaveUpLine(dir, op));
            }
            journal.ops.clear();
            journal.ops.addAll(stuck.keep());
            if (stuck.keep().isEmpty()) {
                Files.deleteIfExists(Journal.pathIn(dir)); // Remove empty journal so the next boot doesn't waste time reading it.
            } else {
                journal.save(Journal.pathIn(dir));
            }
        }
        Set<String> unfinished = new HashSet<>();
        for (Journal.Op op : replayed.retryable()) {
            unfinished.add(strip(op.to()));
        }
        for (Journal.Op op : replayed.abandoned()) {
            unfinished.add(strip(op.to()));
        }
        boolean skipped = false;
        // Derive the download set directly from plan.downloads() so counts stay in sync;
        // refused and committed track failures for strike bookkeeping below.
        Set<String> downloadPaths = new HashSet<>();
        for (Action d : plan.downloads()) {
            downloadPaths.add(d.path());
        }
        int recorded = 0;
        List<Action> refused = new ArrayList<>();
        Set<String> committed = new HashSet<>();

        for (Action a : plan.actions) {
            if (deferred.contains(a.path())) {
                // Skip deferred rows first: they weren't journaled, so landed() would stat the old file and
                // wrongly commit the new sha over old bytes.
                skipped = true;
                refused.add(a);
                continue;
            }
            if (unfinished.contains(a.path())) {
                // Gate every unfinished replay path before landed(): stat can't distinguish old bytes from new on
                // overwrites, and a stuck delete must keep its ledger row so it isn't misread as untracked.
                System.out.println("[updater] " + notRecorded(a));
                skipped = true;
                refused.add(a);
                continue;
            }
            boolean placer = switch (a.kind()) {
                case ADOPT, INSTALL, REPLACE, REPLACE_BAK, RESTORE, SEED, QUARANTINE -> true;
                default -> false;
            };
            if (placer && !landed(a)) {
                System.out.println("[updater] " + notRecorded(a));
                skipped = true;
                refused.add(a);
                continue;
            }
            if (downloadPaths.contains(a.path())) {
                // Count actions that reached commit. MERGE_SERVERS failures are intentionally contained
                // and still counted here.
                recorded++;
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
                    if ("servers".equals(a.entry().merge())) {
                        // Record merge_name immediately so the next sync can check if our server entry exists
                        // instead of re-downloading the data zip to run a merge.
                        en.mergeName = com.retro.bamfos.updater.engine.ping.ServersDat
                                .firstEntryName(dir.resolve(a.path()));
                    }
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
                        committed.add(a.path());
                    } catch (IOException e) {
                        System.out.println("[updater] servers.dat merge skipped: " + e.getMessage());
                    }
                }
            }
            if (a.kind() != Kind.MERGE_SERVERS) {
                committed.add(a.path()); // MERGE_SERVERS adds itself to committed inside the try block; skipped merges aren't committed.
            }
        }
        // Compute convergence once and use it both for the return value and the ledger commit
        // so callers and the ledger never disagree.
        boolean converged = replayed.allLanded() && !skipped;
        if (converged) {
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
        strikes(plan, journal, replayed, refused, seeded, committed, now);
        return new Applied(converged, recorded, deferred.size());
    }

    /**
     * Updates held-file strikes after non-staged applies (skipped when staged so BIOS clicks don't burn
     * boot attempts). Bumps refused rows, clears committed ones, and scrubs given-up ops from the journal.
     */
    private void strikes(SyncPlan plan, Journal journal, Journal.Result replayed, List<Action> refused,
                         Set<String> seeded, Set<String> committed, long now) throws IOException {
        Set<String> crossed = new HashSet<>();
        for (Action a : refused) {
            String key = strikeSha(a);
            if (key == null || seeded.contains(a.path())) {
                continue; // Skip actions without a strike key or that didn't run this boot.
            }
            if (HeldStrikes.bump(dir, a.path(), key, a.kind().name(), now)) {
                crossed.add(a.path());
            }
        }
        // Track failures for carried-over ops that aren't in the current plan. Key them by the
        // journal op kind so they don't collide with planned action kinds.
        Set<String> planPaths = new HashSet<>();
        for (Action a : plan.actions) {
            planPaths.add(a.path());
        }
        List<Journal.Op> unfinishedOps = new ArrayList<>(replayed.retryable());
        unfinishedOps.addAll(replayed.abandoned());
        for (Journal.Op op : unfinishedOps) {
            String path = strip(op.to());
            if (!planPaths.contains(path) && HeldStrikes.bump(dir, path, op.sha256(), op.kind(), now)) {
                crossed.add(path);
            }
        }
        HeldStrikes.clear(dir, committed);
        if (!crossed.isEmpty() && Files.exists(Journal.pathIn(dir))) {
            // Drop abandoned ops from the saved journal before returning, matching how stepZero drops
            // pinned ops, so callers don't think a reboot still has pending work.
            journal.ops.removeIf(op -> crossed.contains(strip(op.to())));
            if (journal.ops.isEmpty()) {
                Files.deleteIfExists(Journal.pathIn(dir));
            } else {
                journal.save(Journal.pathIn(dir));
            }
        }
    }

    /** Returns the hash portion of a strike key: manifest sha256 for downloads, the quarantine tag
     *  for Q1 actions (where entry() is null), or null for actions that aren't strike-tracked. */
    private static String strikeSha(Action a) {
        if (a.entry() != null) {
            return a.entry().sha256();
        }
        return a.kind() == Kind.QUARANTINE ? HeldStrikes.QUARANTINE_SHA : null;
    }

    /**
     * Sweeps dead staged .part/.part.dl files ONLY when no journal survives (pending ops need their
     * staged bytes for offline replay). Leaves FactoryReset's -fr parts alone so resets can resume.
     */
    private static void pruneStaging(Path staging) {
        // Match *.part* (not just *.part) because HttpFetcher downloads into a .part.dl temp file
        // first, so aborted downloads can leave .part.dl files behind.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(staging, "*.part*")) {
            for (Path part : ds) {
                String name = part.getFileName().toString();
                if (name.endsWith(".dl")) {
                    name = name.substring(0, name.length() - 3); // a resume tail lives and dies with its part
                }
                if (com.retro.bamfos.updater.engine.factory.FactoryReset.isResetPart(name)) {
                    continue;
                }
                Files.deleteIfExists(part);
            }
        } catch (IOException litter) {
            // housekeeping, never a reason to fail a sync that already committed its ledger
        }
    }

    /**
     * Prepends still-stuck step-0 ops before this plan's ops so journal.save doesn't drop them. Drops
     * ops re-derived or deferred here, and on a version change drops everything except player renames.
     */
    private List<Journal.Op> carryOver(String targetVersion, Set<String> deferred, List<Journal.Op> fresh) throws IOException {
        Journal leftover = Journal.load(Journal.pathIn(dir));
        if (leftover.ops.isEmpty()) {
            return List.of();
        }
        if (!leftover.targetVersion.equals(targetVersion)) {
            List<Journal.Op> renames = new ArrayList<>();
            int dropped = 0;
            for (Journal.Op kept : leftover.ops) {
                boolean named = fresh.stream().anyMatch(o -> strip(o.to()).equals(strip(kept.to())));
                if (kept.kind().equals("rename") && !named
                        && !pins.pinned(strip(kept.to())) && !deferred.contains(strip(kept.to()))) {
                    // Keep pending enable/disable toggles across version changes; if the new plan targets the
                    // same path, the new op overrides it.
                    renames.add(kept);
                } else {
                    dropped++;
                }
            }
            if (dropped > 0) {
                // Log dropped ops explicitly so there's a trace in the logs if something gets skipped.
                System.out.println("[updater] target moved to " + targetVersion + ", dropping " + dropped
                        + " held-over op(s) from the " + leftover.targetVersion
                        + " journal, the new plan is authoritative");
            }
            return renames;
        }
        List<Journal.Op> carried = new ArrayList<>();
        for (Journal.Op kept : leftover.ops) {
            // Match by logical path rather than on-disk name (.jar vs .disabled) so a carried op and a fresh
            // op with a flipped enable bit don't both run and strand a duplicate.
            int rederived = -1;
            for (int i = 0; i < fresh.size(); i++) {
                if (fresh.get(i).kind().equals(kept.kind()) && strip(fresh.get(i).to()).equals(strip(kept.to()))) {
                    rederived = i;
                    break;
                }
            }
            if (rederived >= 0) {
                // Carry the old op's attempt count into the fresh op, or re-planning every online boot would
                // reset the counter to 0 forever and MAX_ATTEMPTS would never trip.
                Journal.Op o = fresh.get(rederived);
                if (kept.attempts() > o.attempts()) {
                    fresh.set(rederived, new Journal.Op(o.kind(), o.from(), o.to(), o.sha256(), kept.attempts()));
                }
                continue;
            }
            if (pins.pinned(strip(kept.to()))) {
                // Drop carried ops for paths pinned since they were journaled so replay doesn't overwrite the
                // pinned file; log the drop.
                System.out.println("[updater] " + strip(kept.to())
                        + " is pinned now, dropping the held-over op for it");
                continue;
            }
            if (!deferred.contains(strip(kept.to()))) {
                carried.add(kept);
            }
        }
        return carried;
    }

    /** Maps an on-disk journal op path (possibly ending in .disabled) back to its manifest path. */
    private static String strip(String to) {
        return to.endsWith(DISABLED_SUFFIX) ? to.substring(0, to.length() - DISABLED_SUFFIX.length()) : to;
    }

    /** The give-up one-liner, with the old-bytes claim made only when a stat backs it: replay's swap never
     *  unlinks an occupied target anymore, but a stuck fresh INSTALL never had old bytes to keep, and
     *  promising a file that is not there sends the player hunting for a mod that never existed. */
    static String gaveUpLine(Path dir, Journal.Op op) {
        return "gave up on " + op.to() + " after " + Journal.MAX_ATTEMPTS + " tries, "
                + (Files.exists(dir.resolve(op.to()))
                        ? "it stays on its old bytes and the next sync re-plans it"
                        : "nothing is at that path and the next sync re-plans it");
    }

    /** Formats the skip message for a file that wasn't updated. Uses separate wording for Q1
     *  untracked jars (no ledger entry), and checks disk before claiming the old file is intact. */
    String notRecorded(Action a) {
        if (a.kind() == Kind.QUARANTINE) {
            return a.path() + " could not be disabled, nothing recorded for it, next sync retries";
        }
        return disk(a.path()).exists()
                ? a.path() + " did not land, ledger left on the old bytes for the next boot"
                : a.path() + " did not land and nothing is on disk there, the next sync retries it";
    }

    /**
     * Aborts before moving anything if two enabled jars share a modid or two service jars share a
     * provider (checked on staged bytes; service-only so normal mods sharing mixin SPIs don't false-trip).
     */
    private void dupeGuard(SyncPlan plan, Manifest manifest, Ledger ledger, Map<String, Path> stagedFiles)
            throws IOException {
        Map<String, String> owners = new HashMap<>();
        Set<String> replacedPaths = new HashSet<>();
        Set<String> deadPaths = new HashSet<>();
        for (Action a : plan.actions) {
            if (a.entry() != null && a.path().startsWith("mods/")) {
                replacedPaths.add(a.path());
                if (!a.targetDisabled()) {
                    for (String mid : a.entry().modids()) {
                        claim(owners, "modid", mid, a.path() + " (incoming)");
                    }
                    Path staged = stagedFiles.get(a.path());
                    if (staged != null) {
                        try {
                            JarInfo.Identity id = JarInfo.read(staged);
                            if (id.isServiceJar()) {
                                for (String svc : id.services()) {
                                    claim(owners, "service", svc, a.path() + " (incoming)");
                                }
                            }
                        } catch (IOException ignored) {
                            // Unreadable staged jars will fail hash verification elsewhere, so skip them here.
                        }
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
                        JarInfo.Identity id = JarInfo.read(jar);
                        boolean tracked = ledger.files.containsKey(path);
                        String owner = path + (tracked ? "" : " (untracked - delete it or pin= it)");
                        for (String mid : id.modids()) {
                            claim(owners, "modid", mid, owner);
                        }
                        if (id.isServiceJar()) {
                            for (String svc : id.services()) {
                                claim(owners, "service", svc, owner);
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static void claim(Map<String, String> owners, String what, String id, String owner) {
        String prev = owners.put(what + " '" + id + "'", owner);
        if (prev != null && !prev.equals(owner)) {
            throw new DupeModidAbort("two enabled jars provide " + what + " '" + id + "': " + prev + " vs " + owner);
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
