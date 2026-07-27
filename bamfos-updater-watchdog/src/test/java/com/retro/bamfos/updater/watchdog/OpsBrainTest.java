package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests /bamfos reply wording and veto rules outside @Mod/Brigadier.
 * Ensures throttles, offline states, and unpublished repos are not reported as failures.
 */
class OpsBrainTest {

    private static final long NOW = 1_700_000_000_000L;

    /** Checks actionable wording for the four non-ok poll states; only a dead token is framed as a failure. */
    @Test
    void everyPollProblemGetsItsOwnSentence_andATransientNeverReadsAsAFailure() {
        String dead = OpsBrain.pollProblem(true, 403, false, false, false);
        assertTrue(dead.contains("HTTP 403"), String.valueOf(dead));
        assertTrue(dead.contains("/bamfos reload") && dead.contains("/bamfos cleartoken"), String.valueOf(dead));

        String limited = OpsBrain.pollProblem(false, 0, true, false, false);
        assertTrue(limited.contains("rate limiting"), limited);
        assertFalse(limited.toLowerCase().contains("fail"));

        String none = OpsBrain.pollProblem(false, 0, false, true, false);
        assertTrue(none.contains("no published release"), none);
        assertFalse(none.contains("offline"));
        assertTrue(none.contains("cannot access the repo"), String.valueOf(none));

        String offline = OpsBrain.pollProblem(false, 0, false, false, false);
        assertTrue(offline.contains("offline") && offline.contains("retries"), offline);

        assertEquals("", OpsBrain.pollProblem(false, 0, false, false, true));
    }

    /** Checks tokenDead before rateLimited so a 403 gives one consistent result from ReleasePoll. */
    @Test
    void aDeadTokenOutranksEveryOtherReading() {
        String both = OpsBrain.pollProblem(true, 403, true, true, false);
        assertTrue(both.contains("refused the token"), both);
    }

    /** Verifies /bamfos check reports both the found release and whether the watchdog will restart. */
    @Test
    void newsReply_saysWhatTheWatchdogIsAboutToDo_notJustWhatItFound() {
        assertTrue(OpsBrain.newsReply("v2", "v2", WatchdogBrain.Decision.NOTHING, 60)
                .contains("no new release"));
        assertTrue(OpsBrain.newsReply("v2", "v1", WatchdogBrain.Decision.RESTART, 90)
                .contains("countdown is arming now (90s)"));
        assertTrue(OpsBrain.newsReply("v2", "v1", WatchdogBrain.Decision.ANNOUNCE_ONLY, 60)
                .contains("auto_restart is off"));
        assertTrue(OpsBrain.newsReply("v2", "v1", WatchdogBrain.Decision.HOLD_COOLDOWN, 60)
                .contains("cooldown"));
        String giveup = OpsBrain.newsReply("v2", "v1", WatchdogBrain.Decision.HOLD_GIVEUP, 60);
        assertTrue(giveup.contains("watchdog-state.properties"));
        assertTrue(OpsBrain.newsReply("v2", "v1", WatchdogBrain.Decision.HOLD_VETOED, 60)
                .contains("/bamfos restart now"));
        assertTrue(OpsBrain.newsReply("v2", "", WatchdogBrain.Decision.RESTART, 60)
                .contains("an unknown version"));
    }

    /**
     * Verifies a cancel vetoes the current tag so the next poll does not re-arm, while still allowing
     * a newer release tag.
     */
    @Test
    void vetoed_stopsARestartForTheCancelledTagOnly_andTouchesNothingElse() {
        assertEquals(WatchdogBrain.Decision.HOLD_VETOED,
                OpsBrain.vetoed(WatchdogBrain.Decision.RESTART, "v2", "v2"));
        assertEquals(WatchdogBrain.Decision.RESTART,
                OpsBrain.vetoed(WatchdogBrain.Decision.RESTART, "v3", "v2"));
        assertEquals(WatchdogBrain.Decision.RESTART,
                OpsBrain.vetoed(WatchdogBrain.Decision.RESTART, "v2", ""));
        // Non-restarting decisions pass through unchanged so the veto does not hide the real reason.
        for (WatchdogBrain.Decision d : List.of(WatchdogBrain.Decision.NOTHING,
                WatchdogBrain.Decision.ANNOUNCE_ONLY, WatchdogBrain.Decision.HOLD_COOLDOWN,
                WatchdogBrain.Decision.HOLD_GIVEUP)) {
            assertEquals(d, OpsBrain.vetoed(d, "v2", "v2"), d + " restarts nothing already");
        }
    }

