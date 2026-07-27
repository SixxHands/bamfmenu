package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.guard.EngineHealth;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.manifest.ManifestParser;
import com.retro.bamfos.updater.engine.reconcile.PinMatcher;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan;
import com.retro.bamfos.updater.engine.util.HashUtils;

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

/**
 * The sync both doors share: step 0, resolve+reconcile+apply, and the self-divert for the two
 * files a running updater can never replace in place, the shim and the loaded engine. The engine's
 * .new is promoted by the shim before it loads it, and the shim's own .new is swapped by the wrapper
 * SERVER-side. The client shim gets NO promoter, by decision: self-replacing the one
 * thing that fetches updates is too volatile to be worth it, and the shim is deliberately near-static
 * (its own build file says target churn ~never), so a client shim change rides a pack re-ship instead.
 * EngineLoader.promoteStaged is hardcoded to the engine slot and stays that way. So the client stages
 * mods/bamfos-updater-boot.jar.new ONCE and then leaves it alone: a later sync that finds the right bytes
 * already sitting there fetches nothing and only re-prints the waiting line, because the alternative is
 * re-downloading the same asset on every sync for the life of that box. A waiting client shim also never
 * counts as `pending`, because pending is what makes the terminal promise REBOOT TO APPLY and no reboot
 * applies this one. Either way that .new is inert to FML, it only picks up *.jar.
 * Doors differ ONLY in how they pick the tag and side.
 * Everything the boot display or the BIOS later needs is cached beside the ledger on the way
 * through: release notes, the manifest itself, the last-sync result.
 */
final class SyncCore {

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

    /** step 0: finish any crashed apply from LOCAL bytes; stuck ops are re-planned, never poison. */
    static void stepZero(Path dir) throws IOException {
        Journal leftover = Journal.load(Journal.pathIn(dir));
        if (leftover.ops.isEmpty()) {
            return;
        }
        System.out.println("[updater] finishing interrupted apply (" + leftover.ops.size() + " ops)");
        Journal.Result replayed = leftover.replay(dir);
        if (!replayed.abandoned().isEmpty()) {
            // Drop abandoned ops (missing/changed source or already-moved before a crash) so sync below can
            // re-plan them if needed. Word gently since a fully-applied pre-crash journal also lands here.
            System.out.println("[updater] " + replayed.abandoned().size()
                    + " ops could not be re-verified, those paths get re-planned: " + names(replayed.abandoned()));
        }
        if (replayed.retryable().isEmpty()) {
            Files.deleteIfExists(Journal.pathIn(dir));
            return;
        }
        // Keep still-stuck ops in the journal for the next step 0 (and carry them into apply()'s journal)
        // so the sync below doesn't commit a clean ledger while stale files remain.
        System.out.println("[updater] " + replayed.retryable().size()
                + " ops still stuck, journal kept for next boot: " + names(replayed.retryable()));
        leftover.ops.clear();
        leftover.ops.addAll(replayed.retryable());
        leftover.save(Journal.pathIn(dir));
    }

    /** Formats a capped list of filenames from journal ops so stuck-file logs are actionable without flooding. */
    private static String names(List<Journal.Op> ops) {
        List<String> shown = ops.stream().limit(6).map(Journal.Op::to).toList();
        return String.join(", ", shown) + (ops.size() > shown.size() ? ", ..." : "");
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
        bus.syncBegin(cfg.repo(), fromVersion.isEmpty() ? "?" : fromVersion, manifest.pack().version());
        bus.manifest(manifest.files().size());
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
        bus.plan(plan.downloads().size());
        // Log loudly on the wrapper console before downloads start so the server doesn't look hung.
        // Checking versionChanged also catches engine/shim-only bumps absent from plan.downloads().
        boolean serverSide = side == Reconciler.Side.SERVER;
        boolean versionMoved = !fromVersion.equals(manifest.pack().version());
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
        Reconciler.Fetcher feed = (e, dest) -> {
            bus.fileStart(fileName(e.path()));
            gh.fetch(e, dest);
            bus.fileDone(fileName(e.path()));
        };
        rec.apply(plan, filtered, ledger, feed, bus::applying, staged);

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
            Path part = staging.resolve(e.sha256() + ".part");
            bus.fileStart(fileName(e.path()));
            gh.fetch(e, part);
            if (!HashUtils.sha256(part).equals(e.sha256())) {
                throw new IOException(e.path() + " download failed hash verification");
            }
            Files.createDirectories(stagedNew.getParent()); // EngineLoader usually creates this dir, but make sure it exists
            Files.move(part, stagedNew, StandardCopyOption.REPLACE_EXISTING);
            bus.fileDone(fileName(e.path()));
            if (clientShim) {
                shimWaiting(e.path()); // NOT pendingSelf: see the class header, no reboot applies this one
            } else {
                pendingSelf = true;
                System.out.println("[updater] " + e.path() + ".new staged (swapped in before the next run)");
            }
        }

