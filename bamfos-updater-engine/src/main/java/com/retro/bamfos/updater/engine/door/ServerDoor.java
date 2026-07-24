package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
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
import java.util.Optional;

/**
 * The server door: config -> lock -> journal step 0 -> resolve -> reconcile -> apply.
 * No ping, no gate: the server defines truth, so default is the LATEST release (--tag pins/rolls
 * back). rules every exit: 0 no matter what after one log line -- the wrapper must always
 * proceed to launch the server. The running shim jar is the one file we can't replace in place;
 * a newer shim lands as bamfos-updater-boot.jar.new and the wrapper's first line swaps it.
 */
public final class ServerDoor {

    public static final String SHIM_PATH = "bamfos-updater-boot.jar";
    public static final String ENGINE_PATH = ".bamfos-updater/engine/bamfos-updater-engine.jar";
    public static final String TOKEN_DEAD_MARKER = "token-dead";

    private final String apiBase; // injectable so the fixture server can play GitHub in tests

    public ServerDoor(String apiBase) {
        this.apiBase = apiBase;
    }

    public int run(Path dir, String tagOverride, boolean repair) {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            Optional<InstanceLock> lock = InstanceLock.acquire(dir);
            if (lock.isEmpty()) {
                System.out.println("[updater] another sync is running, skipping");
                return 0;
            }
            try (InstanceLock ignored = lock.get()) {
                return sync(dir, cfg, tagOverride, repair);
            }
        } catch (HttpFetcher.TokenDead dead) {
            tokenDead(dir, dead);
            return 0;
        } catch (Exception e) {
            System.out.println("[updater] sync skipped: " + e); // the server must still boot
            return 0;
        }
    }

    private int sync(Path dir, UpdaterConfig cfg, String tagOverride, boolean repair) throws Exception {
        // step 0: finish any crashed apply from LOCAL bytes before anything else.
        // stuck ops are dropped, not kept: the reconcile below re-plans from disk truth anyway,
        // and a permanently-stuck op (the engine self-write bug class) must never poison every sync
        Journal leftover = Journal.load(Journal.pathIn(dir));
        if (!leftover.ops.isEmpty()) {
            System.out.println("[updater] finishing interrupted apply (" + leftover.ops.size() + " ops)");
            var stuck = leftover.replay(dir);
            if (!stuck.isEmpty()) {
                System.out.println("[updater] " + stuck.size() + " ops could not apply, re-planning them");
            }
            Files.deleteIfExists(Journal.pathIn(dir));
        }

        String tag = !tagOverride.isEmpty() ? tagOverride
                : !cfg.tagPin().isEmpty() ? cfg.tagPin()
                : "latest";
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        GithubReleases gh = new GithubReleases(apiBase, cfg.repo(), cfg.githubToken(), tag, staging);
        Manifest manifest = ManifestParser.parse(gh.manifestJson());
        clearTokenDead(dir);

        // the two files this process cannot replace in place: the running SHIM (the wrapper swaps
        // its .new) and the loaded ENGINE.
        // both get filtered out of the reconcile and staged as .new when their hash differs
        List<FileEntry> files = new ArrayList<>();
        List<FileEntry> self = new ArrayList<>();
        for (FileEntry e : manifest.files()) {
            if ((e.path().equals(SHIM_PATH) && e.side() == FileEntry.Side.SERVER)
                    || e.path().equals(ENGINE_PATH)) {
                self.add(e);
            } else {
                files.add(e);
            }
        }
        Manifest filtered = new Manifest(manifest.schemaVersion(), manifest.pack(),
                manifest.requiresRelaunch(), manifest.removedModids(), files);

        Ledger ledger = Ledger.load(Ledger.pathIn(dir));
        Reconciler rec = new Reconciler(dir, Reconciler.Side.SERVER, cfg.pins());
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
                Files.move(part, dir.resolve(e.path() + ".new"), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[updater] " + e.path() + ".new staged (swapped in before the next run)");
            }
        }

        System.out.println("[updater] " + (plan.isNoop() ? "pack current" : "synced")
                + " (" + manifest.pack().version() + ", " + plan.actions.size() + " actions)");
        return 0;
    }

    /** Token death is a PERSISTENT loud state, never a quiet wifi-blip lookalike. */
    private static void tokenDead(Path dir, HttpFetcher.TokenDead dead) {
        System.out.println("[updater] TOKEN DEAD: " + dead.getMessage() + " - updates are disabled until the token is replaced");
        try {
            Path marker = dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, dead.getMessage());
        } catch (IOException ignored) {
        }
    }

    private static void clearTokenDead(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(".bamfos-updater").resolve(TOKEN_DEAD_MARKER));
    }
}
