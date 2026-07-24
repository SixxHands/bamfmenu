package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The server door. No ping, no gate: the server defines truth, so default is the LATEST
 * release (--tag pins/rolls back). rules every exit: 0 no matter what after one log line --
 * the wrapper must always proceed to launch the server.
 */
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
        } catch (HttpFetcher.TokenDead dead) {
            SyncCore.tokenDead(dir, dead);
            return 0;
        } catch (Exception e) {
            System.out.println("[updater] sync skipped: " + e); // the server must still boot
            return 0;
        }
    }
}
