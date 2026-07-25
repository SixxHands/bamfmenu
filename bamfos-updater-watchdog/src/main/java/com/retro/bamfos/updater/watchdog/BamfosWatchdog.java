package com.retro.bamfos.updater.watchdog;

import com.mojang.logging.LogUtils;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
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

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * halt(true) would join the server thread against itself and hang forever -- always halt(false)
 * from mod code. Operator sovereignty: auto_restart=false in bamfos-updater.properties means we
 * only ever announce; the player running the box keeps the veto.
 */
@Mod(value = "bamfoswatchdog", dist = Dist.DEDICATED_SERVER)
public final class BamfosWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long COOLDOWN_MS = 6L * 60 * 60 * 1000; // storm guard: one restart per target per 6h

    private volatile MinecraftServer server;
    private ScheduledExecutorService poller;
    private ReleasePoll poll;
    private Path dir;
    private boolean autoRestart = true;
    private int countdownSeconds = 60;

    // server-thread state (tick handler + act() write there); restartAtTick is ALSO read by the
    // poll thread as its don't-re-decide guard, so it needs the volatile happens-before edge
    private long tick;
    private volatile long restartAtTick = -1;
    private long warnAtTick = -1;
    private String pendingTarget = "";
    private String announcedTag = "";

    public BamfosWatchdog(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        dir = server.getServerDirectory();
        int pollSeconds = 300;
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            poll = new ReleasePoll(cfg.repo(), cfg.githubToken());
            autoRestart = cfg.autoRestart();
            Properties extra = extraProps(dir);
            pollSeconds = intProp(extra, "watchdog_poll_seconds", 300);
            countdownSeconds = intProp(extra, "watchdog_countdown_seconds", 60);
        } catch (IOException e) {
            LOGGER.warn("[watchdog] properties unreadable, using defaults: {}", e.toString());
            poll = new ReleasePoll("SixxHands/bamfos-pack", "");
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bamfos-watchdog-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(this::checkOnce, pollSeconds, pollSeconds, TimeUnit.SECONDS);
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
                LOGGER.warn("[watchdog] github token FAILED (401/403) - update polling is dead until it is replaced");
                return;
            }
            String local = localVersion(dir);
            Properties state = loadState(dir);
            WatchdogBrain.Decision decision = WatchdogBrain.decide(latest, local, autoRestart,
                    state.getProperty("last_target", ""), longProp(state, "last_restart_at"),
                    System.currentTimeMillis(), COOLDOWN_MS);
            if (decision != WatchdogBrain.Decision.NOTHING) {
                s.executeIfPossible(() -> act(decision, latest, local));
            }
        } catch (Throwable t) {
            LOGGER.warn("[watchdog] poll cycle skipped: {}", t.toString()); // never kill the schedule
        }
    }

    /** Server thread: chat + countdown arming. */
    private void act(WatchdogBrain.Decision decision, String target, String local) {
        switch (decision) {
            case RESTART -> {
                if (restartAtTick >= 0) {
                    return;
                }
                pendingTarget = target;
                saveState(dir, target); // recorded BEFORE the halt so a non-converging box can't storm
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
            case HOLD_STORM -> {
                if (!announcedTag.equals(target)) {
                    announcedTag = target;
                    broadcast("Pack update " + target + " is pending but the last restart did not apply it - check the server logs");
                    LOGGER.warn("[watchdog] HOLDING: already restarted for {} and the version never converged - "
                            + "sync is failing on this box (token? disk? offline?), fix that first", target);
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

    // ---- small file plumbing ----

    /** The server's own version, read the same way BCC broadcasts it. */
    private static String localVersion(Path dir) {
        try {
            Path bcc = dir.resolve("config").resolve("bcc-common.toml");
            for (String line : Files.readAllLines(bcc, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("modpackVersion")) {
                    int a = s.indexOf('"');
                    int b = s.lastIndexOf('"');
                    if (a >= 0 && b > a) {
                        return s.substring(a + 1, b);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private static Properties extraProps(Path dir) throws IOException {
        Properties p = new Properties();
        Path f = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        if (Files.isRegularFile(f)) {
            p.load(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
        }
        return p;
    }

    private static Properties loadState(Path dir) {
        Properties p = new Properties();
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("watchdog-state.properties");
            if (Files.isRegularFile(f)) {
                p.load(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
        }
        return p;
    }

    private static void saveState(Path dir, String target) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("watchdog-state.properties");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "last_target=" + target + "\nlast_restart_at=" + System.currentTimeMillis() + "\n");
        } catch (IOException e) {
            LOGGER.warn("[watchdog] could not record the restart attempt: {}", e.toString());
        }
    }

    private static int intProp(Properties p, String key, int dflt) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(dflt)).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static long longProp(Properties p, String key) {
        try {
            return Long.parseLong(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
