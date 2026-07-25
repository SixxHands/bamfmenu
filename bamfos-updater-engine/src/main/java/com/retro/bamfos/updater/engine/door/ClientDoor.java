package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.guard.DisconnectHistory;
import com.retro.bamfos.updater.engine.guard.KickGuard;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
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
 * Also Q2's boot half: consume the mod's kick breadcrumb FIRST (quarantine fires even on a
 * version-current boot -- the classic kick has no version drift at all), record what the cycle
 * changed so the next kick with the same fingerprint can escalate honestly.
 */
public final class ClientDoor {

    private final String apiBase;

    public ClientDoor(String apiBase) {
        this.apiBase = apiBase;
    }

    public int run(Path dir) {
        BootBus bus = BootBus.at(dir);
        Optional<KickGuard.Outcome> kick = Optional.empty();
        String versionBefore = localVersion(dir).orElse("");
        int code = 0;
        String end = "skip";
        try {
            bus.line("com.bamf.updater kmod start");
            bus.line("com.bamf.updater load succeeded");
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            Optional<InstanceLock> lock = InstanceLock.acquire(dir);
            if (lock.isEmpty()) {
                bus.line("updater: another sync is running, skipping");
                return 0;
            }
            try (InstanceLock ignored = lock.get()) {
                SyncCore.stepZero(dir);
                kick = KickGuard.consume(dir, cfg.pins(), bus);

                String tag;
                if (!cfg.tagPin().isEmpty()) {
                    tag = cfg.tagPin(); // pinned version intentionally ignores the server broadcast
                    bus.line("updater: version PINNED to " + tag);
                } else {
                    Optional<String> broadcast = Doors.broadcast(dir, cfg);
                    if (broadcast.isEmpty()) {
                        bus.line("updater: no carrier, boot continues");
                        return 0;
                    }
                    // fresh read, NOT versionBefore: step 0 may have just replayed a staged sync
                    // that rewrote bcc, and comparing the broadcast against the pre-replay value
                    // would re-sync a pack that is already current
                    String local = localVersion(dir).orElse("");
                    if (local.isEmpty()) {
                        bus.line("updater: no local version (bcc-common.toml missing), sync skipped");
                        return 0;
                    }
                    if (broadcast.get().equals(local)) {
                        if (Files.isRegularFile(dir.resolve(".bamfos-updater").resolve("manifest-current.json"))) {
                            bus.line("updater: pack current (" + local + "), nothing to do");
                            return 0;
                        }
                        // current but never synced (fresh seed install): run the no-op sync once
                        // anyway, because it backfills the manifest/notes caches the BIOS pages read
                        bus.line("updater: pack current (" + local + "), caching release data");
                    }
                    tag = broadcast.get(); // upgrades and rollbacks use the same sync path
                }
                code = SyncCore.sync(dir, cfg, tag, versionBefore, false, false,
                        Reconciler.Side.CLIENT, apiBase, bus);
                end = "ok";
                return code;
            }
        } catch (HttpFetcher.TokenDead dead) {
            SyncCore.tokenDead(dir, dead);
            bus.line("AuthEvent: GITHUB token FAILED (401) - updates disabled");
            end = "fail";
            return 0;
        } catch (Exception e) {
            bus.line("updater: sync failed, boot continues"); // the terminal deserves the why-line too
            System.out.println("[updater] sync skipped: " + e); // the game must still boot
            end = "fail";
            return 0;
        } finally {
            bus.end(end);
            kick.ifPresent(k -> recordCycle(dir, k, versionBefore));
        }
    }

    /** The history entry Q2's variant choice hangs on: did this cycle change ANYTHING -- a
     *  quarantine or the pack version? Neither, and the same kick again = unrepairable screen. */
    private void recordCycle(Path dir, KickGuard.Outcome kick, String versionBefore) {
        try {
            boolean versionChanged = !localVersion(dir).orElse("").equals(versionBefore);
            DisconnectHistory h = DisconnectHistory.load(dir);
            h.record(kick.fingerprint(), kick.action(), versionChanged, System.currentTimeMillis());
            h.save(dir);
        } catch (Exception ignored) {
            // don't fail boot if we can't write kick history; worst case is one extra retry screen
        }
    }

    /** The local pack version off BCC's config, same read the BIOS uses -- but keeping the v-form
     *  intact: the broadcast and the tag are byte-for-byte this string. */
    static Optional<String> localVersion(Path dir) {
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
