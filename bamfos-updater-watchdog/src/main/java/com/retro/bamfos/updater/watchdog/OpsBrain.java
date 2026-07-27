package com.retro.bamfos.updater.watchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/* Formats all /bamfos command replies and decisions (MC-free so JUnit tests can cover every edge case
 * like dead tokens, rate limits, and offline states). Transients never report as hard failures. */
final class OpsBrain {

    /** Command reply: lines to print and whether to show them as an error (red). */
    record Reply(List<String> lines, boolean failed) {

        static Reply ok(String... lines) {
            return new Reply(List.of(lines), false);
        }

        static Reply ok(List<String> lines) {
            return new Reply(List.copyOf(lines), false);
        }

        static Reply bad(String... lines) {
            return new Reply(List.of(lines), true);
        }
    }

    static final String NOT_STARTED = "the watchdog did not start this boot, so none of this can run. "
            + "Look for a [watchdog] line in logs/latest.log.";
    static final String NO_WORKER = "the poll thread is not running (the watchdog did not start, or the "
            + "server is shutting down), so nothing was queued.";
    static final String ARMED_ALREADY = "a restart is already armed, and nothing re-decides under a "
            + "countdown. /bamfos restart cancel to call it off, /bamfos restart now to take it.";
    static final String NOTHING_ARMED = "no restart is armed, so there is nothing to cancel.";
    /** Both acks name the LOG as well as "here", because the answer is deferred and an rcon client is one
     *  request and one response: that operator's connection is gone before the poll thread comes back, so
     *  promising him the answer here and nowhere else is a promise this cannot keep. */
    static final String CHECK_STARTED = "polling github now, the answer lands here and in logs/latest.log.";
    static final String SYNC_STARTED = "sync started, the answer lands here and in logs/latest.log when it "
            + "finishes. A full pack takes a while, the server keeps running the whole time.";

    /**
     * Notes on the help page that /bamfos reload only reloads config, not mods.
     * FML locks mod jars for the whole session, so updates have to run before the JVM starts.
     */
    static final String HARD_LIMIT = "None of this can swap a mod. FML opens every jar in mods/ at boot and "
            + "keeps it open all session, so a pack update always needs the restart the wrapper does. "
            + "Here, reload re-reads config and sync stages files for the next restart.";

    static List<String> help() {
        return List.of(
                "/bamfos status ....... version, release lock, last sync, token, next poll, armed restart",
                "/bamfos reload ....... re-read .bamfos-updater/bamfos-updater.properties, no restart needed",
                "/bamfos check ........ poll github now instead of waiting out the interval",
                "/bamfos sync ......... download the release now, it applies on the next restart",
                "/bamfos restart now|cancel ... take an armed countdown now, or call it off",
                "/bamfos cleartoken ... drop the token-dead marker after you fix the credential",
                "Fixing a dead token is two halves: put the new one in the properties file and /bamfos "
                        + "reload, then /bamfos cleartoken to retire the memory of the old one.",
                HARD_LIMIT);
    }

    // ------------------------------------------------------------------ check

    /**
     * Formats an operator message when a poll could not get a release, or empty if it succeeded.
     * Checks tokenDead first because a 403 can be either a throttle or a bad token, already resolved by ReleasePoll.
     */
    static String pollProblem(boolean tokenDead, int deadCode, boolean rateLimited, boolean noRelease,
                              boolean gotTag) {
        if (tokenDead) {
            // Print the actual HTTP status (401 vs 403) so operators know whether to check token expiry or permissions.
            return "github refused the token (HTTP " + deadCode + "), so update polling is dead until it is "
                    + "replaced. Put github_token= in .bamfos-updater/bamfos-updater.properties, then "
                    + "/bamfos reload and /bamfos cleartoken.";
        }
        if (rateLimited) {
            return "github is rate limiting this server, so polling pauses until it lifts. Nothing is broken, "
                    + "it clears itself.";
        }
        if (noRelease) {
            // Mention both 404 causes (no published non-draft release, or a private repo the token cannot read).
            return "no published release on that channel to sync to (drafts and prereleases are skipped), or "
                    + "this server's token cannot access the repo.";
        }
        if (!gotTag) {
            return "no answer from github this cycle, so this server looks offline. The next poll retries.";
        }
        return "";
    }

