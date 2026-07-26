package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
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

    public int run(Path dir, String tagOverride, boolean repair) {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            Optional<InstanceLock> lock = InstanceLock.acquire(dir);
            if (lock.isEmpty()) {
                System.out.println("[updater] another sync is running, skipping");
                return 0;
            }
            try (InstanceLock ignored = lock.get()) {
                SyncCore.stepZero(dir);
                String tag = !tagOverride.isEmpty() ? tagOverride
                        : !cfg.tagPin().isEmpty() ? cfg.tagPin()
                        : "latest";
                return SyncCore.sync(dir, cfg, tag, repair, Reconciler.Side.SERVER, apiBase);
            }
        } catch (HttpFetcher.RateLimited limited) {
            // Catch RateLimited before TokenDead and never write the token-dead marker. Log loudly so
            // server-sync.log closes cleanly after "updating server X -> Y" instead of looking hung.
            SyncCore.loud(dir, "github rate limited (" + limited.code
                    + "), launching on the current version, the next cycle retries");
            return 0;
        } catch (HttpFetcher.TokenDead dead) {
            SyncCore.tokenDead(dir, dead);
            return 0;
        } catch (Exception e) {
            System.out.println("[updater] sync skipped: " + e); // the server must still boot
            return 0;
        }
    }
}
