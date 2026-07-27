package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.guard.DisconnectHistory;
import com.retro.bamfos.updater.engine.guard.EngineHealth;
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
        // Tracks whether sync actually finished checking the pack (vs bailing out early). Kept separate from
        // `end` for the kick-history check in the finally block.
        boolean reachedVerdict = false;
        // Default indicator is idle (no GitHub request made). Converged installs exit here normally,
        // so idle must not look like an error.
        String channel = "idle";
        boolean pinned = false;
        try {
            bus.line("com.bamf.updater: kmod start");
            bus.line("com.bamf.updater: module initialized");
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            // before the lock and before any network: the channel row has to fill on the boots that never
            // reach a sync, which is nearly every boot. The NAME only, because nothing here knows yet what github
            // did, and a trailing OK sent from this point would just mean "the door started"
            bus.ident(cfg.repo());
            Optional<InstanceLock> lock = InstanceLock.acquire(dir);
            if (lock.isEmpty()) {
                bus.line("updater: instance lock held, sync skipped");
                // Clear probation on named skips too: the engine ran and decided cleanly, and most boots on a
                // current install end here, so skipping the clear would trigger a false rollback.
                return EngineHealth.proven(dir, 0);
            }
            try (InstanceLock ignored = lock.get()) {
                SyncCore.stepZero(dir);
                kick = KickGuard.consume(dir, cfg.pins(), bus);

                String tag;
                if (!cfg.tagPin().isEmpty()) {
                    tag = cfg.tagPin(); // pinned version intentionally ignores the server broadcast
                    pinned = true; // Show the pinned tag here using the same label as the BIOS status page; the POST row only says
                    // PINNED, so removing this line would hide which tag is pinned.
                    bus.line("updater: release lock active, pinned to " + tag);
                } else {
                    Optional<String> broadcast = Doors.broadcast(dir, cfg);
                    if (broadcast.isEmpty()) {
                        bus.line("updater: no carrier, sync skipped");
                        // Match the row wording to the log line above so they read consistently.
                        channel = "no-carrier";
                        return EngineHealth.proven(dir, 0); // treat no-carrier as offline so it clears probation
                    }
                    // fresh read, NOT versionBefore: step 0 may have just replayed a staged sync
                    // that rewrote bcc, and comparing the broadcast against the pre-replay value
                    // would re-sync a pack that is already current
                    String local = localVersion(dir).orElse("");
                    if (local.isEmpty()) {
                        bus.line("updater: no version stamp in config/bcc-common.toml, sync skipped");
                        return EngineHealth.proven(dir, 0);
                    }
                    if (broadcast.get().equals(local)) {
                        Optional<String> missing = SyncCore.firstMissingManaged(dir, cfg.pins());
                        boolean cached = Files.isRegularFile(
                                dir.resolve(".bamfos-updater").resolve("manifest-current.json"));
                        boolean agrees = SyncCore.ledgerAgrees(dir, local);
                        if (missing.isEmpty() && cached && agrees) {
                            bus.line("updater: packages current (" + local + "), nothing to do");
                            // three checks agreed that this box is already as converged as a boot can make it,
                            // which is a VERDICT and not a skip. The finally is where that distinction pays off
                            reachedVerdict = true;
                            return EngineHealth.proven(dir, 0);
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
                // true: this IS the promoted engine, the shim loaded these very bytes out of
                // .bamfos-updater/engine/, so a sync it finishes is the alibi the probation flag is waiting on
                code = SyncCore.sync(dir, cfg, tag, versionBefore, false, false,
                        Reconciler.Side.CLIENT, apiBase, bus, true);
                end = "ok";
                reachedVerdict = true;
                channel = "ok"; // github responded, so mark the channel check ok
                return code;
            }
        } catch (HttpFetcher.RateLimited limited) {
            // Catch RateLimited before TokenDead and never write the token-dead marker: rate limits clear
            // on their own within an hour, while the marker would falsely flag the token as revoked.
            bus.line("updater: github rate limited, sync retries next boot");
            // write this to last-sync.json too so the BIOS screen doesn't keep showing an old ok status when
            // rate-limited. Downstream treats `limited` as non-fatal.
            SyncCore.recordFailure(dir, "limited", "github rate limited, the next boot retries");
            EngineHealth.proven(dir); // github answered, so the engine is working, see EngineHealth
            // its OWN end state, never "fail": the realistic trip is mid-download, which is after syncBegin,
            // and UpdaterFeed's END arm turns a failed end into "updater: sync aborted, boot continues" right
            // under the honest line, so the player reads two rows that contradict each other. Quoted exactly,
            // because a reader who greps this string has to land on the line that prints it
            end = "limited";
            channel = "ok"; // rate limit still means github responded and the channel is reachable
            return 0;
        } catch (HttpFetcher.TokenDead dead) {
            SyncCore.tokenDead(dir, dead);
            bus.line("AuthEvent: GITHUB token FAILED (" + dead.code + "), updates disabled");
            EngineHealth.proven(dir); // A revoked credential is a token problem, not a broken engine.
            end = "fail";
            channel = "fail"; // Request was refused, so mark the channel row failed.
            return 0;
        } catch (GithubReleases.ReleaseMissing gone) {
            bus.line(abortLine(gone)); // Record token-dead in last-sync.json (not just stdout) so the BIOS UPDATE AND REPAIR row
            // shows the exact cause instead of a generic abort.
            SyncCore.recordFailure(dir, "no-release", gone.alias // Handle the alias branch here too, same as abortLine.
                    ? "the " + gone.repo + " channel has no published release yet, nothing to converge to"
                    : "no release named " + gone.tag + " on " + gone.repo
                            + " (or the token cannot see it), nothing to converge to");
            EngineHealth.proven(dir); // github answered 404 so the engine itself is healthy (see EngineHealth); record a distinct state
            // here since the network and token worked, the requested release just doesn't exist
            end = "no-release";
            // count 404 as a completed check (see finally) since restarting won't make a missing release appear
            reachedVerdict = true;
            // still mark the channel row failed because the target release doesn't exist
            channel = "fail";
            return 0;
        } catch (Exception e) {
            boolean offline = SyncCore.networkFault(e);
            String why = abortLine(e);
            bus.line(why); // the terminal deserves the why-line too
            System.out.println("[updater] sync skipped: " + e); // Always let the game boot, and record offline/failed in last-sync.json so UPDATE AND REPAIR
            // doesn't keep showing a stale "ok" across days of failed boots.
            SyncCore.recordFailure(dir, offline ? "offline" : "failed", why + " (" + e + ")");
            if (EngineHealth.attributable(e)) {
                EngineHealth.proven(dir);
            }
            // a dead carrier gets its OWN end state, same rule the throttle and the missing release already
            // answer to: the specific why-line above ("repository unreachable, boot continues") already told
            // the player what happened, and `fail` makes UpdaterFeed stack "sync aborted, boot continues"
            // underneath it, which is two rows about one router reboot with the vaguer one last
            end = offline ? "offline" : "fail";
            // A sync was attempted and failed here, so never leave the row on idle or ok (e.g., on a
            // duplicate-modid abort) where it would contradict the failure in the POST block.
            channel = offline ? "no-carrier" : "fail";
            return 0;
        } finally {
            // One verdict per boot, from here for the same reason end() is here: every exit runs through it, so
            // no path can leave the row testing forever. The pin only takes the row when nothing louder claimed
            // it, because a refusal or a dead carrier is what he has to act on, and a pinned boot printed
            // "release lock active, pinned to <tag>" in the log either way. BEFORE end(), so END stays the last
            // line on the bus.
            bus.channel(pinned && "ok".equals(channel) ? "pinned" : channel);
            bus.end(end);
            // Record kick history when a quarantine ran or sync finished checking (including "packages
            // current"), so unfixable kicks don't loop restarts forever. Skip it when sync never got a
            // chance (offline, locked, throttled, token-dead) so a retryable boot isn't marked unchanged.
            boolean verdict = reachedVerdict;
            kick.filter(k -> verdict || !k.quarantined().isEmpty())
                    .ifPresent(k -> recordCycle(dir, k, versionBefore));
        }
    }

    /** Picks a specific abort message from the exception type (network fault, missing release, or
     *  duplicate modid). Unrecognized exceptions stay generic rather than guessing a cause. */
    static String abortLine(Exception e) {
        if (e instanceof Reconciler.DupeModidAbort) {
            return "integrity: two enabled jars claim one modid, sync aborted";
        }
        if (e instanceof GithubReleases.ReleaseMissing gone) {
            if (gone.alias) {
                // Only reachable if tag=latest was hand-written in properties; call out the alias explicitly
                // instead of telling the user to look for a literal "latest" tag.
                return "updater: the channel has no published release yet, boot continues";
            }
            // Include the missing tag name so the maintainer knows what needs to be published.
            return "updater: no release named " + gone.tag + " on the channel, boot continues";
        }
        if (SyncCore.networkFault(e)) {
            return "updater: repository unreachable, boot continues";
        }
        return "updater: sync aborted, boot continues";
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
