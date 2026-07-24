package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
import com.retro.bamfos.updater.engine.ping.BetterStatus;
import com.retro.bamfos.updater.engine.ping.ServersDat;
import com.retro.bamfos.updater.engine.ping.Slp;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The client door, invoked by the shim's locator during FML discovery -- before any mod
 * jar in mods/ is opened. start to finish: converge to what the SERVER BROADCASTS, never to
 * "latest". No broadcast, no local version, no wifi = boot as-is in well under the ping budget.
 * A client-side tag= pin beats the broadcast; the UI owns the warning.
 */
public final class ClientDoor {

    private final String apiBase;

    public ClientDoor(String apiBase) {
        this.apiBase = apiBase;
    }

    public int run(Path dir) {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            Optional<InstanceLock> lock = InstanceLock.acquire(dir);
            if (lock.isEmpty()) {
                System.out.println("[updater] another sync is running, skipping");
                return 0;
            }
            try (InstanceLock ignored = lock.get()) {
                SyncCore.stepZero(dir);

                String tag;
                if (!cfg.tagPin().isEmpty()) {
                    tag = cfg.tagPin(); // pinned version intentionally ignores the server broadcast
                    System.out.println("[updater] version PINNED to " + tag);
                } else {
                    Optional<String> broadcast = broadcast(dir, cfg);
                    if (broadcast.isEmpty()) {
                        System.out.println("[updater] no carrier, boot continues");
                        return 0;
                    }
                    String local = localVersion(dir).orElse("");
                    if (local.isEmpty()) {
                        System.out.println("[updater] no local version (bcc-common.toml missing), sync skipped");
                        return 0;
                    }
                    if (broadcast.get().equals(local)) {
                        System.out.println("[updater] pack current (" + local + "), nothing to do");
                        return 0;
                    }
                    tag = broadcast.get(); // upgrades and rollbacks use the same sync path
                }
                return SyncCore.sync(dir, cfg, tag, false, Reconciler.Side.CLIENT, apiBase);
            }
        } catch (HttpFetcher.TokenDead dead) {
            SyncCore.tokenDead(dir, dead);
            return 0;
        } catch (Exception e) {
            System.out.println("[updater] sync skipped: " + e); // the game must still boot
            return 0;
        }
    }

    /** The server's BCC broadcast: properties host first, else servers.dat's first entry. */
    private Optional<String> broadcast(Path dir, UpdaterConfig cfg) {
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

    /** The local pack version off BCC's config, same read the BIOS uses -- but keeping the v-form
     *  intact: the broadcast and the tag are byte-for-byte this string. */
    private static Optional<String> localVersion(Path dir) {
        Path bcc = dir.resolve("config").resolve("bcc-common.toml");
        if (!Files.isRegularFile(bcc)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(bcc, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("modpackVersion")) {
                    int a = s.indexOf('"');
                    int b = s.lastIndexOf('"');
                    if (a >= 0 && b > a) {
                        return Optional.of(s.substring(a + 1, b));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}