        cacheManifest(dir, manifestJson);
        // apply() mutates this ledger in place and only claims the new version when EVERY row landed, so a
        // mismatch here is the honest witness that something is still pending. The journal alone is not:
        // when every unfinished op is abandoned it gets deleted, and the summary then went into last-sync.json
        // as a flat "updated v1 -> v2" while installed_version was deliberately held at v1, which is the line
        // UpdaterOps.status() shows and the watchdog echoes into logs/latest.log
        boolean heldBack = !ledger.installedVersion.equals(manifest.pack().version());
        boolean pending = staged || pendingSelf || heldBack || Files.exists(Journal.pathIn(dir));
        String was = fromVersion.isEmpty() ? "?" : fromVersion;
        String summary;
        if (plan.isNoop() && !pendingSelf) {
            summary = "already current (" + manifest.pack().version() + ")";
            System.out.println("[updater] pack current (" + manifest.pack().version() + ", "
                    + plan.actions.size() + " actions)");
            if (serverSide) {
                serverLog(dir, "server already current (" + manifest.pack().version() + "), launching");
            }
        } else {
            String now = manifest.pack().version();
            int moved = plan.downloads().size();
            String tail = pending ? ", some apply on next boot" : "";
            // Only use "updated/rolled back" wording when the pack version actually changed; same-version repairs
            // and self-entry-only updates shouldn't claim "UPDATED FROM vX TO vX".
            if (!was.equals(now)) {
                summary = "updated " + was + " -> " + now + " (" + moved + " files)" + tail;
            } else if (moved > 0) {
                summary = "repaired " + moved + " file(s) on " + now + tail;
            } else {
                summary = "staged the updater's own files on " + now + tail;
            }
            if (!was.equals(now) || moved > 0) {
                bus.synced(was, now, pending);
            } else if (pending) {
                bus.pendingOnly();
            }
            if (serverSide) {
                serverLog(dir, "server synced to " + now
                        + (pending ? " (staged files apply on the next boot)" : "") + ", launching");
            } else {
                // the client's own console copy, which BootBus.synced used to print for both sides in the
                // BIOS's voice. latest.log is the file a player pastes when he wants help, so the result has
                // to be in it, and the summary is the same neutral one-liner the durable record keeps
                System.out.println("[updater] " + summary);
            }
        }
        // `pending` and not `ok` for a sync that did not converge: the summary two lines up already says
        // ", some apply on next boot", but UpdaterState.lastSyncCell renders the RESULT WORD only, so the BIOS
        // UPDATE AND REPAIR row read "ok, just now" on every boot of a box the engine itself knows is holding a
        // row back (a player marking a managed jar read only yields AccessDeniedException forever)
        lastSync(dir, manifest.pack().version(), staged ? "staged" : pending ? "pending" : "ok", summary);
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

    /** The doors' way onto the loud channel. sync() already announced "updating server X -> Y" there before
     *  the first byte, and every success path closes with a "..., launching" line, so a refusal answered only
     *  on the quiet channel leaves server-sync.log ending forever on an update that started and never
     *  finished. That is the log the player reads when players report the old version, and reading it as a
     *  hung updater is exactly the conclusion says this state must never produce. */
    static void loud(Path dir, String msg) {
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

    private static void lastSync(Path dir, String tag, String result, String summary) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("at", System.currentTimeMillis());
            root.put("tag", tag);
            root.put("result", result);
            root.put("summary", summary); // the human one-liner the watchdog echoes into logs/latest.log
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
