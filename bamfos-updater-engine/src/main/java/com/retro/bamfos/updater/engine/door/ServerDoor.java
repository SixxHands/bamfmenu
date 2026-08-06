package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.guard.EngineHealth;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
import com.retro.bamfos.updater.engine.ping.PortHold;
import com.retro.bamfos.updater.engine.ping.UpdatingNotice;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;

import java.nio.file.Path;
import java.util.Optional;

// Server pre-JVM sync. Syncs to the latest release (or --tag if pinned) and always exits 0 so the wrapper always reaches the server launch line.
public final class ServerDoor {

    public static final String SHIM_PATH = SyncCore.SHIM_SERVER_PATH;
    public static final String ENGINE_PATH = SyncCore.ENGINE_PATH;
    public static final String TOKEN_DEAD_MARKER = SyncCore.TOKEN_DEAD_MARKER;

    private final String apiBase; // injectable so the fixture server can play GitHub in tests

    public ServerDoor(String apiBase) {
        this.apiBase = apiBase;
    }

    /** Runs the server sync while owning the server port. Acquires PortHold before loading config and
     *  releases it in finally so the port is free before the wrapper launches the server JVM. */
    public int run(Path dir, String tagOverride, boolean repair) {
        PortHold hold = PortHold.open(dir, UpdatingNotice.target(UpdatingNotice.lastTarget(dir),
                ClientDoor.localVersion(dir).orElse("")));
        try {
            return sync(dir, tagOverride, repair);
        } finally {
            String trouble = hold.release();
            if (!trouble.isEmpty()) {
                SyncCore.loud(dir, trouble);
            }
        }
    }

    /**
     * The box repairing itself after a sync that did not land. A server has no BIOS and nobody watching it,
     * so without this the honest `pending` in last-sync.json was written and then read by nothing: the box
     * went on serving a half-applied pack until a human typed /bamfos status, and the only thing that would
     * ever have re-tried is a restart the watchdog stops granting after two attempts.
     * It is not extra github traffic. A repair fetches the same manifest an ordinary cycle does and
     * downloads only the files that actually differ; what it adds is hashing every managed file locally,
     * which is the work that finds bytes rule A skips. It stops on its own: the moment a cycle converges the
     * record says ok and the next boot is ordinary again.
     */
    private static boolean selfHeal(Path dir) {
        String last = SyncCore.lastSyncResult(dir);
        boolean held = SyncCore.lastSyncHeldFiles(dir);
        if (!SyncCore.needsRepair(last, held)) {
            return false;
        }
        SyncCore.loud(dir, "last sync recorded \"" + last + "\""
                + (held ? " with blocked files remaining" : "")
                + ", running a full hash check across managed files instead of trusting the ledger");
        return true;
    }

    private int sync(Path dir, String tagOverride, boolean repair) {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            Optional<InstanceLock> lock = InstanceLock.acquire(dir);
            if (lock.isEmpty()) {
                System.out.println("[updater] another sync is running, skipping");
                return EngineHealth.proven(dir, 0); // another instance is already running sync
            }
            try (InstanceLock ignored = lock.get()) {
                // same as client: step 0 saw a file mismatch on readback, so force a full hash check
                boolean unproven = SyncCore.stepZero(dir, BootBus.headless(), cfg.pins());
                if (unproven) {
                    SyncCore.loud(dir, "step 0 applied files that do not read back as what it placed, so this "
                            + "cycle re-derives the whole install");
                }
                if (repair) {
                    // Only an explicit repair flag clears engine/core refusals (checked before self-heal sets repair=true),
                    // so automatic self-heal never re-arms a known-bad engine into a rollback loop.
                    EngineHealth.forgiveRejectedEngines(dir);
                }
                repair = repair || unproven || selfHeal(dir);
                String tag = !tagOverride.isEmpty() ? tagOverride
                        : !cfg.tagPin().isEmpty() ? cfg.tagPin()
                        : "latest";
                return SyncCore.sync(dir, cfg, tag, repair, Reconciler.Side.SERVER, apiBase);
            }
        } catch (HttpFetcher.RateLimited limited) {
            // Catch RateLimited before TokenDead and never write the token-dead marker. Log loudly so
            // server-sync.log closes cleanly after "updating server X -> Y" instead of looking hung.
            String why = "github rate limited (" + limited.code
                    + "), launching on the current version, the next cycle retries";
            SyncCore.loud(dir, why);
            // Write `limited` to last-sync.json so the watchdog doesn't echo the previous boot's "synced"
            // summary into logs/latest.log on a cycle that skipped.
            SyncCore.recordFailure(dir, "limited", why);
            return EngineHealth.proven(dir, 0); // rate limit still means github responded, so the engine ran fine
        } catch (HttpFetcher.TokenDead dead) {
            SyncCore.tokenDead(dir, dead);
            return EngineHealth.proven(dir, 0); // A revoked credential is a token issue, not an engine failure.
        } catch (GithubReleases.ReleaseMissing gone) {
            // Log and record the missing tag (or explain the "latest" alias 404 when only drafts/prereleases
            // exist) so both server-sync.log and the watchdog summary name what needs to be published.
            String why = gone.alias
                    ? "nothing to sync to, the " + gone.repo + " channel has no published release yet "
                            + "(a draft and a prerelease both count as no release here), "
                            + "launching on the current version"
                    : "nothing to sync to, no release named \"" + gone.tag + "\" on " + gone.repo
                            + " (or the token cannot see it), launching on the current version";
            SyncCore.loud(dir, why);
            SyncCore.recordFailure(dir, "no-release", why);
            return EngineHealth.proven(dir, 0); // github responded, so mark the engine healthy
        } catch (Exception e) {
            // Record offline vs failed in server-sync.log and last-sync.json so stale "ok" results aren't
            // echoed after broken boots, while keeping transient network drops distinct from real failures.
            boolean world = SyncCore.networkFault(e);
            String why = world
                    ? "github unreachable (" + e + "), launching on the current version, the next cycle retries"
                    : "sync FAILED (" + e + "), launching on the current version, the next cycle retries";
            SyncCore.loud(dir, why);
            SyncCore.recordFailure(dir, world ? "offline" : "failed", why);
            if (EngineHealth.attributable(e)) {
                // server has no pre-sync ping, so offline boots land here; don't treat network errors as a broken
                // engine or a router outage would trigger a rollback
                EngineHealth.proven(dir);
            }
            return 0;
        }
    }
}
