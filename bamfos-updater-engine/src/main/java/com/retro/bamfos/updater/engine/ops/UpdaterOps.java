package com.retro.bamfos.updater.engine.ops;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.door.Doors;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.guard.EngineHealth;
import com.retro.bamfos.updater.engine.factory.FactoryReset;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.manifest.ManifestParser;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.ping.Nbt;
import com.retro.bamfos.updater.engine.ping.ServersDat;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The BIOS-facing surface. The BIOS is a view, THIS is the single writer, menu code
 * never touches manifests, ledgers, or files, it calls here. The server's /bamfos op commands share
 * it (serverSyncNow, clearTokenDead, status), because by then the jars are locked on BOTH sides. By menu
 * time jars are loaded and locked, so every sync that moves anything is STAGED: journal written,
 * applies next boot. Return codes over exceptions because callers sit across a
 * reflective boundary: 0 ok/staged, 1 no target to sync to (nothing resolved locally, or github 404s
 * the target tag, because to a caller those are one thing: there is nothing to converge to), 2 failed,
 * 3 another sync holds the lock, 4 token dead,
 * 5 github rate limited, try again later. 5 has its own code so the repair page cannot render a
 * throttle as "Sync failed. See the log.", because a limit clears itself inside the hour and is not a
 * failure. 6 is the box already matching the target: NOTHING was staged, so the page can say done
 * instead of promising a next boot that changes nothing (client-side ops only, stagedSync says why).
 * unpin() answers in that same space: 0 the pin is gone, 1 nothing was pinned so nothing
 * changed, 2 the file could not be written. listReleases() answers in it too but INSIDE its map,
 * under "code", because the picker needs the rows and the reason in one call: 0 the list is live, 2
 * offline, 4 token dead, 5 rate limited, and every non-zero one still ships the installed version as
 * its only row. The page strings for each code live in , not here, so the BIOS implementer
 * reads them off the source of truth instead of a javadoc.
 */
public final class UpdaterOps {

    private final Path dir;
    private final String apiBase;

    public UpdaterOps(Path dir, String apiBase) {
        this.dir = dir;
        this.apiBase = apiBase;
    }

    /** Returns BIOS status fields: version, pin, token state, last sync, and counts. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Ledger ledger = Ledger.load(Ledger.pathIn(dir));
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            out.put("version", Doors.localVersion(dir).orElse(ledger.installedVersion));
            out.put("pinned", cfg.tagPin());
            out.put("reset_target", pinnedOrInstalled(cfg, ledger));
            out.put("token_dead", Doors.tokenDead(dir));
            // Server address pinged for version broadcasts so the BIOS can display it; empty is normal
            // (falls back to servers.dat).
            out.put("server_host", cfg.serverHost());
            out.put("server_port", cfg.serverPort());
            long disabled = ledger.files.values().stream().filter(e -> e.disabled && !e.quarantined).count();
            long quarantined = ledger.files.values().stream().filter(e -> e.quarantined).count();
            out.put("disabled_count", disabled);
            out.put("quarantined_count", quarantined);
            Path last = dir.resolve(".bamfos-updater").resolve("last-sync.json");
            if (Files.isRegularFile(last) && MiniJson.parse(Files.readString(last, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                out.put("last_sync_at", m.get("at") instanceof Long l ? l : 0L);
                out.put("last_sync_result", m.get("result") instanceof String s ? s : "");
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e));
        }
        return out;
    }

    /**
     * Target version for repair and factoryReset: pin if set, else the ledger's converged version
     * (never bcc's stamp). Shared with the BIOS so its reset label always matches what actually runs.
     */
    static String pinnedOrInstalled(UpdaterConfig cfg, Ledger ledger) {
        return !cfg.tagPin().isEmpty() ? cfg.tagPin() : ledger.installedVersion;
    }

    /**
     * True if this looks like a server dir (has server.properties). Guards factoryReset, which always
     * passes Side.CLIENT, from ever running on a server via OpsGateway and wiping server-only mods.
     */
    static boolean looksLikeServer(Path dir) {
        return Files.isRegularFile(dir.resolve("server.properties"));
    }

