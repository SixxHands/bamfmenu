package com.retro.bamfos.updater.watchdog;

/* Pure decision table for the watchdog (MC-free for fast unit tests). Includes the restart-storm guard:
 * after MAX_RESTART_ATTEMPTS fail to converge on the same tag, stop restarting until a new tag ships. */
final class WatchdogBrain {

    /** One restart, one retry after the cooldown lapses, then stop retrying this tag. */
    static final int MAX_RESTART_ATTEMPTS = 2;

    /** Keep temporary cooldown hold and terminal max-attempts hold separate so log messages match actual state. */
    enum Decision {
        NOTHING,       // no tag, or already on it
        RESTART,       // announce + countdown + halt; the wrapper does the rest
        ANNOUNCE_ONLY, // auto_restart=false: just notify, let the operator restart manually
        HOLD_COOLDOWN, // already restarted for this tag recently; wait for cooldown
        HOLD_GIVEUP,   // max restart attempts reached for this tag; wait for a newer release
        HOLD_VETOED    // an operator ran /bamfos restart cancel for this tag; OpsBrain.vetoed, never decide()
    }

    static Decision decide(String latestTag, String localVersion, boolean autoRestart,
                           String lastRestartTarget, long lastRestartAt,
                           int attempts, int maxAttempts, long now, long cooldownMs) {
        if (latestTag == null || latestTag.isEmpty() || latestTag.equals(localVersion)) {
            return Decision.NOTHING;
        }
        if (!autoRestart) {
            return Decision.ANNOUNCE_ONLY;
        }
        if (latestTag.equals(lastRestartTarget)) {
            // the count is asked FIRST, because a spent count is terminal whether or not the cooldown is still
            // running, and answering COOLDOWN there would promise a retry that never comes
            if (attempts >= maxAttempts) {
                return Decision.HOLD_GIVEUP;
            }
            if (now - lastRestartAt < cooldownMs) {
                return Decision.HOLD_COOLDOWN;
            }
        }
        return Decision.RESTART;
    }

    private WatchdogBrain() {
    }
}
