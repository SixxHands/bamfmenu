package com.retro.bamfos.updater.watchdog;

import com.mojang.logging.LogUtils;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.door.Doors;
import com.retro.bamfos.updater.engine.ops.UpdaterOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/* Server update watchdog: polls GitHub for new releases, announces a countdown in chat and MOTD, then
 * calls halt(false) so the wrapper syncs and restarts. Never call halt(true) from the server thread or
 * it deadlocks against itself. */
@Mod(value = "bamfoswatchdog", dist = Dist.DEDICATED_SERVER)
public final class BamfosWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long COOLDOWN_MS = 6L * 60 * 60 * 1000; // storm guard: one restart per target per 6h
    private static final int TICK_SAMPLE_SECONDS = 15; // only caps how late a hang gets detected
    private static final long WRAPPER_WINDOW_MS = 6L * 60 * 60 * 1000; // 6h freshness window for the last-sync summary

    private volatile MinecraftServer server;
    private ScheduledExecutorService poller;
    private Path dir;

    // Volatile because /bamfos reload updates these on the server thread while the poll thread reads them.
    // Fields above are written once in start() before the executor is created.
    private volatile ReleasePoll poll;
    private volatile ScheduledFuture<?> pollTask;
    private volatile int pollSeconds = 300;
    private volatile boolean autoRestart = true;
    private volatile int countdownSeconds = 60;
    /** Set once in start() and read by opReload so reloading config can't re-trigger a restart. */
    private volatile boolean wrapperPresent;

    // Written on the server thread; restartAtTick and tick are volatile because the poll thread reads them
    // (and volatile prevents 64-bit long tearing). Plain tick++ is safe since only the server thread writes it.
    private volatile long tick;
    private volatile long restartAtTick = -1;
    private long warnAtTick = -1;
    /** Set by /bamfos stop, and by restart now on a box with no wrapper (a halt nothing relaunches IS a
     *  stop, whatever the operator called it), before the countdown is armed, so the ONE halt in onTickPost
     *  knows which of the two things it is doing. Server thread only, same as warnAtTick, and never cleared:
     *  the only exit from a stop is the process ending. */
    private boolean stopping;
    private String pendingTarget = "";
    private String announcedTag = "";
    /**
     * Tag cancelled via /bamfos restart cancel so the next poll doesn't re-arm the same countdown.
     * Kept in memory only since a restart clears the need to suppress it.
     */
    private volatile String vetoedTarget = "";
    // Warn-once flags written on the poll thread, but reset from the server thread by
    // /bamfos cleartoken and /bamfos reload, so keep them volatile.
    private volatile boolean tokenDeadLogged;
    private volatile boolean rateLimitedLogged;
    // Server-list MOTD override (server thread only). Non-empty motdOurs also marks the
    // countdown window as active so we don't need a separate boolean.
    private String motdOurs = "";
    private String motdBefore = "";
    private final ZombieWatch zombieWatch = new ZombieWatch(ZombieWatch.STALL_MS); // poll thread only, same rule

    public BamfosWatchdog(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    /**
     * Catch Throwable like onServerStarted since this runs during startup; failing to register /bamfos
     * shouldn't crash the whole server.
     */
    private void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            OpsCommands.register(event.getDispatcher(), this);
        } catch (Throwable t) {
            LOGGER.warn("[watchdog] /bamfos did not register, the server runs without the op commands: {}",
                    t.toString());
        }
    }

    /**
     * Listener boundary: never let a watchdog failure block server startup.
     * Catches Throwable (not just Exception) because a failed jar-in-jar extraction for MiniJson raises
     * NoClassDefFoundError or ExceptionInInitializerError, which NeoForge would turn into a crash report.
     */
    private void onServerStarted(ServerStartedEvent event) {
        try {
            start(event.getServer());
        } catch (Throwable t) {
            LOGGER.warn("[watchdog] did not start, the server runs with no update polling: {}", t.toString());
        }
    }

    private void start(MinecraftServer started) {
        server = started;
        dir = server.getServerDirectory();
        echoPreBootSync(dir); // Echo the pre-JVM shim summary into logs/latest.log, then check once outside the config try-block
        // so the fallback path cannot leave auto_restart true without a wrapper.
        wrapperPresent = WatchdogState.wrapperRan(dir, System.currentTimeMillis(), WRAPPER_WINDOW_MS);
        if (!wrapperPresent) {
            LOGGER.warn("[watchdog] nothing ran the updater before this JVM, so there is no wrapper loop to "
                    + "bring the server back: auto_restart is forced OFF and new releases are announced only. "
                    + "Start the server with run-server.bat to get automatic restarts back.");
        }
        autoRestart = wrapperPresent;
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            poll = new ReleasePoll(cfg.repo(), cfg.githubToken());
            // Fold wrapperRan into the field here so status/log output matches actual restart behavior.
            autoRestart = cfg.autoRestart() && wrapperPresent;
            Properties extra = UpdaterConfig.rawProps(dir);
            // Keep clamping inside WatchdogState so it is unit-tested; a period <= 0 would throw in scheduleAtFixedRate
            // outside this catch and disable polling for the rest of the session.
            pollSeconds = WatchdogState.pollSeconds(extra);
            countdownSeconds = WatchdogState.countdownSeconds(extra);
        } catch (Exception e) {
            // Catch all Exceptions so config load never blocks boot; warn clearly since an empty token 404s on private repos.
            LOGGER.warn("[watchdog] properties unreadable, polling anonymously on the default repo, "
                    + "so a private repo will not answer: {}", e.toString());
            poll = new ReleasePoll("SixxHands/bamfos-pack", "");
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bamfos-watchdog-poll");
            t.setDaemon(true);
            return t;
        });
        // Fixed delay, NOT fixed rate: a slow /bamfos sync on this thread would otherwise queue up a burst of back-to-back GitHub polls and trip rate limits.
        pollTask = poller.scheduleWithFixedDelay(this::checkOnce, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        // Runs on the poll thread since sampling is cheap and ZombieWatch measures elapsed time,
        // so a slow GitHub request only delays detection slightly and avoids a second thread to clean up.
        poller.scheduleWithFixedDelay(this::sampleTicks, TICK_SAMPLE_SECONDS, TICK_SAMPLE_SECONDS, TimeUnit.SECONDS);
        LOGGER.info("[watchdog] up, polling every {}s (auto_restart={}), /bamfos is registered for ops",
                pollSeconds, autoRestart);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    /**
     * Poll thread: resolve latest release, decide, then hop to the server thread to act.
     * Returns the operator reply so /bamfos check shares this exact path; scheduled polls ignore it.
     */
    private OpsBrain.Reply checkOnce() {
        try {
            MinecraftServer s = server;
            if (s == null) {
                return OpsBrain.Reply.bad(OpsBrain.NOT_STARTED);
            }
            if (restartAtTick >= 0) {
                return OpsBrain.Reply.ok(OpsBrain.ARMED_ALREADY); // don't re-decide under a countdown
            }
            // Grab one snapshot for the whole cycle because /bamfos reload can swap this field from the
            // server thread mid-poll, which would mix the old tag with the new error flags.
            ReleasePoll p = poll;
            String latest = p.latestTag().orElse("");
            String problem = OpsBrain.pollProblem(p.tokenDead, p.deadCode, p.rateLimited, p.noRelease,
                    !latest.isEmpty());
            if (p.tokenDead) {
                if (!tokenDeadLogged) { // Warn once per state change and log the actual HTTP status (401 vs 403), matching the client updater.
                    LOGGER.warn("[watchdog] github token FAILED ({}), update polling is dead until it is replaced",
                            p.deadCode);
                    tokenDeadLogged = true;
                }
                return OpsBrain.Reply.bad(problem);
            }
            tokenDeadLogged = false;
            if (p.rateLimited) {
                if (!rateLimitedLogged) {
                    LOGGER.info("[watchdog] github is rate limiting this server, update polling pauses until it lifts");
                    rateLimitedLogged = true;
                }
                return OpsBrain.Reply.ok(problem);
            }
            rateLimitedLogged = false;
            if (!problem.isEmpty()) {
                return OpsBrain.Reply.ok(problem); // offline, or a channel with nothing published on it
            }
            // Use settled version, not raw bcc-common.toml, so a partial sync with held files still triggers a restart.
            String local = Doors.settledVersion(dir).orElse("");
            String pin = pinNow();
            Properties state = WatchdogState.load(dir);
            String lastTarget = state.getProperty("last_target", "");
            int attempts = WatchdogState.attemptsFor(state, latest); // the tag-reset rule lives with its test
            WatchdogBrain.Decision decision = OpsBrain.vetoed(WatchdogBrain.decide(latest, local, pin, autoRestart,
                    lastTarget, WatchdogState.longProp(state, "last_restart_at"),
                    attempts, WatchdogBrain.MAX_RESTART_ATTEMPTS,
                    System.currentTimeMillis(), COOLDOWN_MS), latest, vetoedTarget);
            if (decision != WatchdogBrain.Decision.NOTHING) {
                s.executeIfPossible(() -> act(decision, latest, local, pin, attempts));
            }
            return OpsBrain.Reply.ok(OpsBrain.newsReply(latest, local, pin, decision, countdownSeconds));
        } catch (Throwable t) {
            LOGGER.warn("[watchdog] poll cycle skipped: {}", t.toString()); // never kill the schedule
            return OpsBrain.Reply.bad("the poll did not finish: " + t);
        }
    }

    /**
     * Read the tag= pin fresh on every poll cycle so pinning a version takes effect on the next poll
     * without needing a restart.
     */
    private String pinNow() {
        try {
            return UpdaterConfig.load(dir).tagPin();
        } catch (Exception e) {
            return ""; // start() already warned if the file is unreadable; fall back to unpinned
        }
    }

    /**
     * Poll thread: check the tick counter for a hung server thread and log if stalled.
     * Log-only because a wedged server thread cannot deliver chat until the hang is already over.
     */
    private void sampleTicks() {
        try {
            MinecraftServer s = server;
            if (s == null) {
                return;
            }
            // nanoTime, NOT currentTimeMillis, so an NTP clock jump doesn't fake a two-minute freeze.
            long now = System.nanoTime() / 1_000_000L;
            boolean watchable = ZombieWatch.watchable(s.isRunning(), s.isStopped(), s.isCurrentlySaving());
            switch (zombieWatch.sample(tick, watchable, now)) {
                case WENT_ZOMBIE -> LOGGER.warn("[watchdog] \"brainzzzzz...\" server seems to have gone zombie, "
                        + "no tick in {}s. Restart to bring it back from the dead!", zombieWatch.stalledMs() / 1000);
                case RECOVERED -> LOGGER.info("[watchdog] ticks are moving again after {}s parked, "
                        + "so it was only playing dead", zombieWatch.stalledMs() / 1000);
                case NOTHING -> {
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[watchdog] tick sample skipped: {}", t.toString()); // a throw here cancels the schedule
        }
    }

    /** Server thread: chat + countdown arming. */
    private void act(WatchdogBrain.Decision decision, String target, String local, String pin, int attempts) {
        // Only place we set the update MOTD, gated by UpdateMotd.takes so non-restarting decisions never leave it stuck.
        if (UpdateMotd.takes(decision)) {
            takeMotd(target);
        }
        switch (decision) {
            case RESTART -> {
                if (restartAtTick >= 0) {
                    return;
                }
                try {
                    // Persist the restart attempt before halting to prevent a restart storm on a non-converging sync.
                    // If the disk write fails, refuse to arm and let the next poll retry.
                    if (!WatchdogState.recordRestart(dir, target, System.currentTimeMillis(), attempts)) {
                        giveBackMotd(); // notice is sent and no restart is scheduled
                        if (!announcedTag.equals(target)) {
                            announcedTag = target;
                            broadcast("Pack update " + target + " is pending but this box could not record "
                                    + "the restart, check the server logs");
                            LOGGER.warn("[watchdog] could not write .bamfos-updater/watchdog-state.properties, "
                                    + "so the restart for {} is NOT armed to prevent a restart loop. Free space or "
                                    + "fix permissions on .bamfos-updater; the next poll retries", target);
                        }
                        return;
                    }
                    pendingTarget = target;
                    broadcast("Pack update " + target + " - restarting in " + countdownSeconds + "s");
                    LOGGER.info("[watchdog] new release {} (server on {}), restarting in {}s", target, local, countdownSeconds);
                    restartAtTick = tick + countdownSeconds * 20L;
                    warnAtTick = countdownSeconds > 15 ? restartAtTick - 200 : -1;
                } catch (Throwable orphaned) {
                    // Vanilla swallows server-thread exceptions after logging FATAL, so restore the MOTD before rethrowing
                    // or the server stays up advertising a restart that was never armed.
                    giveBackMotd();
                    throw orphaned;
                }
            }
            case ANNOUNCE_ONLY -> {
                if (!announcedTag.equals(target)) {
                    announcedTag = target;
                    broadcast("Pack update " + target + " is out - restart the server to apply it (auto restart is off)");
                    LOGGER.info("[watchdog] release {} available; auto_restart=false, operator restarts", target);
                }
            }
            case HOLD_COOLDOWN -> {
                if (!announcedTag.equals(target)) {
                    announcedTag = target;
                    broadcast("Pack update " + target + " is pending but the last restart did not apply it, "
                            + "the next attempt waits out the cooldown");
                    LOGGER.warn("[watchdog] pausing after {} restart attempt(s) for {}: the version never "
                            + "converged, so sync is failing on this server (token? disk? offline?). One "
                            + "more attempt follows after the cooldown", attempts, target);
                }
            }
            case HOLD_GIVEUP -> {
                if (!announcedTag.equals(target)) {
                    announcedTag = target;
                    broadcast("Pack update " + target + " is pending but the last restart did not apply it, "
                            + "check the server logs");
                    // Name the state file so operators know how to clear the hold if the same tag is republished with fixed assets.
                    LOGGER.warn("[watchdog] PAUSING after {} restart attempt(s) for {}: the version never "
                            + "converged, so sync is failing on this server (token? disk? offline?). Skipping "
                            + "further restarts for this tag; publish a new tag or delete "
                            + ".bamfos-updater/watchdog-state.properties to reset", attempts, target);
                }
            }
            case HOLD_PINNED -> {
                if (!announcedTag.equals(target)) {
                    announcedTag = target;
                    broadcast("Pack update " + target + " is out but this box is locked to " + pin
                            + " on purpose, so it stays put");
                    LOGGER.info("[watchdog] release {} is out but tag= locks this box to {}; remove tag= "
                            + "from .bamfos-updater/bamfos-updater.properties to take it", target, pin);
                }
            }
            case HOLD_VETOED -> {
                // Do nothing here: /bamfos restart cancel already notified players, and re-announcing
                // every poll would look like the cancel didn't stick. Still visible in status/check.
            }
            case NOTHING -> {
            }
        }
    }

    private void onTickPost(ServerTickEvent.Post event) {
        tick++;
        if (restartAtTick < 0) {
            return;
        }
        if (tick == warnAtTick) {
            broadcast("Restarting in 10s");
        }
        if (tick >= restartAtTick) {
            restartAtTick = -1;
            // Single halt path for both stop and restart so they share the same tick, broadcast, and log.
            // Only the message changes so players aren't told "back in a minute" on a full stop.
            broadcast(stopping ? "Server going down (not restarting automatically)"
                    : "Restarting now - back in a minute");
            // target can be empty when running /bamfos restart now with no update pending
            LOGGER.info("[watchdog] stopping the server{} ({})",
                    pendingTarget.isEmpty() ? " on an operator's ask" : " for " + pendingTarget,
                    stopping ? (wrapperPresent ? "the wrapper reads the stop file and exits"
                            : "nothing here relaunches it")
                            : "the wrapper syncs + relaunches");
            event.getServer().halt(false);
        }
    }

    // /bamfos command handlers (called by OpsCommands)

    /** True if start() didn't finish, so commands can return an error instead of throwing an NPE. */
    private boolean down() {
        return dir == null || poller == null || poll == null;
    }

    /** Server thread. Reads local files only (no GitHub calls) so it responds even during network stalls. */
    OpsBrain.Reply opStatus() {
        if (down()) {
            return OpsBrain.Reply.bad(OpsBrain.NOT_STARTED);
        }
        return OpsBrain.Reply.ok(OpsBrain.statusLines(new UpdaterOps(dir, poll.apiBase).status(),
                autoRestart, pollSeconds, OpsBrain.nextPoll(pollTask), OpsBrain.armedSeconds(restartAtTick, tick),
                vetoedTarget, System.currentTimeMillis()));
    }

    /**
     * Server thread: reloads config and rebuilds ReleasePoll so it does not wait behind a stalled GitHub poll.
     * In-flight cycles finish against their snapshotted poll object; the next cycle picks up the new one.
     */
    OpsBrain.Reply opReload() {
        if (down()) {
            return OpsBrain.Reply.bad(OpsBrain.NOT_STARTED);
        }
        UpdaterConfig cfg;
        Properties extra;
        try {
            cfg = UpdaterConfig.load(dir);
            extra = UpdaterConfig.rawProps(dir);
        } catch (Exception e) {
            // nothing swapped yet, so the old config stays active
            return OpsBrain.Reply.bad("the properties file would not read and nothing changed: " + e);
        }
        int wasPoll = pollSeconds;
        int newPoll = WatchdogState.pollSeconds(extra);
        boolean rescheduled = newPoll == wasPoll || reschedule(newPoll);
        if (rescheduled) {
            pollSeconds = newPoll; // only update this if rescheduling succeeded so status stays accurate
        }
        poll = new ReleasePoll(cfg.repo(), cfg.githubToken());
        autoRestart = cfg.autoRestart() && wrapperPresent; // same fold as start(), see the field
        countdownSeconds = WatchdogState.countdownSeconds(extra);
        tokenDeadLogged = false; // reset warn-once flags so a bad new token still logs a warning
        rateLimitedLogged = false;
        // Pass the raw token strings to OpsBrain so tests can verify it names the source without leaking the value.
        return OpsBrain.Reply.ok(OpsBrain.reloadLines(cfg.repo(),
                extra.getProperty("github_token", ""), cfg.githubToken(),
                autoRestart, newPoll, wasPoll, countdownSeconds, rescheduled));
    }

    /**
     * Replaces the poll schedule for a new interval, returning false if the executor is shutting down.
     * Schedules the new task before cancelling the old one, and uses cancel(false) to avoid interrupting HTTP I/O.
     */
    private boolean reschedule(int seconds) {
        ScheduledExecutorService p = poller;
        if (p == null || p.isShutdown()) {
            return false;
        }
        try {
            // Use fixed delay just like start() so reloading doesn't reintroduce catch-up bursts.
            ScheduledFuture<?> fresh = p.scheduleWithFixedDelay(this::checkOnce, seconds, seconds, TimeUnit.SECONDS);
            ScheduledFuture<?> old = pollTask;
            pollTask = fresh;
            if (old != null) {
                old.cancel(false);
            }
            return true;
        } catch (RuntimeException stopping) {
            return false;
        }
    }

    /** Poll thread (offload only). */
    OpsBrain.Reply opCheck() {
        return checkOnce();
    }

    /** Poll thread only: downloads and stages the pack off the server thread for the next restart. */
    OpsBrain.Reply opSync() {
        return OpsBrain.syncReply(new UpdaterOps(dir, poll.apiBase).serverSyncNow());
    }

    /** Poll thread only: fetches and hashes all managed files, so keep it off the server thread. */
    OpsBrain.Reply opRepair() {
        return OpsBrain.repairReply(new UpdaterOps(dir, poll.apiBase).serverRepair());
    }

    /** Server thread. Clears the dead-token marker; change the token in properties and run /bamfos reload. */
    OpsBrain.Reply opClearToken() {
        if (down()) {
            return OpsBrain.Reply.bad(OpsBrain.NOT_STARTED);
        }
        OpsBrain.Reply reply = OpsBrain.clearTokenReply(new UpdaterOps(dir, poll.apiBase).clearTokenDead());
        tokenDeadLogged = false;
        return reply;
    }

    /** Server thread. Schedules the halt for the next tick so onTickPost handles the stop, chat, and log. */
    OpsBrain.Reply opRestartNow() {
        if (server == null) {
            return OpsBrain.Reply.bad(OpsBrain.NOT_STARTED);
        }
        boolean wasArmed = restartAtTick >= 0;
        vetoedTarget = "";
        warnAtTick = -1; // else a brought-forward countdown could still fire "Restarting in 10s" first
        if (!wrapperPresent) {
            // Without a wrapper nothing relaunches the server, so use stop wording instead of "back in a minute".
            stopping = true;
        }
        restartAtTick = tick;
        return OpsBrain.Reply.ok(OpsBrain.restartNowReply(wasArmed, wrapperPresent));
    }

    /**
     * Server thread: stops the server cleanly, bypassing the wrapper's relaunch loop via a stop sentinel.
     * Writes the sentinel before halting and aborts if the write fails; without a wrapper, just halts.
     */
    OpsBrain.Reply opStop() {
        if (server == null) {
            return OpsBrain.Reply.bad(OpsBrain.NOT_STARTED);
        }
        boolean written = StopFile.write(dir);
        if (wrapperPresent && !written) {
            return OpsBrain.stopReply(true, false); // still up, and the reply says why
        }
        stopping = true;
        vetoedTarget = "";
        warnAtTick = -1;
        pendingTarget = "";
        restartAtTick = tick; // use the same onTickPost halt path as restart now
        return OpsBrain.stopReply(wrapperPresent, written);
    }

    /**
     * Server thread: disarms the countdown and vetoes the tag for this session so the next poll does not re-arm it.
     * Leaves the attempt count in watchdog-state burned.
     */
    OpsBrain.Reply opRestartCancel() {
        if (server == null) {
            return OpsBrain.Reply.bad(OpsBrain.NOT_STARTED);
        }
        if (restartAtTick < 0) {
            // Restore the MOTD even when no countdown is armed, in case an earlier take was orphaned (no-op otherwise).
            giveBackMotd();
            return OpsBrain.Reply.ok(OpsBrain.NOTHING_ARMED);
        }
        String target = pendingTarget;
        restartAtTick = -1;
        warnAtTick = -1;
        pendingTarget = "";
        vetoedTarget = target;
        giveBackMotd(); // The veto skips this release for the rest of the session, so clear the update notice.
        broadcast("Restart cancelled - carry on");
        return OpsBrain.Reply.ok(OpsBrain.restartCancelReply(target));
    }

    /**
     * Runs work on the poll thread and posts the reply on the server thread (returns false if no worker exists).
     * Keeps network I/O off the server thread and CommandSourceStack access on it.
     */
    boolean offload(Supplier<OpsBrain.Reply> work, Consumer<OpsBrain.Reply> answer) {
        MinecraftServer s = server;
        ScheduledExecutorService p = poller;
        if (s == null || p == null) {
            return false;
        }
        try {
            p.execute(() -> {
                OpsBrain.Reply reply;
                try {
                    reply = work.get();
                } catch (Throwable t) {
                    // Catch failures on the worker so the operator gets an error reply instead of silence.
                    reply = OpsBrain.Reply.bad("that did not finish: " + t);
                }
                // Always log the deferred reply too since RCON disconnects right after the initial ack.
                for (String line : reply.lines()) {
                    if (reply.failed()) {
                        LOGGER.warn("[watchdog] {}", line);
                    } else {
                        LOGGER.info("[watchdog] {}", line);
                    }
                }
                OpsBrain.Reply done = reply;
                try {
                    s.executeIfPossible(() -> answer.accept(done));
                } catch (RejectedExecutionException gone) {
                    // executeIfPossible throws once isStopped() flips during shutdown; ignore since the reply was already logged.
                }
            });
            return true;
        } catch (RejectedExecutionException stopping) {
            return false; // the poller is shut down, so the server is already on its way out
        }
    }

    /**
     * Server thread: updates the in-memory server-list MOTD without touching DedicatedServerProperties.
     * Calls invalidateStatus so NeoForge drops its 5-second cached status JSON and short countdowns show up.
     */
    private void takeMotd(String target) {
        MinecraftServer s = server;
        if (s == null || !motdOurs.isEmpty()) {
            return;
        }
        motdBefore = s.getMotd();
        motdOurs = UpdateMotd.motd(target);
        s.setMotd(motdOurs);
        s.invalidateStatus();
    }

    /** Server thread: restores the previous MOTD on any non-halt exit from an armed countdown. */
    private void giveBackMotd() {
        MinecraftServer s = server;
        if (s == null || motdOurs.isEmpty()) {
            return;
        }
        s.setMotd(UpdateMotd.restored(s.getMotd(), motdOurs, motdBefore));
        s.invalidateStatus();
        motdOurs = "";
        motdBefore = "";
    }

    private void broadcast(String msg) {
        MinecraftServer s = server;
        if (s != null) {
            s.getPlayerList().broadcastSystemMessage(Component.literal("[BAMFOS] " + msg), false);
        }
    }

    /**
     * Echoes the pre-JVM shim's one-line sync summary into logs/latest.log if it is from this boot.
     * The freshness window filters out stale summaries from boots where the shim was skipped.
     */
    private void echoPreBootSync(Path dir) {
        WatchdogState.preBootSummary(dir, System.currentTimeMillis(), 60L * 60 * 1000)
                .ifPresent(s -> LOGGER.info("[watchdog] pre-boot updater sync: {}", s));
    }

}
