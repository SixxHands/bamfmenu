package com.retro.bamfos.updater.watchdog;

import com.mojang.logging.LogUtils;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.door.Doors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The M6 autonomy host: author updates the player's server by doing nothing but
 * publishing. Poll thread notices a new latest tag -> hop to the server thread -> announce a
 * countdown in chat -> halt(false). The wrapper loop's next cycle syncs and relaunches; no save
 * call needed here, stopServer() saves players + chunks itself on the way down.
 * halt(true) would join the server thread against itself and hang forever, always halt(false)
 * from mod code. Operator sovereignty: auto_restart=false in bamfos-updater.properties means we
 * only ever announce; the player running the box keeps the veto.
 */
@Mod(value = "bamfoswatchdog", dist = Dist.DEDICATED_SERVER)
public final class BamfosWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long COOLDOWN_MS = 6L * 60 * 60 * 1000; // storm guard: one restart per target per 6h
    private static final int TICK_SAMPLE_SECONDS = 15; // only caps how late a hang gets detected

    private volatile MinecraftServer server;
    private ScheduledExecutorService poller;
    private ReleasePoll poll;
    private Path dir;
    private boolean autoRestart = true;
    private int countdownSeconds = 60;

    // Written on the server thread; restartAtTick and tick are volatile because the poll thread reads them
    // (and volatile prevents 64-bit long tearing). Plain tick++ is safe since only the server thread writes it.
    private volatile long tick;
    private volatile long restartAtTick = -1;
    private long warnAtTick = -1;
    private String pendingTarget = "";
    private String announcedTag = "";
    private boolean tokenDeadLogged; // poll thread only: the token-dead WARN fires once per transition
    private boolean rateLimitedLogged;
    private final ZombieWatch zombieWatch = new ZombieWatch(ZombieWatch.STALL_MS); // poll thread only, same rule

    public BamfosWatchdog(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
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
        echoPreBootSync(dir); // surface what the pre-JVM shim did into logs/latest.log, first thing
        int pollSeconds = 300;
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            poll = new ReleasePoll(cfg.repo(), cfg.githubToken());
            autoRestart = cfg.autoRestart();
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
        poller.scheduleAtFixedRate(this::checkOnce, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        // Runs on the poll thread since sampling is cheap and ZombieWatch measures elapsed time,
        // so a slow GitHub request only delays detection slightly and avoids a second thread to clean up.
        poller.scheduleWithFixedDelay(this::sampleTicks, TICK_SAMPLE_SECONDS, TICK_SAMPLE_SECONDS, TimeUnit.SECONDS);
        LOGGER.info("[watchdog] up, polling every {}s (auto_restart={})", pollSeconds, autoRestart);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    /** Poll thread: resolve latest, decide, and only then hop to the server thread to act. */
    private void checkOnce() {
        try {
            MinecraftServer s = server;
            if (s == null || restartAtTick >= 0) {
                return; // countdown already running: don't re-decide under it
            }
            String latest = poll.latestTag().orElse("");
            if (poll.tokenDead) {
                if (!tokenDeadLogged) { // Warn once per state change and log the actual HTTP status (401 vs 403), matching the client updater.
                    LOGGER.warn("[watchdog] github token FAILED ({}), update polling is dead until it is replaced",
                            poll.deadCode);
                    tokenDeadLogged = true;
                }
                return;
            }
            tokenDeadLogged = false;
            if (poll.rateLimited) {
                if (!rateLimitedLogged) {
                    LOGGER.info("[watchdog] github is rate limiting this server, update polling pauses until it lifts");
                    rateLimitedLogged = true;
                }
                return;
            }
            rateLimitedLogged = false;
            String local = Doors.localVersion(dir).orElse("");
            Properties state = WatchdogState.load(dir);
            String lastTarget = state.getProperty("last_target", "");
            int attempts = WatchdogState.attemptsFor(state, latest); // the tag-reset rule lives with its test
            WatchdogBrain.Decision decision = WatchdogBrain.decide(latest, local, autoRestart,
                    lastTarget, WatchdogState.longProp(state, "last_restart_at"),
                    attempts, WatchdogBrain.MAX_RESTART_ATTEMPTS,
                    System.currentTimeMillis(), COOLDOWN_MS);
            if (decision != WatchdogBrain.Decision.NOTHING) {
                s.executeIfPossible(() -> act(decision, latest, local, attempts));
            }
        } catch (Throwable t) {
            LOGGER.warn("[watchdog] poll cycle skipped: {}", t.toString()); // never kill the schedule
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
    private void act(WatchdogBrain.Decision decision, String target, String local, int attempts) {
        switch (decision) {
            case RESTART -> {
                if (restartAtTick >= 0) {
                    return;
                }
                pendingTarget = target;
                // BEFORE the halt so a non-converging box can't storm; the +1 lives in recordRestart, where
                // a test can hold it, because the cooldown alone only spaces the bounces out
                WatchdogState.recordRestart(dir, target, System.currentTimeMillis(), attempts);
                broadcast("Pack update " + target + " - restarting in " + countdownSeconds + "s");
                LOGGER.info("[watchdog] new release {} (server on {}), restarting in {}s", target, local, countdownSeconds);
                restartAtTick = tick + countdownSeconds * 20L;
                warnAtTick = countdownSeconds > 15 ? restartAtTick - 200 : -1;
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
            broadcast("Restarting now - back in a minute");
            LOGGER.info("[watchdog] stopping the server for {} (the wrapper syncs + relaunches)", pendingTarget);
            event.getServer().halt(false);
        }
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
