package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.cargo.CoreCargo;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.console.ConsoleBar;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.guard.EngineHealth;
import com.retro.bamfos.updater.engine.guard.HeldStrikes;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.manifest.ManifestParser;
import com.retro.bamfos.updater.engine.reconcile.PinMatcher;
import com.retro.bamfos.updater.engine.reconcile.Reasons;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan;
import com.retro.bamfos.updater.engine.util.HashUtils;
import com.retro.bamfos.updater.engine.util.TagOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/* Shared sync pipeline for client and server doors: replay any pending journal, reconcile against
 * manifest, and stage self-updates (.new) for the shim and loaded engine. */
public final class SyncCore {

    static final String SHIM_SERVER_PATH = "bamfos-updater-boot.jar";
    static final String SHIM_CLIENT_PATH = "mods/bamfos-updater-boot.jar";
    static final String ENGINE_PATH = ".bamfos-updater/engine/bamfos-updater-engine.jar";
    static final String TOKEN_DEAD_MARKER = "token-dead";

    /** Returns the first managed file from the ledger that is missing on disk, checked before the
     *  up-to-date shortcut so deleted/AV-quarantined jars get restored. Stat-only (no hashing) on boot. */
    static Optional<String> firstMissingManaged(Path dir, List<String> pins) {
        try {
            PinMatcher matcher = new PinMatcher(pins);
            Ledger led = Ledger.load(Ledger.pathIn(dir));
            for (Map.Entry<String, Ledger.Entry> e : led.files.entrySet()) {
                Ledger.Entry en = e.getValue();
                if (en.quarantined || en.seedOnce) {
                    continue; // a quarantine record IS a rename, and this rule never restores a seed
                }
                String path = e.getKey();
                if (matcher.pinned(path)) {
                    continue; // don't restore a pinned file the user deleted
                }
                String onDisk = en.disabled ? path + ".disabled" : path;
                if (!Files.isRegularFile(dir.resolve(onDisk))) {
                    return Optional.of(path);
                }
            }
        } catch (Exception ignored) {
            // Don't force a fetch on an unreadable ledger; the normal sync path handles it.
        }
        return Optional.empty();
    }

