package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.manifest.ManifestParser;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sync both doors share: step 0, resolve+reconcile+apply, and the self-divert for the two
 * files a running updater can never replace in place -- the shim (wrapper swaps its .new server-
 * side; next boot's promotion scan picks up the swapped jar client-side) and the loaded engine
 * (the shim swaps its .new before loading). Doors differ ONLY in how they pick the tag and side.
 * Everything the boot display or the BIOS later needs is cached beside the ledger on the way
 * through: release notes, the manifest itself, the last-sync result.
 */
final class SyncCore {

    static final String SHIM_SERVER_PATH = "bamfos-updater-boot.jar";
    static final String SHIM_CLIENT_PATH = "mods/bamfos-updater-boot.jar";
    static final String ENGINE_PATH = ".bamfos-updater/engine/bamfos-updater-engine.jar";
    static final String TOKEN_DEAD_MARKER = "token-dead";

    /** step 0: finish any crashed apply from LOCAL bytes; stuck ops are re-planned, never poison. */
    static void stepZero(Path dir) throws IOException {
        Journal leftover = Journal.load(Journal.pathIn(dir));
        if (!leftover.ops.isEmpty()) {
            System.out.println("[updater] finishing interrupted apply (" + leftover.ops.size() + " ops)");
            var stuck = leftover.replay(dir);
            if (!stuck.isEmpty()) {
                System.out.println("[updater] " + stuck.size() + " ops could not apply, re-planning them");
            }
            Files.deleteIfExists(Journal.pathIn(dir));
        }
    }

    static int sync(Path dir, UpdaterConfig cfg, String tag, boolean repair, Reconciler.Side side,
                    String apiBase) throws Exception {
        String from = Ledger.load(Ledger.pathIn(dir)).installedVersion;
        return sync(dir, cfg, tag, from, repair, false, side, apiBase, BootBus.headless());
    }

    static int sync(Path dir, UpdaterConfig cfg, String tag, String fromVersion, boolean repair,
                    boolean staged, Reconciler.Side side, String apiBase, BootBus bus) throws Exception {
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        GithubReleases gh = new GithubReleases(apiBase, cfg.repo(), cfg.githubToken(), tag, staging);
        String manifestJson = gh.manifestJson();
        Manifest manifest = ManifestParser.parse(manifestJson);
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
        Reconciler.Fetcher feed = (e, dest) -> {
            bus.fileStart(fileName(e.path()));
            gh.fetch(e, dest);
            bus.fileDone(fileName(e.path()));
        };
        rec.apply(plan, filtered, ledger, feed, bus::applying, staged);

        boolean pendingSelf = false;
        for (FileEntry e : self) {
            Path current = dir.resolve(e.path());
            boolean same = Files.isRegularFile(current) && HashUtils.sha256(current).equals(e.sha256());
            if (!same) {
                Path part = staging.resolve(e.sha256() + ".part");
                bus.fileStart(fileName(e.path()));
                gh.fetch(e, part);
                if (!HashUtils.sha256(part).equals(e.sha256())) {
                    throw new IOException(e.path() + " download failed hash verification");
                }
                Path stagedNew = dir.resolve(e.path() + ".new");
                Files.createDirectories(stagedNew.getParent()); // EngineLoader usually creates this dir, but make sure it exists
                Files.move(part, stagedNew, StandardCopyOption.REPLACE_EXISTING);
                bus.fileDone(fileName(e.path()));
                pendingSelf = true;
                System.out.println("[updater] " + e.path() + ".new staged (swapped in before the next run)");
            }
        }

        cacheManifest(dir, manifestJson);
        boolean pending = staged || pendingSelf || Files.exists(Journal.pathIn(dir));
        if (plan.isNoop() && !pendingSelf) {
            System.out.println("[updater] pack current (" + manifest.pack().version() + ", "
                    + plan.actions.size() + " actions)");
        } else {
            bus.synced(fromVersion.isEmpty() ? "?" : fromVersion, manifest.pack().version(), pending);
        }
        lastSync(dir, manifest.pack().version(), staged ? "staged" : "ok");
        return 0;
    }

    // Token death is a persistent state so it never looks like a temporary wifi blip.
    static void tokenDead(Path dir, HttpFetcher.TokenDead dead) {
        System.out.println("[updater] TOKEN DEAD: " + dead.getMessage() + " - updates are disabled until the token is replaced");
        try {
            Path marker = dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, dead.getMessage());
            lastSync(dir, "", "token-dead");
        } catch (IOException ignored) {
        }
    }

    static void clearTokenDead(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER));
    }

    /** The notes ARE the notification: headline to the bus now, body cached for the
     *  BIOS what's-new page -- zero extra requests, it rode in on the release JSON. */
    private static void cacheReleaseNotes(Path dir, GithubReleases gh, String version, BootBus bus) {
        String body = gh.targetReleaseField("body");
        String name = gh.targetReleaseField("name");
        String headline = firstLine(body);
        if (headline.isEmpty()) {
            headline = name == null ? "" : name;
        }
        if (!headline.isEmpty()) {
            bus.note("PACK UPDATE " + version + " - " + headline);
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

    /** The applied manifest, cached verbatim: UpdaterOps derives the optional-mods page from it
     *  offline (categories never made it into the ledger, on purpose -- one source of truth). */
    private static void cacheManifest(Path dir, String manifestJson) {
        try {
            Files.writeString(dir.resolve(".bamfos-updater").resolve("manifest-current.json"),
                    manifestJson, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static void lastSync(Path dir, String tag, String result) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("at", System.currentTimeMillis());
            root.put("tag", tag);
            root.put("result", result);
            Files.writeString(dir.resolve(".bamfos-updater").resolve("last-sync.json"),
                    JsonWriter.write(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
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
