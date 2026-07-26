package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.EngineEntry;
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

/* Client boot sync (runs during FML discovery before mods/ jars open). Syncs to the version the server
 * broadcasts, never blindly to "latest". No server response or offline just boots as-is. */
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
            bus.line("com.bamf.updater: kmod start");
            bus.line("com.bamf.updater: module initialized");
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            // before the lock and before any network: the hardware rows report what a sync WOULD use, so
            // they have to fill on the boots that never reach one, which is nearly every boot
            bus.ident(cfg.repo(), EngineEntry.version(), cfg.tagPin());
            Optional<InstanceLock> lock = InstanceLock.acquire(dir);
            if (lock.isEmpty()) {
                bus.line("updater: instance lock held, sync skipped");
                return 0;
            }
            try (InstanceLock ignored = lock.get()) {
                SyncCore.stepZero(dir);
                kick = KickGuard.consume(dir, cfg.pins(), bus);

                String tag;
                if (!cfg.tagPin().isEmpty()) {
                    tag = cfg.tagPin(); // pinned version intentionally ignores the server broadcast
                    // the same noun the hardware block's Release Lock row shows, so the log line and the row
                    // read as one fact instead of two
                    bus.line("updater: release lock active, pinned to " + tag);
                } else {
                    Optional<String> broadcast = Doors.broadcast(dir, cfg);
                    if (broadcast.isEmpty()) {
                        bus.line("updater: no carrier, sync skipped");
                        return 0;
                    }
                    // fresh read, NOT versionBefore: step 0 may have just replayed a staged sync
                    // that rewrote bcc, and comparing the broadcast against the pre-replay value
                    // would re-sync a pack that is already current
                    String local = localVersion(dir).orElse("");
                    if (local.isEmpty()) {
                        bus.line("updater: no version stamp in config/bcc-common.toml, sync skipped");
                        return 0;
                    }
                    if (broadcast.get().equals(local)) {
                        Optional<String> missing = SyncCore.firstMissingManaged(dir, cfg.pins());
                        boolean cached = Files.isRegularFile(
                                dir.resolve(".bamfos-updater").resolve("manifest-current.json"));
                        boolean agrees = SyncCore.ledgerAgrees(dir, local);
                        if (missing.isEmpty() && cached && agrees) {
                            bus.line("updater: packages current (" + local + "), nothing to do");
                            return 0;
                        }
                        if (missing.isPresent()) {
                            // A managed file is missing even though the version matches; bypass the shortcut so the
                            // reconciler can restore it, and report it as an integrity issue.
                            bus.line("integrity: " + missing.get() + " missing, restoring");
                        } else if (!cached) {
                            // current but never synced (fresh seed install): run the no-op sync once
                            // anyway, because it backfills the manifest/notes caches the BIOS pages read.
                            // Asked BEFORE the ledger, because a box with no cache never had a last sync and
                            // the line below would be a lie about one
                            bus.line("updater: packages current (" + local
                                    + "), no cached index, backfilling release data");
                        } else {
                            // bcc says the new version but the ledger never got to claim it, so the last
                            // sync left at least one row on its old bytes. The version compare is blind to
                            // that by design (bcc is itself a managed file the release restamps), and this
                            // is the only reason a converged-looking box re-plans (SyncCore.ledgerAgrees)
                            bus.line("updater: last sync left rows unapplied, re-planning");
                        }
                    }
                    tag = broadcast.get(); // upgrades and rollbacks use the same sync path
                }
                code = SyncCore.sync(dir, cfg, tag, versionBefore, false, false,
                        Reconciler.Side.CLIENT, apiBase, bus);
                end = "ok";
                return code;
            }
        } catch (HttpFetcher.RateLimited limited) {
            // Catch RateLimited before TokenDead and never write the token-dead marker: rate limits clear
            // on their own within an hour, while the marker would falsely flag the token as revoked.
            bus.line("updater: github rate limited, sync retries next boot");
            // its OWN end state, never "fail": the realistic trip is mid-download, which is after syncBegin,
            // and UpdaterFeed's END arm turns a failed end into "updater: sync aborted, boot continues" right
            // under the honest line, so the player reads two rows that contradict each other. Quoted exactly,
            // because a reader who greps this string has to land on the line that prints it
            end = "limited";
            return 0;
        } catch (HttpFetcher.TokenDead dead) {
            SyncCore.tokenDead(dir, dead);
            bus.line("AuthEvent: GITHUB token FAILED (" + dead.code + "), updates disabled");
            end = "fail";
            return 0;
        } catch (Exception e) {
            bus.line(abortLine(e)); // the terminal deserves the why-line too
            System.out.println("[updater] sync skipped: " + e); // the game must still boot
            end = "fail";
            return 0;
        } finally {
            bus.end(end);
            // A boot where the sync never got its shot (no carrier, instance locked, token dead) could
            // not have converged ANYTHING, and writing "this cycle changed nothing" there is exactly
            // what flips the next identical kick to the terminal screen. A quarantine still counts, because
            // that landed on disk before the sync was reached.
            String outcome = end;
            kick.filter(k -> "ok".equals(outcome) || !k.quarantined().isEmpty())
                    .ifPresent(k -> recordCycle(dir, k, versionBefore));
        }
    }

    /**
     * The abort line, chosen off the exception TYPE. "sync failed" gives a player nothing to act on, and the
     * type is the only thing at this point that knows the difference: a dead carrier is wait-and-see, while
     * two enabled jars claiming one modid is his own mods folder and the reconciler already refused to move
     * a byte over it, which is an integrity call and not a network one. Anything unrecognised stays
     * deliberately vague, because naming a cause we did not identify is where a log line starts lying.
     */
    static String abortLine(Exception e) {
        if (e instanceof Reconciler.DupeModidAbort) {
            return "integrity: two enabled jars claim one modid, sync aborted";
        }
        if (networkFault(e)) {
            return "updater: repository unreachable, boot continues";
        }
        return "updater: sync aborted, boot continues";
    }

    /** True when the network stack is what threw. The CAUSE CHAIN, not the top type: HttpClient reports a
     *  refused connect as an IOException wrapping a java.net one, and a bare `instanceof IOException` would
     *  read a full disk mid-write as an unreachable repository, which is the opposite of actionable. The
     *  bounded walk is not decoration, a cause chain CAN cycle and this runs on the boot path. */
    private static boolean networkFault(Throwable t) {
        Throwable c = t;
        for (int depth = 0; c != null && depth < 8; depth++) {
            String pkg = c.getClass().getPackageName();
            if ((pkg.equals("java.net") || pkg.equals("java.net.http") || pkg.equals("javax.net.ssl"))
                    && !badUrlString(c)) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    /** The two java.net members that mean a bad STRING and not a dead carrier. A player hand-editing
     *  `repo=SixxHands/bamfos pack` reaches URI.create in the fetcher, which throws IllegalArgumentException
     *  caused by URISyntaxException, and by package alone that reads as "repository unreachable" and sends him
     *  hunting a network problem he does not have while his config file is the bug. */
    private static boolean badUrlString(Throwable c) {
        return c instanceof java.net.URISyntaxException || c instanceof java.net.MalformedURLException;
    }

    /** Records whether this boot changed anything (quarantine or pack version). If nothing changed and
     *  we get the same kick again, we escalate to BAMF_SYNC_CONVERGENCE_FAILED. */
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

    /** The local pack version off BCC's config, same read the BIOS uses, but keeping the v-form
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