    /** Reports both what the poll found and what the watchdog will do next so operators do not have to check logs. */
    static String newsReply(String latest, String local, WatchdogBrain.Decision decision, int countdownSeconds) {
        String on = local.isEmpty() ? "an unknown version" : local;
        String found = "release " + latest + " is out and this server is on " + on + ", ";
        return switch (decision) {
            case NOTHING -> "no new release, this server is on " + on + " and that is the latest.";
            case RESTART -> found + "so the countdown is arming now (" + countdownSeconds + "s).";
            case ANNOUNCE_ONLY -> found + "and auto_restart is off. Run /bamfos restart now when ready.";
            case HOLD_COOLDOWN -> found + "but the last restart did not apply it, so the next attempt waits "
                    + "out the cooldown.";
            case HOLD_GIVEUP -> found + "but the restarts for it are spent and it never converged, so "
                    + "nothing restarts again for this tag. Fix the sync, then publish a new tag or delete "
                    + ".bamfos-updater/watchdog-state.properties.";
            case HOLD_VETOED -> found + "and you cancelled its restart, so nothing re-arms it on its own. "
                    + "/bamfos restart now to take it.";
        };
    }

    /**
     * Applies the operator's per-session veto outside WatchdogBrain.decide.
     * Only RESTART can be vetoed since every other decision already skips restarting.
     */
    static WatchdogBrain.Decision vetoed(WatchdogBrain.Decision decision, String target, String vetoedTarget) {
        boolean held = decision == WatchdogBrain.Decision.RESTART
                && !vetoedTarget.isEmpty() && vetoedTarget.equals(target);
        return held ? WatchdogBrain.Decision.HOLD_VETOED : decision;
    }

    // ------------------------------------------------------------------ sync

    /** One sentence per UpdaterOps code, and only two of the six are painted as failures. */
    static Reply syncReply(int code) {
        return switch (code) {
            case 0 -> Reply.ok("sync staged. The files are downloaded and the journal is written, so the next "
                    + "restart applies them in seconds. Run /bamfos restart now when ready.");
            case 1 -> Reply.ok("nothing to sync to: no published release under that name (drafts and "
                    + "prereleases are skipped), or this server's token cannot access the repo.");
            case 3 -> Reply.ok("another sync already has the updater lock, so this one did not start. "
                    + "Try again in a moment.");
            case 4 -> Reply.bad("github refused the token, so nothing was fetched. Replace it, then "
                    + "/bamfos reload and /bamfos cleartoken.");
            case 5 -> Reply.ok("github is rate limiting this server, so nothing was staged. It clears itself, "
                    + "try again later.");
            case 2 -> Reply.bad("the sync did not finish and nothing applied. Read "
                    + ".bamfos-updater/server-sync.log for the reason; the next boot syncs again anyway.");
            // Don't guess a message for an unknown exit code; just print the code.
            default -> Reply.bad("sync returned unknown code " + code + ". "
                    + "Check .bamfos-updater/server-sync.log.");
        };
    }

    /**
     * Formats the result of clearTokenDead(). Code 1 is a no-op so we don't tell the operator
     * we cleared a marker that wasn't there.
     */
    static Reply clearTokenReply(int code) {
        return switch (code) {
            case 0 -> Reply.ok("token-dead marker cleared. /bamfos check proves the new credential; if it "
                    + "is still wrong, the next refusal writes the marker straight back.");
            case 1 -> Reply.ok("there was no token-dead marker, so nothing changed.");
            default -> Reply.bad("could not delete .bamfos-updater/token-dead, check its permissions.");
        };
    }

    // ------------------------------------------------------------------ reload

    /**
     * Formats the /bamfos reload summary. Prints only the token source (never its value) since output goes
     * to logs/latest.log; accepts the raw token so tests can verify it is never interpolated.
     */
    static List<String> reloadLines(String repo, String tokenFromFile, String resolvedToken,
                                    boolean autoRestart, int pollSeconds, int wasPollSeconds,
                                    int countdownSeconds, boolean rescheduled) {
        String token = !tokenFromFile.trim().isEmpty()
                ? "from the properties file (it overrides the one in the shim)"
                : !resolvedToken.isEmpty() ? "the one embedded in the shim"
                : "none, polling anonymously, so a private repo will not answer";
        String poll;
        if (pollSeconds == wasPollSeconds) {
            poll = "poll every " + pollSeconds + "s, unchanged";
        } else if (rescheduled) {
            poll = "poll every " + pollSeconds + "s, was " + wasPollSeconds + "s, the poller is rescheduled";
        } else {
            // Tell the operator if the poll interval changed in the file but rescheduling failed.
            poll = "poll every " + wasPollSeconds + "s still, the poller could not be rescheduled to "
                    + pollSeconds + "s and the old interval stands until a restart";
        }
        return List.of(
                "properties re-read.",
                "repo " + repo + " | token " + token,
                "auto_restart " + (autoRestart ? "on" : "off") + " | countdown " + countdownSeconds + "s | "
                        + poll,
                "The tag= pin is read fresh by every sync, so it never needed a reload.",
                HARD_LIMIT);
    }

