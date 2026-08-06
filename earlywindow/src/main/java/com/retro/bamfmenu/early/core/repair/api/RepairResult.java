package com.retro.bamfmenu.early.core.repair.api;

import com.retro.bamfmenu.early.core.updater.OpsBridge;

/**
 * One-line action result for the panel.
 * This is the ONLY place raw engine return codes become user-facing text.
 */
public record RepairResult(Kind kind, String message) {

    /** Outcome category used by the page to pick a text color. */
    public enum Kind {
        /** Action succeeded and changes will apply on the next boot. */
        STAGED,
        /** Done and already true on disk. */
        DONE,
        /** Action was skipped without error (e.g. nothing to do or another write is active). */
        REFUSED,
        /** It went wrong, and the log has the rest. */
        FAILED,
        /**
         * Succeeded, and the game must close before it takes effect.
         * Separate kind (not a boolean on DONE) so callers cannot miss the farewell and exit flow.
         */
        RESTART_REQUIRED
    }

    /* Exit codes for sync ops (repair, syncNow, syncTo). Code 5 gets a separate message because
       GitHub rate limits clear on their own within an hour and shouldn't look like a broken updater. */
    public static RepairResult ofSync(int code) {
        return switch (code) {
            case 0 -> new RepairResult(Kind.STAGED, "Changes staged. Applies on next boot.");
            case 1 -> new RepairResult(Kind.REFUSED, "No release to sync to.");
            case 3 -> new RepairResult(Kind.REFUSED, "Another sync holds the lock.");
            case 4 -> new RepairResult(Kind.FAILED, "UPDATE TOKEN EXPIRED. Sync is dead until it is replaced.");
            case 5 -> new RepairResult(Kind.REFUSED, "GitHub is rate limiting. Try again later.");
            // 6 means already current with nothing staged (avoids pointless reboots and lets Verify report clean).
            // Older engines return 0 here.
            case 6 -> new RepairResult(Kind.DONE, "Already up to date. Nothing needed changing.");
            case OpsBridge.NO_ANSWER -> noAnswer();
            default -> new RepairResult(Kind.FAILED, "Sync failed. See the log.");
        };
    }

    /* unpin uses the same numeric exit codes with different meanings: 1 means nothing was pinned
       (a no-op, not an error or a cleared pin). */
    public static RepairResult ofUnpin(int code) {
        return switch (code) {
            case 0 -> new RepairResult(Kind.DONE, "Release lock cleared. BAMFOS tracks the server again.");
            case 1 -> new RepairResult(Kind.REFUSED, "Nothing was pinned.");
            case OpsBridge.NO_ANSWER -> noAnswer();
            default -> new RepairResult(Kind.FAILED, "Could not write the release lock. See the log.");
        };
    }

    /**
     * Optional mod toggle result: true is STAGED (never DONE), since renames apply on next boot.
     * False means the engine refused and wrote the reason to the log.
     */
    public static RepairResult ofToggle(boolean ok, String name, boolean nowDisabled) {
        if (!ok) {
            return new RepairResult(Kind.FAILED, "Could not toggle " + name + ". See the log.");
        }
        return new RepairResult(Kind.STAGED, name + (nowDisabled ? " is off" : " is on") + " next boot.");
    }

    /** Shared result returned by repair actions while another engine write is already running. */
    public static RepairResult locked() {
        return new RepairResult(Kind.REFUSED, "Update in progress. Actions locked.");
    }

    /** The engine returned something other than a status code, so the operation didn't run. */
    public static RepairResult noAnswer() { // Unsupported op on an older engine, or the call threw an error.
        return new RepairResult(Kind.FAILED, "The updater did not answer. See the log.");
    }
}