    /** Only two of the six sync codes are the operator's problem. 5 folded into "failed" is the exact bug
     *  the BIOS spent its own code on, and 1 is the pack's to fix by publishing, not the player's. */
    @Test
    void syncReply_keepsAThrottleAndAMissingReleaseOffTheFailureFlag() {
        assertFalse(OpsBrain.syncReply(0).failed());
        assertTrue(OpsBrain.syncReply(0).lines().get(0).contains("next restart"));
        assertFalse(OpsBrain.syncReply(1).failed());
        assertTrue(OpsBrain.syncReply(1).lines().get(0).contains("no published release"));
        assertTrue(OpsBrain.syncReply(2).failed());
        assertFalse(OpsBrain.syncReply(3).failed());
        assertTrue(OpsBrain.syncReply(4).failed());
        assertFalse(OpsBrain.syncReply(5).failed());
        assertTrue(OpsBrain.syncReply(5).lines().get(0).contains("rate limiting"));
        assertTrue(OpsBrain.syncReply(9).failed() && OpsBrain.syncReply(9).lines().get(0).contains("9"));
    }

    @Test
    void clearTokenReply_reportsAnHonestNoOp() {
        assertFalse(OpsBrain.clearTokenReply(0).failed());
        assertTrue(OpsBrain.clearTokenReply(1).lines().get(0).contains("nothing changed"));
        assertFalse(OpsBrain.clearTokenReply(1).failed());
        assertTrue(OpsBrain.clearTokenReply(2).failed());
    }

    /**
     * Passes a fake GitHub token into reloadLines to check that it only prints the token source
     * and never leaks the secret into console output or logs/latest.log.
     */
    @Test
    void reloadLines_nameTheTokenSource_andNeverTheToken() {
        // Built dynamically so TokenLeakGateTest does not flag a PAT-shaped literal in source.
        String secret = "ghp_" + "s".repeat(40);
        String embedded = "ghp_" + "e".repeat(40);
        String fromFile = String.join(" ", OpsBrain.reloadLines("o/r", secret, secret, true, 300, 300, 60, true));
        assertTrue(fromFile.contains("from the properties file"), fromFile);
        assertTrue(fromFile.contains("overrides"));
        assertFalse(fromFile.contains(secret), String.valueOf(fromFile));

        String shim = String.join(" ", OpsBrain.reloadLines("o/r", "", embedded, true, 300, 300, 60, true));
        assertTrue(shim.contains("embedded in the shim"), shim);
        assertFalse(shim.contains(embedded), String.valueOf(shim));

        assertTrue(String.join(" ", OpsBrain.reloadLines("o/r", "   ", embedded, true, 300, 300, 60, true))
                .contains("embedded in the shim"));

        String anonymous = String.join(" ", OpsBrain.reloadLines("o/r", "", "", true, 300, 300, 60, true));
        assertTrue(anonymous.contains("anonymously") && anonymous.contains("private repo"), String.valueOf(anonymous));
    }

    /** Verifies reload warns when rescheduling fails instead of claiming the new interval took effect. */
    @Test
    void reloadLines_onlyClaimTheNewIntervalWhenTheScheduleActuallyTookIt() {
        assertTrue(String.join(" ", OpsBrain.reloadLines("o/r", "tok", "tok", true, 300, 300, 60, true))
                .contains("poll every 300s, unchanged"));
        String moved = String.join(" ", OpsBrain.reloadLines("o/r", "tok", "tok", true, 120, 300, 60, true));
        assertTrue(moved.contains("poll every 120s") && moved.contains("was 300s")
                && moved.contains("rescheduled"), moved);
        String refused = String.join(" ", OpsBrain.reloadLines("o/r", "tok", "tok", true, 120, 300, 60, false));
        assertTrue(refused.contains("poll every 300s still") && refused.contains("could not be rescheduled"), String.valueOf(refused));
    }

    /**
     * Remind operators on both the help page and /bamfos reload output that reload only updates
     * config, not mods.
     */
    @Test
    void theHardLimitIsSaidOutLoud_onHelpAndOnEveryReload() {
        String help = String.join(" ", OpsBrain.help());
        assertTrue(help.contains("FML") && help.contains("mods/"), help);
        assertTrue(help.contains("None of this can swap a mod"));
        assertTrue(help.contains("/bamfos cleartoken") && help.contains("/bamfos reload"));
        assertTrue(String.join(" ", OpsBrain.reloadLines("o/r", "tok", "tok", true, 300, 300, 60, true))
                .contains("FML"));
    }

    /** Verifies status output includes all key fields and flags errors clearly. */
    @Test
    void statusLines_drawTheWholeBoard() {
        Map<String, Object> ops = state("v0.2.5", "", false, "ok", NOW - 300_000);
        List<String> lines = OpsBrain.statusLines(ops, true, 300, 122, -1, "", NOW);
        String all = String.join(" | ", lines);
        assertTrue(all.contains("version v0.2.5"), all);
        assertTrue(all.contains("release lock none"), all);
        assertTrue(all.contains("auto_restart on"), all);
        assertTrue(all.contains("last sync ok, 5m ago"), all);
        assertTrue(all.contains("token ok"), all);
        assertTrue(all.contains("next poll in 122s (every 300s)"), all);
        assertTrue(all.contains("restart not armed"), all);

        String pinned = String.join(" | ", OpsBrain.statusLines(
                state("v0.2.5", "v0.2.2", false, "", 0), true, 300, 10, -1, "", NOW));
        assertTrue(pinned.contains("release lock v0.2.2"), pinned);
        assertTrue(pinned.contains("last sync never"));
    }