    // ------------------------------------------------------------------ status

    /**
     * Formats the /bamfos status report from UpdaterOps.status() and live watchdog state.
     * Treats nextPollSeconds < 0 as a dead poller, and checks key presence (not just error) so unreadable
     * fields are never reported as healthy while earlier present keys like token_dead still appear.
     */
    static List<String> statusLines(Map<String, Object> ops, boolean autoRestart, int pollSeconds,
                                    long nextPollSeconds, long armedSeconds, String vetoedTarget, long now) {
        List<String> out = new ArrayList<>();
        String err = str(ops.get("error"));
        if (!err.isEmpty()) {
            out.add("the updater state would not read: " + err);
        }
        String pin = str(ops.get("pinned"));
        out.add("version " + orUnknown(str(ops.get("version")))
                + " | release lock " + (pin.isEmpty() ? "none" : pin)
                + " | auto_restart " + (autoRestart ? "on" : "off"));
        out.add("last sync " + lastSync(ops, now));
        out.add(!ops.containsKey("token_dead")
                ? "token unknown, the read did not get that far."
                : Boolean.TRUE.equals(ops.get("token_dead"))
                ? "token DEAD, github refused it. Replace it, then /bamfos reload and /bamfos cleartoken."
                : "token ok, nothing has refused it.");
        out.add(nextPollSeconds < 0
                ? "next poll NEVER, the poll schedule is not running. Restart the server to get it back."
                : nextPollSeconds == 0
                ? "next poll due now (every " + pollSeconds + "s), so a poll or a sync has the thread."
                : "next poll in " + nextPollSeconds + "s (every " + pollSeconds + "s)");
        if (armedSeconds >= 0) {
            out.add("restart ARMED, " + armedSeconds + "s out. /bamfos restart cancel to call it off.");
        } else if (!vetoedTarget.isEmpty()) {
            out.add("restart not armed. You cancelled " + vetoedTarget + ", so it will not re-arm itself "
                    + "before the next boot. /bamfos restart now to take it.");
        } else {
            out.add("restart not armed.");
        }
        return out;
    }

    /**
     * Formats the poll status with its age. Either alone is misleading: "ok" hides a week-old
     * stale check, and a bare timestamp doesn't say whether it succeeded.
     */
    private static String lastSync(Map<String, Object> ops, long now) {
        String result = str(ops.get("last_sync_result"));
        if (result.isEmpty()) {
            // Don't claim "never synced" if an earlier status() error stopped before reading the sync record.
            return ops.containsKey("error")
                    ? "unknown, the updater state would not read."
                    : "never, this server has no sync record yet.";
        }
        long at = ops.get("last_sync_at") instanceof Number n ? n.longValue() : 0L;
        return at <= 0 || at > now ? result + ", at an unknown time." : result + ", " + ago(now - at) + ".";
    }

    /**
     * Returns seconds until the next poll, or -1 ONLY when no poller is active.
     * ScheduledThreadPoolExecutor reports a negative delay while a fixed-delay task is running (e.g. during
     * /bamfos sync), so clamp running tasks to 0; kept here so unit tests cover it.
     */
    static long nextPoll(java.util.concurrent.ScheduledFuture<?> task) {
        if (task == null || task.isCancelled() || task.isDone()) {
            return -1;
        }
        return Math.max(0, task.getDelay(java.util.concurrent.TimeUnit.SECONDS));
    }

    /**
     * Returns the countdown in seconds, or -1 if no restart is scheduled.
     * Converts restartAtTick (20 ticks/sec) here so callers don't accidentally print raw ticks as seconds.
     */
    static long armedSeconds(long restartAtTick, long tick) {
        return restartAtTick < 0 ? -1 : Math.max(0, (restartAtTick - tick) / 20);
    }

    /** Rounded to rough units since operators only need to see if the last poll is stale. */
    static String ago(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m ago";
        }
        if (seconds < 86400) {
            return seconds / 3600 + "h ago";
        }
        return seconds / 86400 + "d ago";
    }

    static String restartNowReply(boolean wasArmed) {
        return wasArmed
                ? "countdown brought forward, the server stops on the next tick and the wrapper syncs and "
                        + "relaunches it."
                : "nothing was armed, restarting anyway. The server stops on the next tick and the wrapper "
                        + "syncs and relaunches it.";
    }

    static String restartCancelReply(String target) {
        return target.isEmpty()
                ? "restart cancelled."
                : "restart for " + target + " cancelled. It will not re-arm itself before the next boot, so "
                        + "/bamfos restart now when you are ready.";
    }

    private static String orUnknown(String s) {
        return s.isEmpty() ? "unknown" : s;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    private OpsBrain() {
    }
}
