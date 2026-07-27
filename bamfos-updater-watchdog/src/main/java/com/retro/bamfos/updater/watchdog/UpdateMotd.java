package com.retro.bamfos.updater.watchdog;

// In-memory MOTD override during a restart countdown (never edits server.properties, so a crash won't leave a stale update notice behind).
final class UpdateMotd {

    // Matches UpdatingNotice.headline word for word so players watching their server list see one continuous status across the restart.
    static String updating(String tag) {
        return tag.isEmpty() ? "The server is updating!" : "Updating the server to " + tag + "!";
    }

    /** Two-line MOTD shown in the server list. */
    static String motd(String tag) {
        return updating(tag) + "\nRestarting shortly to apply it";
    }

    /**
     * Returns true only for decisions that start a countdown; hold states and announce-only leave the MOTD
     * alone since they have no cancel path. Unknown decisions return false.
     */
    static boolean takes(WatchdogBrain.Decision decision) {
        return decision == WatchdogBrain.Decision.RESTART;
    }

    /**
     * Restores the prior MOTD only if ours is still active, avoiding overwriting changes made mid-countdown.
     * Using setMotd also composes cleanly with BCC's betterStatus without a status mixin.
     */
    static String restored(String current, String ours, String original) {
        return current.equals(ours) ? original : current;
    }

    private UpdateMotd() {
    }
}
