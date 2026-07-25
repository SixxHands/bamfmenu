package com.retro.bamfos.updater.watchdog;

/**
 * The watchdog's whole decision table, MC-free so it tests in milliseconds. One rule matters more
 * than the rest: the RESTART-STORM GUARD. The wrapper's sync and this poll MUST agree on the target
 * (both resolve /releases/latest), but if a restart ever fails to converge the versions -- door
 * offline, token dead on the box, disk full -- a naive watchdog would bounce the server forever.
 * A target we already restarted for inside the cooldown window is HELD, loudly, until the tag
 * changes or the cooldown lapses.
 */
final class WatchdogBrain {

    enum Decision {
        NOTHING,       // no tag, or already on it
        RESTART,       // announce + countdown + halt; the wrapper does the rest
        ANNOUNCE_ONLY, // auto_restart=false: just notify, let the operator restart manually
        HOLD_STORM     // this exact target already got its restart and nothing converged
    }

    static Decision decide(String latestTag, String localVersion, boolean autoRestart,
                           String lastRestartTarget, long lastRestartAt, long now, long cooldownMs) {
        if (latestTag == null || latestTag.isEmpty() || latestTag.equals(localVersion)) {
            return Decision.NOTHING;
        }
        if (!autoRestart) {
            return Decision.ANNOUNCE_ONLY;
        }
        if (latestTag.equals(lastRestartTarget) && now - lastRestartAt < cooldownMs) {
            return Decision.HOLD_STORM;
        }
        return Decision.RESTART;
    }

    private WatchdogBrain() {
    }
}
