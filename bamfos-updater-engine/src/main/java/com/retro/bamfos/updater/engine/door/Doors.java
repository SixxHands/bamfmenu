package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.ping.BetterStatus;
import com.retro.bamfos.updater.engine.ping.ServersDat;
import com.retro.bamfos.updater.engine.ping.Slp;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Public facade for UpdaterOps, exposing local version, broadcast lookup, and staged sync. */
public final class Doors {

    /** The local pack version off bcc-common.toml, v-form intact. */
    public static Optional<String> localVersion(Path dir) {
        return ClientDoor.localVersion(dir);
    }

    /** Gets the server BCC broadcast from the configured host, falling back to the first entry in servers.dat. */
    public static Optional<String> broadcast(Path dir, UpdaterConfig cfg) {
        String host = cfg.serverHost();
        int port = cfg.serverPort();
        if (host.isEmpty()) {
            Optional<String> ip = ServersDat.firstIp(dir.resolve("servers.dat"));
            if (ip.isEmpty()) {
                return Optional.empty();
            }
            String raw = ip.get();
            int colon = raw.lastIndexOf(':');
            if (colon > 0 && raw.indexOf(':') == colon) { // host:port form, but never split an IPv6
                host = raw.substring(0, colon);
                try {
                    port = Integer.parseInt(raw.substring(colon + 1));
                } catch (NumberFormatException bad) {
                    host = raw;
                }
            } else {
                host = raw;
            }
        }
        try {
            return BetterStatus.parse(Slp.status(host, port)).map(BetterStatus::version);
        } catch (Exception unreachable) {
            return Optional.empty();
        }
    }

    /** In-session sync: stages all changes to apply on the next boot. Also clears engine probation since
     *  the BIOS invokes the promoted engine jar via UpdaterBridge, so completing a sync proves it works. */
    public static int stagedClientSync(Path dir, UpdaterConfig cfg, String tag, String from,
                                       boolean repair, String apiBase, BootBus bus) throws Exception {
        return SyncCore.sync(dir, cfg, tag, from, repair, true, Reconciler.Side.CLIENT, apiBase, bus, true);
    }

    /** Stages an in-session server sync for /bamfos sync (applied on next wrapper boot since FML keeps
     *  mods/ open). Passes provesEngine=false because this runs from the watchdog's jar-in-jar copy,
     *  not the shim-promoted engine, so clearing probation here would disarm rollback on a broken engine. */
    public static int stagedServerSync(Path dir, UpdaterConfig cfg, String tag, String from,
                                       boolean repair, String apiBase, BootBus bus) throws Exception {
        return SyncCore.sync(dir, cfg, tag, from, repair, true, Reconciler.Side.SERVER, apiBase, bus, false);
    }

    // Checks if the persistent token-dead marker exists (shared name between doors and ops).
    public static boolean tokenDead(Path dir) {
        return Files.exists(marker(dir));
    }

    /** Clears the token-dead marker after credentials are updated, returning true if one existed.
     *  Without this, surfaces keep showing TOKEN DEAD until the next sync parses a manifest. */
    public static boolean clearTokenDead(Path dir) throws IOException {
        return Files.deleteIfExists(marker(dir));
    }

    private static Path marker(Path dir) {
        return dir.resolve(".bamfos-updater").resolve(SyncCore.TOKEN_DEAD_MARKER);
    }

    private Doors() {
    }
}