    /** Checks whether the ledger's installed_version matches the version bcc-common.toml claims.
     *  bcc can update during a partial apply while other jars remain stale, which would otherwise fool
     *  the up-to-date shortcut. Returns false if the ledger is unreadable. */
    static boolean ledgerAgrees(Path dir, String local) {
        try {
            return Ledger.load(Ledger.pathIn(dir)).installedVersion.equals(local);
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** Headless step 0 for callers without a boot terminal (server and tests). */
    static boolean stepZero(Path dir) throws IOException {
        return stepZero(dir, BootBus.headless(), List.of());
    }

    /** Step 0 with a terminal and no pin mask, kept for the tests that drive the bus directly. */
    static boolean stepZero(Path dir, BootBus bus) throws IOException {
        return stepZero(dir, bus, List.of());
    }

    /** Step 0: replays any leftover journal from local staging and verifies the resulting files on disk.
     *  Returns true if any verified path doesn't match, signaling the caller to skip the up-to-date shortcut. */
    static boolean stepZero(Path dir, BootBus bus, List<String> pins) throws IOException {
        Journal leftover = Journal.load(Journal.pathIn(dir));
        if (leftover.ops.isEmpty()) {
            // delete empty journals instead of skipping them, otherwise anything checking this path treats
            // an ops:[] file as a pending update
            Files.deleteIfExists(Journal.pathIn(dir));
            return false;
        }
        // Drop queued ops for paths pinned after the journal was written, since replay uses REPLACE_EXISTING
        // and would overwrite the pinned file.
        PinMatcher pinGuard = new PinMatcher(pins);
        List<Journal.Op> pinnedOps = leftover.ops.stream()
                .filter(op -> pinGuard.pinned(stripDisabled(op.to()))).toList();
        if (!pinnedOps.isEmpty()) {
            System.out.println("[updater] " + pinnedOps.size()
                    + " staged op(s) target pinned paths, dropped: " + names(pinnedOps));
            leftover.ops.removeAll(pinnedOps);
            if (leftover.ops.isEmpty()) {
                Files.deleteIfExists(Journal.pathIn(dir));
                return false;
            }
        }
        int total = leftover.ops.size();
        int removals = (int) leftover.ops.stream().filter(o -> o.kind().equals("delete")).count();
        System.out.println("[updater] finishing interrupted apply (" + total + " ops)");
        bus.applyBegin(leftover.targetVersion, removals, total - removals);
        // each phase counts itself, because an ordinary journal interleaves the two kinds and one running total
        // would draw a bar that says "installing" over a delete
        int[] removed = {0};
        int[] placed = {0};
        Journal.Result replayed = leftover.replay(dir, (op, done, n) -> {
            if (op.kind().equals("delete")) {
                bus.applyStep("remove", op.to(), ++removed[0], removals);
            } else {
                bus.applyStep("install", op.to(), ++placed[0], total - removals);
            }
        });
        if (!replayed.abandoned().isEmpty()) {
            // Drop abandoned ops (missing/changed source or already-moved before a crash) so sync below can
            // re-plan them if needed. Word gently since a fully-applied pre-crash journal also lands here.
            System.out.println("[updater] " + replayed.abandoned().size()
                    + " ops could not be re-verified, those paths get re-planned: " + names(replayed.abandoned()));
        }
        // Verify every op that returned cleanly (file exists with expected hash, or deleted path is gone).
        // Collect the list before bookkeeping below clears it.
        List<Journal.Op> landed = new ArrayList<>(leftover.ops);
        landed.removeAll(replayed.retryable());
        landed.removeAll(replayed.abandoned());
        List<Journal.Op> wrong = Journal.verify(dir, landed,
                (op, done, n) -> bus.applyStep("verify", op.to(), done, n));
        if (!wrong.isEmpty()) {
            System.out.println("[updater] " + wrong.size() + " of " + landed.size() + " applied paths are not"
                    + " what the journal placed, so this cycle re-derives the whole install: " + names(wrong));
        }
        keepOrDrop(dir, leftover, replayed);
        // `partial` covers files still stuck and files that failed readback. An abandoned op isn't partial
        // by itself (usually a move whose source was already consumed by an earlier replay); verify above
        // distinguishes the two.
        bus.applyEnd(wrong.isEmpty() && replayed.retryable().isEmpty() ? "ok" : "partial");
        return !wrong.isEmpty();
    }

    /** Whether the journal survives this boot: kept for the ops still worth another go, deleted the moment
     *  nothing is left, because an empty journal is a file every later boot reads for nothing. */
    private static void keepOrDrop(Path dir, Journal leftover, Journal.Result replayed) throws IOException {
        if (replayed.retryable().isEmpty()) {
            Files.deleteIfExists(Journal.pathIn(dir));
            return;
        }
        Journal.Stuck stuck = Journal.afterAttempt(replayed.retryable());
        if (!stuck.gaveUp().isEmpty()) {
            // Drop ops that exhausted their retry count (permanently locked/read-only files) so they don't loop
            // forever; the ledger never recorded them, so a later probe or repair can still re-plan them.
            System.out.println("[updater] gave up on " + stuck.gaveUp().size() + " op(s) after "
                    + Journal.MAX_ATTEMPTS + " boots, something else is holding them: " + names(stuck.gaveUp())
                    + " (close any other launcher, then check your antivirus)");
        }
        if (stuck.keep().isEmpty()) {
            Files.deleteIfExists(Journal.pathIn(dir));
            return;
        }
        // Keep still-stuck ops in the journal for the next step 0 (and carry them into apply()'s journal)
        // so the sync below doesn't commit a clean ledger while stale files remain.
        System.out.println("[updater] " + stuck.keep().size()
                + " ops still stuck, journal kept for next boot: " + names(stuck.keep()));
        leftover.ops.clear();
        leftover.ops.addAll(stuck.keep());
        leftover.save(Journal.pathIn(dir));
    }

    /** Journal ops store the on-disk filename, so strip .disabled to match the manifest path before
     *  checking pin globs (same as the reconciler carry-over). */
    private static String stripDisabled(String to) {
        return to.endsWith(".disabled") ? to.substring(0, to.length() - ".disabled".length()) : to;
    }

    /** Formats a capped list of filenames from journal ops so stuck-file logs are actionable without flooding. */
    private static String names(List<Journal.Op> ops) {
        List<String> shown = ops.stream().limit(6).map(Journal.Op::to).toList();
        return String.join(", ", shown) + (ops.size() > shown.size() ? ", ..." : "");
    }

    /** Same capped filename formatter as names(), for given-up strike paths that no longer have journal ops. */
    private static String capped(List<String> paths) {
        List<String> shown = paths.stream().limit(6).toList();
        return String.join(", ", shown) + (paths.size() > shown.size() ? ", ..." : "");
    }

    /** Runs sync from a door; doors always run the promoted engine, so provesEngine=true below. */
    static int sync(Path dir, UpdaterConfig cfg, String tag, boolean repair, Reconciler.Side side,
                    String apiBase) throws Exception {
        String from = Ledger.load(Ledger.pathIn(dir)).installedVersion;
        return sync(dir, cfg, tag, from, repair, false, side, apiBase, BootBus.headless(), true);
    }

    static int sync(Path dir, UpdaterConfig cfg, String tag, String fromVersion, boolean repair,
                    boolean staged, Reconciler.Side side, String apiBase, BootBus bus,
                    boolean provesEngine) throws Exception {
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        GithubReleases gh = new GithubReleases(apiBase, cfg.repo(), cfg.githubToken(), tag, staging);
        String manifestJson = gh.manifestJson();
        Manifest manifest = ManifestParser.parse(manifestJson);
        // Clear engine probation once the manifest parses cleanly (earlier would let an incompatible engine
        // pass; later failures are local disk/lock issues). Only clear when provesEngine is true so
        // /bamfos sync (running the watchdog's jar-in-jar copy) can't disarm rollback for a promoted engine.
        if (provesEngine) {
            EngineHealth.proven(dir);
        }
        clearTokenDead(dir);
        cacheReleaseNotes(dir, gh, manifest.pack().version(), bus);

        List<FileEntry> files = new ArrayList<>();
        List<FileEntry> self = new ArrayList<>();
        for (FileEntry e : manifest.files()) {
            boolean selfEntry = e.path().equals(ENGINE_PATH)
                    || (side == Reconciler.Side.SERVER && e.path().equals(SHIM_SERVER_PATH) && e.side() == FileEntry.Side.SERVER)
                    || (side == Reconciler.Side.CLIENT && e.path().equals(SHIM_CLIENT_PATH));
            if (selfEntry) {
                self.add(e);
            } else {
                files.add(e);
            }
        }
        Manifest filtered = new Manifest(manifest.schemaVersion(), manifest.pack(),
                manifest.requiresRelaunch(), manifest.removedModids(), files);

        Ledger ledger = Ledger.load(Ledger.pathIn(dir));
        Reconciler rec = new Reconciler(dir, side, cfg.pins());
        SyncPlan plan = rec.plan(filtered, ledger, repair);
        boolean versionMoved = !fromVersion.equals(manifest.pack().version());
        // Only emit syncBegin (which opens the Updating BAMFOS block and locks DEL) when files are downloading
        // or the version changed; self-entry fetches emit it below so no-op syncs don't raise an empty block.
        boolean postRaised = !plan.downloads().isEmpty() || versionMoved;
        if (postRaised) {
            bus.syncBegin(cfg.repo(), fromVersion.isEmpty() ? "?" : fromVersion, manifest.pack().version());
            bus.manifest(manifest.files().size());
        }
        bus.plan(plan.downloads().size());
        // Log loudly on the wrapper console before downloads start so the server doesn't look hung.
        // Checking versionChanged also catches engine/shim-only bumps absent from plan.downloads().
        boolean serverSide = side == Reconciler.Side.SERVER;
        if (serverSide && (!plan.downloads().isEmpty() || versionMoved)) {
            String was = fromVersion.isEmpty() ? "?" : fromVersion;
            if (versionMoved) {
                serverLog(dir, "new release " + manifest.pack().version() + " discovered (server on " + was + ")");
                serverLog(dir, "updating server " + was + " -> " + manifest.pack().version()
                        + " (" + plan.downloads().size() + " files) before the server opens");
            } else {
                // Same version with files to fetch is a repair, not a new release, so log it as a repair instead of "new release discovered".
                serverLog(dir, "repairing " + plan.downloads().size() + " file(s) on "
                        + manifest.pack().version() + " before the server opens");
            }
        }
        // Progress updates go to both the client boot bus and the server console bar through the same
        // callback; each output throttles itself.
        ConsoleBar consoleBar = serverSide ? ConsoleBar.forThisProcess() : ConsoleBar.silent();
        GithubReleases.Progress onBytes = (asset, done, total) -> {
            bus.fileProgress(asset, done, total);
            consoleBar.at(asset, done, total);
        };
        /* Map of path -> reason built from the plan before apply runs. Fetcher only sees the manifest entry,
           so we keep the reconciler action reason here for the display. */
        java.util.Map<String, String> why = new java.util.HashMap<>();
        for (SyncPlan.Action a : plan.downloads()) {
            why.put(a.path(), Reasons.of(a.kind(), repair));
        }
        Reconciler.Fetcher feed = (e, dest) -> {
            bus.fileStart(fileName(e.path()), why.getOrDefault(e.path(), ""));
            gh.fetch(e, dest, onBytes);
            consoleBar.finish();
            bus.fileDone(fileName(e.path()));
        };
        Reconciler.Applied applied = rec.apply(plan, filtered, ledger, feed, bus::applying, staged);

        boolean pendingSelf = false;
        List<String> refusedEngines = EngineHealth.rejectedEngines(dir);
        // Respect path pins on self entries too. Skipping staging here also protects run-server.bat, which
        // unconditionally moves bamfos-updater-boot.jar.new into place before the engine even runs.
        PinMatcher selfPins = new PinMatcher(cfg.pins());
        for (FileEntry e : self) {
            Path current = dir.resolve(e.path());
            boolean same = Files.isRegularFile(current) && HashUtils.sha256(current).equals(e.sha256());
            if (same) {
                continue;
            }
            if (selfPins.pinned(e.path())) {
                selfPinned(e.path());
                continue;
            }
            if (e.path().equals(ENGINE_PATH) && refusedEngines.contains(e.sha256())) {
                // Skip staging an engine sha the shim already rejected, otherwise every boot re-downloads it and
                // sets pendingSelf (promising a reboot that applies nothing).
                engineRefused(e.sha256());
                continue;
            }
            Path stagedNew = dir.resolve(e.path() + ".new");
            // Nothing on the client auto-promotes the shim, so skip re-downloading if the existing .new already
            // has the exact manifest bytes.
            boolean clientShim = side == Reconciler.Side.CLIENT && e.path().equals(SHIM_CLIENT_PATH);
            if (clientShim && Files.isRegularFile(stagedNew)
                    && HashUtils.sha256(stagedNew).equals(e.sha256())) {
                shimWaiting(e.path());
                continue;
            }
            if (!postRaised) {
                // self-only updates still download a jar and print REBOOT TO APPLY inside the POST block, so open it here
                bus.syncBegin(cfg.repo(), fromVersion.isEmpty() ? "?" : fromVersion, manifest.pack().version());
                bus.manifest(manifest.files().size());
                postRaised = true;
            }
            Path part = staging.resolve(e.sha256() + ".part");
            bus.fileStart(fileName(e.path()), Reasons.SELF);
            gh.fetch(e, part, onBytes);
            consoleBar.finish();
            if (!HashUtils.sha256(part).equals(e.sha256())) {
                throw new IOException(e.path() + " download failed hash verification");
            }
            Files.createDirectories(stagedNew.getParent()); // EngineLoader usually creates this dir, but make sure it exists
            Files.move(part, stagedNew, StandardCopyOption.REPLACE_EXISTING);
            bus.fileDone(fileName(e.path()));
            if (e.path().equals(ENGINE_PATH)) {
                // extract the core from the staged .new engine right away so the stub picks it up on the next boot
                // instead of needing an extra restart
                CoreCargo.placeFrom(dir, stagedNew);
            }
            if (clientShim) {
                shimWaiting(e.path()); // NOT pendingSelf: see the class header, no reboot applies this one
            } else {
                pendingSelf = true;
                System.out.println("[updater] " + e.path() + ".new staged (swapped in before the next run)");
            }
        }

        cacheManifest(dir, manifestJson);
        // Use apply()'s explicit outcome instead of inferring from version/journal (same-version repairs and
        // deferred/abandoned rows leave neither changed). Keep apply() updating strikes and scrubbing given-up
        // ops before returning so the journal check below doesn't promise REBOOT TO APPLY for dropped rows.
        boolean journalKept = Files.exists(Journal.pathIn(dir));
        // include any active strike (spent or not): once strikes are exhausted, a normal plan skips the
        // rule-A row since the ledger sha matches, so without this check we'd overwrite the record with ok
        int strikeCount = HeldStrikes.currentCount(dir, manifest);
        boolean strikesHeld = strikeCount > 0;
        boolean heldBack = !applied.converged()
                || !ledger.installedVersion.equals(manifest.pack().version())
                || strikesHeld;
        // Only set rebootApplies when a reboot actually applies work (a saved journal or staged self-entry).
        // Don't key off `staged` or `heldBack`: a staged run that deferred every row has no journal to replay.
        boolean rebootApplies = pendingSelf || journalKept;
        boolean pending = staged || pendingSelf || heldBack || journalKept;
        List<String> givenUp = HeldStrikes.exhaustedNow(dir, System.currentTimeMillis());
        if (!givenUp.isEmpty()) {
            // check the strike file directly rather than plan seeding, since a diverged rule-A row isn't in a
            // normal plan at all
            String gaveUp = givenUp.size() + " file(s) held back and given up on: " + capped(givenUp)
                    + " (run the repair pass to retry)";
            if (serverSide) {
                serverLog(dir, gaveUp);
            } else {
                System.out.println("[updater] " + gaveUp);
            }
        }
        String was = fromVersion.isEmpty() ? "?" : fromVersion;
        String summary;
        if (plan.isNoop() && !pendingSelf && !strikesHeld) {
            summary = "already current (" + manifest.pack().version() + ")";
            System.out.println("[updater] pack current (" + manifest.pack().version() + ", "
                    + plan.actions.size() + " actions)");
            if (serverSide) {
                serverLog(dir, "server already current (" + manifest.pack().version() + "), launching");
            }
        } else {
            String now = manifest.pack().version();
            int moved = plan.downloads().size();
            // shortfall is planned files minus applied files; staged runs stop at the journal so shortfall is 0
            int shortfall = staged ? 0 : moved - applied.recorded();
            // use plan shortfall if non-zero, otherwise fall back to active strikes so rule-A divergences don't
            // display as "0 file(s) held back"
            int heldCount = Math.max(staged ? applied.deferred() : shortfall, strikeCount);
            // if we're in a held state with no countable plan shortfall or strike (e.g. a carried op no longer
            // in the plan), still report it as held instead of saying "0 file(s) held back"
            String heldPhrase = heldCount > 0 ? heldCount + " file(s) held back" : "not everything landed";
            // Only append ", some apply on next boot" when a reboot will apply staged changes; held-back rows
            // use separate wording instead.
            String tail = rebootApplies ? ", some apply on next boot"
                    : heldBack ? ", " + heldPhrase
                    : "";
            // Only use "updated/rolled back" wording when the pack version actually changed; same-version repairs
            // and self-entry-only updates shouldn't claim "UPDATED FROM vX TO vX".
            if (!was.equals(now)) {
                // Pick the verb from TagOrder so rollbacks aren't labelled "updated"; unknown/unorderable tags
                // fall back to SIDEWAYS instead of guessing a direction.
                summary = TagOrder.pastVerb(was, now) + " " + was + " -> " + now
                        + " (" + moved + " files)" + tail;
            } else if (moved > 0) {
                // report actual applied count on a shortfall, not the planned count, so logs/latest.log doesn't
                // claim a file was repaired when it wasn't
                summary = shortfall > 0
                        ? "repaired " + applied.recorded() + " of " + moved + " file(s) on " + now
                                + ", " + shortfall + " held back"
                                + (rebootApplies ? ", some apply on next boot" : "")
                        : "repaired " + moved + " file(s) on " + now + tail;
            } else if (plan.isNoop() && strikesHeld && !rebootApplies) {
                // Machine-matched prefix: UpdaterOps.stagedSync maps this to code 7 (held) instead of 6 (done);
                // keep both in sync. Require !rebootApplies so a run that also staged a self-entry falls
                // through to code 0 and lets the reboot apply it (the next sync reports code 7).
                summary = "current with " + heldCount + " file(s) held back (" + now + ")";
            } else {
                summary = "staged the updater's own files on " + now + tail;
            }
            if (!was.equals(now) || moved > 0) {
                // Pass actual applied count for the final summary; use -1 on staged runs (not measured yet) so
                // in-session updates don't show as incomplete with 0 applied.
                bus.synced(was, now, rebootApplies, staged ? -1 : applied.recorded());
            } else if (rebootApplies) {
                bus.pendingOnly();
            }
            if (serverSide) {
                // Gate the "apply on next boot" note on rebootApplies, not pending, so held-only boots don't
                // promise a reboot that won't apply anything.
                serverLog(dir, "server synced to " + now
                        + (rebootApplies ? " (staged files apply on the next boot)"
                                : heldBack ? " (" + heldPhrase + ")" : "")
                        + ", launching");
            } else {
                // the client's own console copy, which BootBus.synced used to print for both sides in the
                // BIOS's voice. latest.log is the file a player pastes when he wants help, so the result has
                // to be in it, and the summary is the same neutral one-liner the durable record keeps
                System.out.println("[updater] " + summary);
            }
        }
        // Write `pending` (not `ok`) whenever work remains, since UpdaterState.lastSyncCell only shows the
        // result word. Set heldFiles only when disk bytes are truly stuck (heldBack or leftover journal on
        // normal runs; deferred rows or active strikes on staged runs, where a journal is expected).
        boolean heldFiles = staged ? applied.deferred() > 0 || strikesHeld : heldBack || journalKept;
        lastSync(dir, manifest.pack().version(), staged ? "staged" : pending ? "pending" : "ok", summary,
                heldFiles);
        return 0;
    }

    /** Logs one line whenever a sync stages a newer client shim, explaining how to apply it since the
     *  client can't self-promote. Kept out of the boot bus since its event set is fixed. */
    private static void shimWaiting(String path) {
        System.out.println("[updater] " + path + ".new is staged and waiting, nothing on the client swaps it in: "
                + "replace the jar by hand or take it with the next pack re-ship");
    }

    /** Logs one line whenever a sync skips a pinned self-entry so the operator can see the pin is active
     *  and knows how to clear it once the bad shim or engine is fixed. */
    private static void selfPinned(String path) {
        System.out.println("[updater] " + path + " is pinned, not taking the release's copy: "
                + "remove it from pin= in .bamfos-updater/bamfos-updater.properties to let updates through");
    }

    /** Logs why a manifest-listed engine was skipped after prior rejections, naming both ways out:
     *  shipping a new engine build or running manual repair. */
    private static void engineRefused(String sha) {
        System.out.println("[updater] engine " + sha.substring(0, Math.min(12, sha.length()))
                + " already failed probation, skipping download (publish a new engine or run BIOS repair to retry)");
    }

    /** Writes a loud line to the wrapper console and server-sync.log so failures close cleanly after
     *  "updating server X -> Y". Public so TroubleReport shares the same prefix and log rotation. */
    public static void loud(Path dir, String msg) {
        serverLog(dir, msg);
    }

    /** Writes a non-converged outcome (offline | failed | no-release) to last-sync.json so BIOS status
     *  and watchdog startup logs don't keep showing a stale "ok" from an earlier boot.
     *  Omits the tag since sync never reached a manifest. */
    static void recordFailure(Path dir, String result, String summary) {
        lastSync(dir, "", result, summary);
    }

    /** Returns true when a failure came from the network (TransportFault or java.net/ssl in the cause
     *  chain) rather than local I/O like a full disk, so both doors distinguish `offline` from `failed`.
     *  Bounds the cause-chain walk to protect the boot path against cyclic causes. */
    static boolean networkFault(Throwable t) {
        Throwable c = t;
        for (int depth = 0; c != null && depth < 8; depth++) {
            if (c instanceof HttpFetcher.TransportFault) {
                return true;
            }
            String pkg = c.getClass().getPackageName();
            if ((pkg.equals("java.net") || pkg.equals("java.net.http") || pkg.equals("javax.net.ssl"))
                    && !badUrlString(c)) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    /** Excludes URISyntaxException and MalformedURLException from networkFault so a typo in the repo
     *  config reports a config error instead of "repository unreachable". */
    private static boolean badUrlString(Throwable c) {
        return c instanceof java.net.URISyntaxException || c instanceof java.net.MalformedURLException;
    }

    // Token death is a persistent state so it never looks like a temporary wifi blip.
    static void tokenDead(Path dir, HttpFetcher.TokenDead dead) {
        System.out.println("[updater] TOKEN DEAD: " + dead.getMessage() + ", updates are disabled until the token is replaced");
        try {
            Path marker = dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, dead.getMessage());
            lastSync(dir, "", "token-dead", "update polling disabled (github token dead)");
        } catch (IOException ignored) {
        }
    }

    static void clearTokenDead(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER));
    }

    /** The notes ARE the notification: headline to the bus now, body cached for the
     *  BIOS what's-new page, zero extra requests, it rode in on the release JSON. */
    private static void cacheReleaseNotes(Path dir, GithubReleases gh, String version, BootBus bus) {
        String body = gh.targetReleaseField("body");
        String name = gh.targetReleaseField("name");
        String headline = firstLine(body);
        if (headline.isEmpty()) {
            headline = name == null ? "" : name;
        }
        if (!headline.isEmpty()) {
            
            bus.note("PACK UPDATE " + version + ", " + headline);
        }
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("tag", version);
            root.put("name", name == null ? "" : name);
            root.put("headline", headline);
            root.put("body", body == null ? "" : body);
            Path p = dir.resolve(".bamfos-updater").resolve("release-notes.json");
            Files.createDirectories(p.getParent());
            Files.writeString(p, JsonWriter.write(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // caching release notes is optional; don't fail sync if it errors
        }
    }

    /** Caches the applied manifest verbatim so UpdaterOps can build the optional-mods page offline
     *  without duplicating category data in the ledger. */
    private static void cacheManifest(Path dir, String manifestJson) {
        try {
            Files.writeString(dir.resolve(".bamfos-updater").resolve("manifest-current.json"),
                    manifestJson, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** Returns the result field from last-sync.json, or "" if missing or unreadable. */
    public static String lastSyncResult(Path dir) {
        return lastSyncField(dir, "result") instanceof String s ? s : "";
    }

    /** Returns whether last-sync.json recorded stuck files (distinct from a normal `pending` staged update
     *  or self-entry, which shouldn't trigger a full re-hash). Defaults to false on older records. */
    public static boolean lastSyncHeldFiles(Path dir) {
        return Boolean.TRUE.equals(lastSyncField(dir, "held"));
    }

    /** Returns true if the last sync left disk state suspect (`failed` or held files) and needs a repair
     *  pass. Pure network errors (`offline`, `limited`, `no-release`, `token-dead`) never touched files. */
    public static boolean needsRepair(String lastResult, boolean heldFiles) {
        return heldFiles || "failed".equals(lastResult);
    }

    private static Object lastSyncField(Path dir, String key) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("last-sync.json");
            if (Files.isRegularFile(f)
                    && MiniJson.parse(Files.readString(f, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                return m.get(key);
            }
        } catch (IOException | RuntimeException unreadable) {
            // treat an unreadable record as unknown (null) rather than a failed sync so callers don't escalate
        }
        return null;
    }

    private static void lastSync(Path dir, String tag, String result, String summary) {
        lastSync(dir, tag, result, summary, false);
    }

    private static void lastSync(Path dir, String tag, String result, String summary, boolean held) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("at", System.currentTimeMillis());
            root.put("tag", tag);
            root.put("result", result);
            root.put("summary", summary); // Short summary line that the watchdog copies into logs/latest.log.
            // Keep file failures separate from self-update staging so diagnostics can tell them apart.
            root.put("held", held);
            Files.writeString(dir.resolve(".bamfos-updater").resolve("last-sync.json"),
                    JsonWriter.write(root), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // catch RuntimeException too since recordFailure runs inside a catch block and shouldn't escape
            // and crash boot
        }
    }

    private static final int SERVER_LOG_MAX_LINES = 200;

    /** Writes a prefixed status line to the server wrapper console and server-sync.log (since the
     *  dedicated server has no boot terminal). The watchdog later echoes the final summary on startup. */
    private static void serverLog(Path dir, String msg) {
        System.out.println("[BAMFOS UPDATER] " + msg);
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("server-sync.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, System.currentTimeMillis() + "  " + msg + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            rollServerLog(f);
        } catch (IOException ignored) {
            // Logging errors must never fail a sync or block boot.
        }
    }

    /** Trims the log to the last SERVER_LOG_MAX_LINES on append to keep disk usage bounded over long
     *  uptimes. Reading 200 lines per append is cheap. */
    private static void rollServerLog(Path f) throws IOException {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        if (lines.size() > SERVER_LOG_MAX_LINES) {
            Files.write(f, lines.subList(lines.size() - SERVER_LOG_MAX_LINES, lines.size()),
                    StandardCharsets.UTF_8);
        }
    }

    private static String firstLine(String body) {
        if (body == null) {
            return "";
        }
        for (String line : body.split("\r?\n")) {
            String s = line.replace("#", "").trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private SyncCore() {
    }
}