    /**
     * Catches synced-but-never-run servers (no server.properties yet) by finding any ledger path only
     * shipped by side:server manifest rows. Joins by side reach rather than path or hash, because a few
     * configs and jars legitimately ship on both client and server rows with identical bytes.
     */
    static String serverOnlyFileOnDisk(Manifest manifest, Ledger led) {
        Set<String> clientReach = new HashSet<>();
        for (FileEntry e : manifest.files()) {
            if (e.side() != FileEntry.Side.SERVER) {
                clientReach.add(e.path());
            }
        }
        for (FileEntry e : manifest.files()) {
            if (e.side() == FileEntry.Side.SERVER && !clientReach.contains(e.path())
                    && led.files.containsKey(e.path())) {
                return e.path();
            }
        }
        return null;
    }

    /** Stages a sync to the pinned version, or the version currently broadcast by the server. */
    public int syncNow() {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            String tag = !cfg.tagPin().isEmpty() ? cfg.tagPin()
                    : Doors.broadcast(dir, cfg).orElse("");
            if (tag.isEmpty()) {
                return 1;
            }
            return stagedSync(cfg, tag, false, false);
        } catch (IOException e) {
            return 2;
        }
    }

    /**
     * Server sync (/bamfos sync). Uses the pin if set, else "latest" (no ping), matching the pre-JVM
     * server door so the next boot doesn't undo what this staged.
     */
    public int serverSyncNow() {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            return stagedSync(cfg, cfg.tagPin().isEmpty() ? "latest" : cfg.tagPin(), false, true);
        } catch (IOException e) {
            return 2;
        }
    }

    /**
     * Server repair (/bamfos repair). Audits the SERVER file set against the installed version (never
     * "latest", so repair won't upgrade) and skips engine probation since it runs from the watchdog jar.
     */
    public int serverRepair() {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            String tag = pinnedOrInstalled(cfg, Ledger.load(Ledger.pathIn(dir)));
            if (tag.isEmpty()) {
                return 1; // never synced, so there is no manifest to audit against and nothing to repair to
            }
            EngineHealth.forgiveRejectedEngines(dir); // Same engine-rejection reset as BIOS repair; see repair().
            return stagedSync(cfg, tag, true, true);
        } catch (IOException e) {
            return 2;
        }
    }

    /**
     * Arms a factory reset against the installed version (never latest): stages the pack and journals
     * wipe+replace for next boot's step 0. Returns a map instead of a code so the BIOS gets real disk
     * space and file-count numbers.
     */
    public Map<String, Object> factoryReset() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 2L);
        lastBusFile = null;
        Optional<InstanceLock> lock;
        try {
            lock = InstanceLock.acquire(dir);
        } catch (IOException e) {
            return said(out, 2, "could not take the updater lock, so nothing was changed.");
        }
        if (lock.isEmpty()) {
            return said(out, 3, "another sync is running, so the reset did not start. Try again in a moment.");
        }
        try (InstanceLock ignored = lock.get()) {
            if (looksLikeServer(dir)) {
                return said(out, 6, "this is a server directory, and a factory reset only knows how to rebuild "
                        + "a client. Nothing was changed. Use /bamfos sync, or delete the instance and let the "
                        + "wrapper rebuild it.");
            }
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            String tag = pinnedOrInstalled(cfg, Ledger.load(Ledger.pathIn(dir)));
            if (tag.isEmpty()) {
                return said(out, 1, "this install has never completed a sync, so there is no release to reset "
                        + "to. Sync first.");
            }
            Path staging = dir.resolve(".bamfos-updater").resolve("staging");
            Files.createDirectories(staging);
            GithubReleases gh = new GithubReleases(apiBase, cfg.repo(), cfg.githubToken(), tag, staging);
            Manifest manifest = ManifestParser.parse(gh.manifestJson());
            String serverFile = serverOnlyFileOnDisk(manifest, Ledger.load(Ledger.pathIn(dir)));
            if (serverFile != null) {
                return said(out, 6, "this install holds server files (" + serverFile + "), so it is a server "
                        + "and a factory reset only knows how to rebuild a client. Nothing was changed.");
            }
            FactoryReset.Armed armed = FactoryReset.arm(dir, manifest, Reconciler.Side.CLIENT, gh::fetch, tag,
                    (path, done, total) -> bus(path, done, total));
            out.put("deletes", (long) armed.deletes());
            out.put("places", (long) armed.places());
            out.put("bytes", armed.payloadBytes());
            out.put("tag", armed.tag());
            out.put("reused", (long) armed.reused());
            // On a resumed reset, only count files downloaded in this run instead of reporting the full manifest
            // total when most files were already cached.
            String got = armed.reused() > 0
                    ? armed.places() + " files ready (" + armed.reused() + " already downloaded before)"
                    : armed.places() + " files downloaded";
            return said(out, 0, "reset armed for " + tag + ": " + got + " and "
                    + armed.deletes() + " to remove. Nothing has changed yet.");
        } catch (FactoryReset.Refused refused) {
            // Separate exit code for refusals vs failures: a refusal leaves the install untouched.
            return said(out, 6, refused.why);
        } catch (HttpFetcher.TokenDead dead) {
            return said(out, 4, "github refused the token, so nothing was downloaded and nothing changed.");
        } catch (HttpFetcher.RateLimited limited) {
            return said(out, 5, "github is rate limiting this box, so the reset could not download. It clears "
                    + "itself, try again later. Nothing was changed.");
        } catch (Exception e) {
            return said(out, 2, "the reset did not finish and nothing was changed: " + e);
        } finally {
            // Close the last FILE line on every exit so an aborted reset doesn't leave a stale progress line
            // stuck on the BIOS bus for the rest of the session.
            if (lastBusFile != null) {
                try {
                    BootBus.append(dir).fileDone(lastBusFile);
                } catch (RuntimeException | Error ignored) {
                    // same rule as bus(): a progress line is never worth a failed reset
                }
                lastBusFile = null;
            }
        }
    }

    /** Last FILE name written to the bus by factoryReset, so its finally block can close it on exit. */
    private String lastBusFile;

    /** Reports download progress to the bus so the boot terminal can render a progress bar. */
    private void bus(String path, int done, int total) {
        try {
            // Use append(), not at(): the BIOS feed is already tailing this file from a live offset, so truncating
            // it would stop the UI from seeing new progress lines.
            String name = path.substring(path.lastIndexOf('/') + 1);
            BootBus.append(dir).fileStart(name, "factory reset " + done + "/" + total);
            lastBusFile = name;
        } catch (RuntimeException | Error ignored) {
            // a progress line is never worth a failed reset
        }
    }

    private static Map<String, Object> said(Map<String, Object> out, int code, String message) {
        out.put("code", (long) code);
        out.put("message", message);
        return out;
    }

    /** Clears the persistent token-dead marker after the credential is fixed. Returns 0 if removed, 1 if
     *  no marker existed, or 2 if deleting it failed (matching unpin). */
    public int clearTokenDead() {
        try {
            return Doors.clearTokenDead(dir) ? 0 : 1;
        } catch (IOException e) {
            return 2;
        }
    }

    /** Writes the tag= pin and stages a sync to it. The UI handles showing the PINNED warning. */
    public int syncTo(String tag) {
        try {
            writePin(tag);
            return stagedSync(UpdaterConfig.load(dir), tag, false, false);
        } catch (IOException e) {
            return 2;
        }
    }

    /**
     * Clears the tag= pin so this install tracks the server broadcast again. Doesn't stage a sync (the
     * next boot will sync automatically); returns 1 if no pin was set.
     */
    public int unpin() {
        try {
            if (UpdaterConfig.load(dir).tagPin().isEmpty()) {
                return 1;
            }
            writePin("");
            return 0;
        } catch (IOException e) {
            return 2;
        }
    }

    // Updates both server_host in bamfos-updater.properties and Minecraft's servers.dat together so syncing and joining stay in sync.
    public int setServer(String host, int port) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty() || h.contains(" ") || port < 1 || port > 65535) {
            return 2;
        }
        try {
            UpdaterConfig before = UpdaterConfig.load(dir);
            if (h.equals(before.serverHost()) && port == before.serverPort()) {
                return 1; // Don't report "saved" if the file didn't actually change.
            }
            writeKeys(Map.of("server_host", h, "server_port", Integer.toString(port)));
            return mergeServerList(h, port) ? 0 : 3;
        } catch (IOException | RuntimeException e) {
            return 2;
        }
    }

    /**
     * Updates our entry in servers.dat via the normal seed merge, preserving the player's other servers.
     * Carries the existing entry name over unchanged so we don't overwrite a player's custom label.
     */
    private boolean mergeServerList(String host, int port) {
        try {
            Path serversDat = dir.resolve("servers.dat");
            String priorName = Ledger.load(Ledger.pathIn(dir)).files.values().stream()
                    .map(e -> e.mergeName)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst()
                    .orElse("BAMFOS");
            String ip = port == 25565 ? host : host + ":" + port; // Omit default port 25565 so the saved server entry matches what the player typed.
            LinkedHashMap<String, Nbt.Val> entry = new LinkedHashMap<>();
            entry.put("name", new Nbt.Val(8, priorName));
            entry.put("ip", new Nbt.Val(8, ip));
            LinkedHashMap<String, Nbt.Val> root = new LinkedHashMap<>();
            root.put("servers", new Nbt.Val(9, new Nbt.ListVal(10, List.of(new Nbt.Val(10, entry)))));
            byte[] seed = Nbt.write(new Nbt.Root("", root));
            ServersDat.mergeFirstEntry(serversDat, seed, priorName);
            return true;
        } catch (IOException | RuntimeException theirListIsTheirs) {
            // Never replace a servers.dat we couldn't read or write; return 3 so the UI knows only the config
            // side succeeded.
            return false;
        }
    }

    /** Max releases to fetch for the rollback picker (sent as per_page so large repos don't return megabytes). */
    static final int MAX_RELEASES = 10;

    /**
     * Rollback picker rows, newest first, with a status code (never throws). Offline, returns just the
     * installed version rather than an empty list, and keeps rate-limit distinct from hard failures.
     */
    public Map<String, Object> listReleases() {
        String installed = installedVersion();
        List<Map<String, Object>> rows = new ArrayList<>();
        int code = 0;
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            // Empty tag and null cache path are intentional here; list calls don't fetch or cache a specific release.
            GithubReleases gh = new GithubReleases(apiBase, cfg.repo(), cfg.githubToken(), "",
                    dir.resolve(".bamfos-updater"));
            for (Map<?, ?> r : gh.releases(MAX_RELEASES)) {
                if (r.get("tag_name") instanceof String tag && !tag.isEmpty()) {
                    rows.add(releaseRow(tag,
                            r.get("name") instanceof String name ? name : "",
                            r.get("published_at") instanceof String at ? at : "",
                            Boolean.TRUE.equals(r.get("prerelease")),
                            tag.equals(installed)));
                }
            }
        } catch (HttpFetcher.RateLimited limited) {
            // BEFORE the generic catch, same ordering stagedSync needs: folded into 2 the picker calls a
            // throttle a broken updater, and the player goes hunting for a fix instead of waiting an hour
            code = 5;
        } catch (HttpFetcher.TokenDead dead) {
            code = 4;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); // the BIOS runs this on a worker we may be tearing down
            code = 2;
        } catch (Exception offline) {
            code = 2;
        }
        if (code != 0) {
            rows.clear(); // Don't return a partial release list on error.
            if (!installed.isEmpty()) {
                rows.add(releaseRow(installed, "", "", false, true));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("installed", installed);
        out.put("releases", rows);
        return out;
    }

    /** Builds a consistent row map for both online and offline modes so callers don't need key checks. */
    private static Map<String, Object> releaseRow(String tag, String name, String publishedAt,
                                                  boolean prerelease, boolean installed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", tag);
        row.put("name", name);
        row.put("published_at", publishedAt);
        row.put("prerelease", prerelease);
        row.put("installed", installed);
        return row;
    }

    /** Current installed version: prefers the bcc stamp, falling back to the last synced ledger version. */
    private String installedVersion() {
        Optional<String> stamped = Doors.localVersion(dir);
        if (stamped.isPresent()) {
            return stamped.get();
        }
        try {
            return Ledger.load(Ledger.pathIn(dir)).installedVersion;
        } catch (Exception unreadable) {
            return "";
        }
    }

    /** Full hash audit vs the INSTALLED version's manifest, staged. */
    public int repair() {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            String tag = pinnedOrInstalled(cfg, Ledger.load(Ledger.pathIn(dir)));
            if (tag.isEmpty()) {
                return 1;
            }
            // Clear rejected-engine strikes on repair so a false-positive probation failure doesn't pin this
            // install to an older engine until a new build ships.
            EngineHealth.forgiveRejectedEngines(dir);
            return stagedSync(cfg, tag, true, false);
        } catch (IOException e) {
            return 2;
        }
    }

    /**
     * Optional-mods page data: category=optional entries with disk/ledger state, plus quarantined jars.
     * Tracks `missing` separately when neither name is on disk so a hand-deleted jar doesn't render as on.
     */
    public List<Map<String, Object>> listOptional() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Ledger ledger = Ledger.load(Ledger.pathIn(dir));
            Path cached = dir.resolve(".bamfos-updater").resolve("manifest-current.json");
            if (Files.isRegularFile(cached)
                    && MiniJson.parse(Files.readString(cached, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    && m.get("files") instanceof List<?> files) {
                for (Object f : files) {
                    if (f instanceof Map<?, ?> fm && "optional".equals(fm.get("category"))
                            && fm.get("path") instanceof String path) {
                        out.add(modRow(path, false, ""));
                    }
                }
            }
            for (Map.Entry<String, Ledger.Entry> e : ledger.files.entrySet()) {
                if (e.getValue().quarantined) {
                    out.add(modRow(e.getKey(), true,
                            e.getValue().quarantineReason == null ? "" : e.getValue().quarantineReason));
                }
            }
        } catch (Exception ignored) {
            // an unreadable cache lists nothing; the page renders empty, nothing breaks
        }
        return out;
    }

    /** Builds a consistent row map for both kinds so callers don't need key checks. */
    private Map<String, Object> modRow(String path, boolean quarantined, String reason) {
        boolean on = Files.isRegularFile(dir.resolve(path));
        boolean off = Files.isRegularFile(dir.resolve(path + ".disabled"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("disabled", !on && off);
        row.put("missing", !on && !off);
        row.put("quarantined", quarantined);
        if (quarantined) {
            row.put("reason", reason);
        }
        return row;
    }

    /** Flips the filename bit THROUGH the engine so the ledger stays coherent. True =
     *  the ask is met or journaled (a held jar flips at the next boot's step 0); false = nothing sane
     *  to flip (both names on disk is crash debris and the reconciler's business, neither name means
     *  no such mod), with the reason printed, because the BIOS renders every false as "See the log." */
    public boolean setDisabled(String path, boolean disabled) {
        try (var lock = InstanceLock.acquire(dir).orElse(null)) {
            if (lock == null) {
                System.out.println("[updater] cannot toggle " + path + ": another sync has the lock");
                return false;
            }
            Path enabledFile = dir.resolve(path);
            Path off = dir.resolve(path + ".disabled");
            boolean hasEnabled = Files.isRegularFile(enabledFile);
            boolean hasOff = Files.isRegularFile(off);
            if (hasEnabled == hasOff) {
                System.out.println("[updater] cannot toggle " + path + (hasEnabled
                        ? ": both names are on disk, crash debris the next sync untangles"
                        : ": neither name is on disk, no such mod here"));
                return false;
            }
            if (disabled == hasOff) {
                ledgerBit(path, disabled); // Already in the requested state; just fix the ledger entry if it drifted.
                return true;
            }
            try {
                Files.move(disabled ? enabledFile : off, disabled ? off : enabledFile);
            } catch (IOException held) {
                // FML keeps loaded jars in mods/ open while the game is running, so renaming one on Windows fails
                // with ERROR_SHARING_VIOLATION. That's expected for a normal toggle, not an error.
                return journalToggle(path, disabled, held);
            }
            ledgerBit(path, disabled);
            return true;
        } catch (IOException e) {
            System.out.println("[updater] cannot toggle " + path + ": " + e);
            return false;
        }
    }

    /**
     * Appends a "rename" journal op so next boot's step 0 flips the filename before FML scans mods.
     * Verifies source sha and refuses occupied targets; ledger stays unchanged until that boot adopts it.
     */
    private boolean journalToggle(String path, boolean disabled, IOException held) {
        String from = disabled ? path : path + ".disabled";
        String to = disabled ? path + ".disabled" : path;
        try {
            Journal journal = Journal.load(Journal.pathIn(dir));
            if (journal.ops.isEmpty()) {
                // a fresh journal gets the installed version, because carryOver keeps leftover ops only for a
                // plan targeting the SAME version: unnamed, the very next in-session sync drops the flip
                journal.targetVersion = Ledger.load(Ledger.pathIn(dir)).installedVersion;
            }
            Journal.Op op = new Journal.Op("rename", from, to, HashUtils.sha256(dir.resolve(from)));
            // Ignore duplicate toggle presses so we don't queue the same op twice and log a spurious abandon.
            if (journal.ops.stream().noneMatch(o -> o.kind().equals(op.kind())
                    && o.from().equals(op.from()) && o.to().equals(op.to()))) {
                journal.ops.add(op);
                journal.save(Journal.pathIn(dir));
            }
        } catch (IOException e) {
            System.out.println("[updater] cannot toggle " + path + ": the game is locking " + from + " ("
                    + held + ") and the journal write failed too (" + e + ")");
            return false;
        }
        System.out.println("[updater] the game is locking " + from + " (" + held
                + "), the toggle is journaled for the next boot");
        return true;
    }

    /** Updates the ledger after a mod toggle rename already succeeded on disk. Swallows IOException so we
     *  don't tell the player the toggle failed when it actually worked; the next sync picks up the on-disk
     *  state anyway and logs the error. */
    private void ledgerBit(String path, boolean disabled) {
        try {
            Ledger ledger = Ledger.load(Ledger.pathIn(dir));
            Ledger.Entry en = ledger.files.get(path);
            if (en != null && en.disabled != disabled) {
                en.disabled = disabled;
                ledger.save(Ledger.pathIn(dir));
            }
        } catch (IOException e) {
            System.out.println("[updater] " + path + " flipped on disk but the ledger did not save (" + e
                    + "), the next sync adopts the bit");
        }
    }

    /** The cached notes body: current release's, or the just-applied one after a sync. */
    public String releaseNotes() {
        try {
            Path p = dir.resolve(".bamfos-updater").resolve("release-notes.json");
            if (Files.isRegularFile(p)
                    && MiniJson.parse(Files.readString(p, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    && m.get("body") instanceof String body) {
                return body;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private int stagedSync(UpdaterConfig cfg, String tag, boolean repair, boolean server) {
        Optional<InstanceLock> lock;
        try {
            lock = InstanceLock.acquire(dir);
        } catch (IOException e) {
            return 2;
        }
        if (lock.isEmpty()) {
            return 3;
        }
        // Use append() so we don't truncate bus.log under the live tailer, and always emit END in finally
        // or syncActive() leaves the BIOS file actions locked for the rest of the session.
        BootBus bus = BootBus.append(dir);
        String end = "fail";
        try (InstanceLock ignored = lock.get()) {
            String from = Doors.localVersion(dir).orElse("");
            long asked = System.currentTimeMillis();
            int code = server
                    ? Doors.stagedServerSync(dir, cfg, tag, from, repair, apiBase, bus)
                    : Doors.stagedClientSync(dir, cfg, tag, from, repair, apiBase, bus);
            end = "ok";
            // 0 with nothing staged renders on the BIOS as "Changes staged. Applies on next boot.", which
            // sends a current box rebooting for nothing and means Verify can NEVER answer a healthy install
            // clean. Both surfaces read 6 now: OpsBrain grew its own already-current line, so the server
            // gate that used to hold this back is gone.
            return code == 0 && alreadyCurrent(asked) ? 6 : code;
        } catch (HttpFetcher.RateLimited limited) {
            // Catch rate limits before the generic handler so the UI reports throttling instead of a hard sync failure.
            end = "limited";
            return 5;
        } catch (HttpFetcher.TokenDead dead) {
            return 4;
        } catch (GithubReleases.ReleaseMissing gone) {
            // 1 is "no target to sync to" and that is exactly what a 404 on the target tag means, so folding
            // it into 2 had the page answer "Sync failed. See the log." for the one state where the log says
            // nothing is wrong with this box: the release is not there. Only the host can fix that, by
            // publishing it, and a player told the sync failed goes hunting his own machine instead
            return 1;
        } catch (Exception e) {
            return 2;
        } finally {
            bus.end(end);
        }
    }

    /**
     * Did the sync that JUST answered 0 stage anything? Read off last-sync.json rather than a new SyncCore
     * return code, because that record is the engine's own verdict written under the lock we still hold, and
     * "already current (" is the one summary its no-op branch writes (SyncCore.sync); the boot doors keep
     * their 0, current is their every-boot answer and no boot surface renders it as staged. The timestamp
     * check covers the record write failing (lastSync swallows IOException): a stale already-current from an
     * earlier sync must never turn real staged work into "done", so anything unreadable or old answers
     * false and the page keeps the staged line, which is the safe direction, claiming staged over nothing
     * wastes a reboot, claiming done over staged work hides an update.
     */
    private boolean alreadyCurrent(long since) {
        try {
            Path last = dir.resolve(".bamfos-updater").resolve("last-sync.json");
            return Files.isRegularFile(last)
                    && MiniJson.parse(Files.readString(last, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    && m.get("at") instanceof Long at && at >= since
                    && m.get("summary") instanceof String s
                    && s.startsWith("already current (");
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Rewrites only the tag= line of the properties file; every other line is the human's.
     * LATIN-1 both ways, NOT UTF-8, because UpdaterConfig.rawProps reads this same file with a latin-1 fallback
     * for a documented reason (readString throws on a file Notepad saved as ANSI). Read it stricter than the
     * boot path does and a player who typed one accented character in a comment gets a box where the sync
     * still works but the BIOS rollback page answers "Sync failed. See the log." forever, with nothing in the
     * log saying why. latin-1 maps every byte, so this round-trips their bytes untouched either way.
     */
    void writePin(String tag) throws IOException {
        writeKeys(Map.of("tag", tag));
    }

    /**
     * Rewrites only the named keys in a single atomic pass (so a crash can't split host and port).
     * Empty values delete the key since UpdaterConfig treats absent and empty identically.
     */
    private void writeKeys(Map<String, String> pairs) throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(p)) {
            for (String line : Files.readAllLines(p, StandardCharsets.ISO_8859_1)) {
                if (pairs.keySet().stream().noneMatch(k -> isKeyLine(line, k))) {
                    lines.add(line);
                }
            }
        }
        // Sort keys so rewrites stay deterministic and produce clean diffs.
        pairs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (!e.getValue().isEmpty()) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
        });
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try {
            Files.write(tmp, lines, StandardCharsets.ISO_8859_1);
            try {
                // Use ATOMIC_MOVE (same volume via resolveSibling): plain REPLACE_EXISTING on Windows deletes
                // before moving, so an AV lock on the tmp file could wipe the player's config.
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException filesystemSaysNo) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp); // If the replace fails, keep the old config and clean up the temp file.
            } catch (IOException ignored) {
                // Don't let a failed temp-file delete hide the original replace error.
            }
            throw e;
        }
    }

    /* Matches any key separator java.util.Properties accepts ('=', ':', whitespace) so writes always
       find the same line UpdaterConfig reads. */
    private static boolean isKeyLine(String line, String key) {
        String s = line.trim();
        if (s.startsWith("#") || s.startsWith("!")) {
            return false; // Preserve comment lines as-is; Properties ignores them.
        }
        int end = 0;
        while (end < s.length() && "=: \t\f".indexOf(s.charAt(end)) < 0) {
            end++;
        }
        return key.contentEquals(s.subSequence(0, end));
    }
}