    /** Verifies a dead poll schedule and a dead token both stand out clearly instead of rendering as healthy. */
    @Test
    void statusLines_shoutTheTwoStatesThatMeanNobodyIsWatching() {
        String dead = String.join(" | ", OpsBrain.statusLines(
                state("v0.2.5", "", true, "token-dead", NOW - 7_200_000), true, 300, -1, -1, "", NOW));
        assertTrue(dead.contains("token DEAD"), dead);
        assertTrue(dead.contains("/bamfos reload") && dead.contains("/bamfos cleartoken"), dead);
        assertTrue(dead.contains("next poll NEVER"));
        assertTrue(dead.contains("2h ago"), dead);
    }

    /** The two restart states an operator has to be able to tell apart at a glance, because they lead to
     *  opposite commands: one needs cancelling, the other needs taking. */
    @Test
    void statusLines_separateAnArmedCountdownFromOneYouAlreadyCancelled() {
        String armed = String.join(" | ", OpsBrain.statusLines(
                state("v0.2.5", "", false, "ok", NOW), true, 300, 60, 45, "", NOW));
        assertTrue(armed.contains("restart ARMED, 45s out"), armed);
        assertTrue(armed.contains("/bamfos restart cancel"), armed);

        String held = String.join(" | ", OpsBrain.statusLines(
                state("v0.2.5", "", false, "ok", NOW), true, 300, 60, -1, "v0.2.6", NOW));
        assertTrue(held.contains("You cancelled v0.2.6"), held);
        assertTrue(held.contains("will not re-arm itself"));
        assertTrue(held.contains("/bamfos restart now"), held);
    }

    /**
     * Verifies statusLines surfaces UpdaterOps.status() errors without turning unread keys (token_dead,
     * last_sync_result) into false "ok" or "never" claims.
     */
    @Test
    void statusLines_admitWhenTheUpdaterStateWouldNotRead() {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("error", "java.nio.file.AccessDeniedException: ledger.json");
        String all = String.join(" | ", OpsBrain.statusLines(broken, true, 300, 5, -1, "", NOW));
        assertTrue(all.contains("would not read"), all);
        assertTrue(all.contains("AccessDeniedException"));
        assertTrue(all.contains("version unknown"), all);
        assertFalse(all.contains("token ok"), String.valueOf(all));
        assertTrue(all.contains("token unknown"), all);
        assertFalse(all.contains("last sync never"), String.valueOf(all));
        assertTrue(all.contains("last sync unknown"), all);

        // and the other direction, because degrading on the error flag instead of on key presence would swallow a
        // real token_dead=true whenever a LATER read (the sync record) is the thing that threw
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("token_dead", true);
        partial.put("error", "java.nio.file.AccessDeniedException: last-sync.json");
        String half = String.join(" | ", OpsBrain.statusLines(partial, true, 300, 5, -1, "", NOW));
        assertTrue(half.contains("token DEAD"), String.valueOf(half));
    }

    /** restartAtTick is in ticks, not seconds, so make sure status converts it before printing. */
    @Test
    void armedSeconds_convertsTicks_andSaysMinusOneForNotArmed() {
        assertEquals(-1, OpsBrain.armedSeconds(-1, 5000));
        assertEquals(60, OpsBrain.armedSeconds(6200, 5000));
        assertEquals(0, OpsBrain.armedSeconds(5000, 5000));
        assertEquals(0, OpsBrain.armedSeconds(4990, 5000));
    }

    @Test
    void ago_isCoarseEnoughToRead() {
        assertEquals("just now", OpsBrain.ago(0));
        assertEquals("just now", OpsBrain.ago(59_000));
        assertEquals("1m ago", OpsBrain.ago(60_000));
        assertEquals("59m ago", OpsBrain.ago(3_599_000));
        assertEquals("1h ago", OpsBrain.ago(3_600_000));
        assertEquals("3d ago", OpsBrain.ago(3 * 86_400_000L));
    }

    @Test
    void restartReplies_tellHimWhatJustHappenedToTheBox() {
        assertTrue(OpsBrain.restartNowReply(true).contains("brought forward"));
        assertTrue(OpsBrain.restartNowReply(false).contains("nothing was armed"));
        assertTrue(OpsBrain.restartNowReply(false).contains("wrapper"));
        assertTrue(OpsBrain.restartCancelReply("v0.2.6").contains("v0.2.6"));
        assertTrue(OpsBrain.restartCancelReply("v0.2.6").contains("not re-arm"));
        assertEquals("restart cancelled.", OpsBrain.restartCancelReply(""));
    }

    private static Map<String, Object> state(String version, String pin, boolean tokenDead,
                                             String result, long at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("pinned", pin);
        m.put("token_dead", tokenDead);
        if (!result.isEmpty()) {
            m.put("last_sync_result", result);
            m.put("last_sync_at", at);
        }
        return m;
    }
}
