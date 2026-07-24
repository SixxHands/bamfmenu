package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.manifest.ManifestParser;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The sync both doors share: step 0, resolve+reconcile+apply, and the self-divert for the two
 * files a running updater can never replace in place -- the shim (wrapper swaps its .new server-
 * side; next boot's promotion scan picks up the swapped jar client-side) and the loaded engine
 * (the shim swaps its .new before loading). Doors differ ONLY in how they pick the tag and side.
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
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        GithubReleases gh = new GithubReleases(apiBase, cfg.repo(), cfg.githubToken(), tag, staging);
        Manifest manifest = ManifestParser.parse(gh.manifestJson());
        clearTokenDead(dir);

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
        rec.apply(plan, filtered, ledger, gh);

        for (FileEntry e : self) {
            Path current = dir.resolve(e.path());
            boolean same = Files.isRegularFile(current) && HashUtils.sha256(current).equals(e.sha256());
            if (!same) {
                Path part = staging.resolve(e.sha256() + ".part");
                gh.fetch(e, part);
                if (!HashUtils.sha256(part).equals(e.sha256())) {
                    throw new IOException(e.path() + " download failed hash verification");
                }
                Path staged = dir.resolve(e.path() + ".new");
                Files.createDirectories(staged.getParent()); // EngineLoader usually creates this dir, but make sure it exists
                Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[updater] " + e.path() + ".new staged (swapped in before the next run)");
            }
        }

        System.out.println("[updater] " + (plan.isNoop() ? "pack current" : "synced")
                + " (" + manifest.pack().version() + ", " + plan.actions.size() + " actions)");
        return 0;
    }

    // Token death is a persistent state so it never looks like a temporary wifi blip.
    static void tokenDead(Path dir, HttpFetcher.TokenDead dead) {
        System.out.println("[updater] TOKEN DEAD: " + dead.getMessage() + " - updates are disabled until the token is replaced");
        try {
            Path marker = dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, dead.getMessage());
        } catch (IOException ignored) {
        }
    }

    static void clearTokenDead(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER));
    }

    private SyncCore() {
    }
}
