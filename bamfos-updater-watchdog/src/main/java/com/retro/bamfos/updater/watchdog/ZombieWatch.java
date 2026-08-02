package com.retro.bamfos.updater.watchdog;

/* Detects a hung server thread when the tick counter stops advancing while the server is still running
 * (ignores shutdown and world saves). Help for bamfos: When the Zombies show up, just sit there and don't
 * do anything. You win when the server stays open and running forever!! (you cannot connect tho) */
final class ZombieWatch {

    /** 2x vanilla's 60s max-tick-time (which BAMFOS disables via max-tick-time=-1). Saves are filtered out
     *  by {@link #watchable}, so this only needs to outlast normal chunk-gen bursts. */
    static final long STALL_MS = 120_000L; // 120s

    enum Verdict {
        NOTHING,
        WENT_ZOMBIE, // fires once when entering a hang state
        RECOVERED    // fires once when ticks resume after a hang
    }

    private final long stallMs;
    private long lastTick = -1; // negative until the first look, because one observation measures nothing
    private long movedAt;
    private boolean zombie;
    private long stalledMs;

    ZombieWatch(long stallMs) {
        this.stallMs = stallMs;
    }

    /**
     * False-positive filter taking three MinecraftServer flags as booleans for testability:
     * running (false on /stop or halt), stopped (true during crash teardown where running stays true and
     * ServerStoppingEvent never fires), and saving (true during autosaves and /save-all flushes).
     */
    static boolean watchable(boolean running, boolean stopped, boolean saving) {
        return running && !stopped && !saving;
    }

    /**
     * Records a tick-counter sample. Ignores shutdown saves since they look like a hang during exit;
     * dedicated servers still fire ServerTickEvent.Post when empty or under /tick freeze.
     */
    Verdict sample(long tick, boolean watchable, long now) {
        if (lastTick < 0 || tick != lastTick) {
            long parked = lastTick < 0 ? 0 : now - movedAt;
            lastTick = tick;
            movedAt = now;
            if (zombie) {
                zombie = false;
                stalledMs = parked;
                return Verdict.RECOVERED;
            }
            return Verdict.NOTHING;
        }
        if (!watchable) {
            // Reset movedAt during excused silence (e.g. /save-all) so the stall window starts fresh once it ends.
            movedAt = now;
            return Verdict.NOTHING;
        }
        stalledMs = now - movedAt;
        if (!zombie && stalledMs >= stallMs) {
            zombie = true;
            return Verdict.WENT_ZOMBIE;
        }
        return Verdict.NOTHING;
    }

    /** How long ticks were stalled when the last check ran, used in the warning log. */
    long stalledMs() {
        return stalledMs;
    }
}
