package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.ping.BetterStatus;
import com.retro.bamfos.updater.engine.ping.ServersDat;
import com.retro.bamfos.updater.engine.ping.Slp;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;

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

    /** In-session sync: everything lands via staging + journal, applies next boot. */
    public static int stagedClientSync(Path dir, UpdaterConfig cfg, String tag, String from,
                                       boolean repair, String apiBase, BootBus bus) throws Exception {
        return SyncCore.sync(dir, cfg, tag, from, repair, true, Reconciler.Side.CLIENT, apiBase, bus);
    }

    private Doors() {
    }
}
