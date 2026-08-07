package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.updater.OpsBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins each return-code message so statuses stay accurate (e.g., 5 is rate-limited, not a broken
 * updater, and 1 is no-op, not a cleared pin).
 */
class RepairResultTest {

    @Test
    void everySyncCodeGetsItsOwnHonestLine() {
        assertEquals("Update staged. It applies the next time you start the game.",
                RepairResult.ofSync(0).message());
        assertEquals(RepairResult.Kind.RESTART_REQUIRED, RepairResult.ofSync(0).kind());
        assertEquals("update", RepairResult.ofSync(0).restartCause());
        assertEquals("No release to sync to.", RepairResult.ofSync(1).message());
        assertEquals("Sync failed. See the log.", RepairResult.ofSync(2).message());
        assertEquals("Another sync holds the lock.", RepairResult.ofSync(3).message());
        assertEquals("UPDATE TOKEN EXPIRED. Sync is dead until it is replaced.", RepairResult.ofSync(4).message());
        assertEquals("GitHub is rate limiting. Try again later.", RepairResult.ofSync(5).message());
        assertEquals(RepairResult.Kind.REFUSED, RepairResult.ofSync(5).kind());
        assertEquals(RepairResult.Kind.FAILED, RepairResult.ofSync(4).kind());
        assertEquals("Already up to date. Nothing needed changing.", RepairResult.ofSync(6).message());
        assertEquals(RepairResult.Kind.DONE, RepairResult.ofSync(6).kind());
    }

    @Test
    void anEngineThatPredatesAnOpIsNotASuccess() {
        // OpsGateway returns "error: unknown op x" for unsupported ops, which OpsBridge maps to NO_ANSWER.
        // Treating that as 0 would wrongly report work as staged.
        assertEquals("The updater did not answer. See the log.",
                RepairResult.ofSync(OpsBridge.NO_ANSWER).message());
        assertEquals(RepairResult.Kind.FAILED, RepairResult.ofSync(OpsBridge.NO_ANSWER).kind());
        assertEquals("The updater did not answer. See the log.",
                RepairResult.ofUnpin(OpsBridge.NO_ANSWER).message());
    }

    @Test
    void unpinAnswersInTheSameNumbersWithItsOwnMeanings() {
        assertEquals("Release lock cleared. BAMFOS tracks the server again.", RepairResult.ofUnpin(0).message());
        assertEquals(RepairResult.Kind.DONE, RepairResult.ofUnpin(0).kind());
        assertEquals("Nothing was pinned.", RepairResult.ofUnpin(1).message());
        assertEquals(RepairResult.Kind.REFUSED, RepairResult.ofUnpin(1).kind());
        assertEquals("Could not write the release lock. See the log.", RepairResult.ofUnpin(2).message());
    }

    @Test
    void aToggleSaysWhichWayItWentAndWhen() {
        assertEquals("chipped.jar is on next boot.", RepairResult.ofToggle(true, "chipped.jar", false).message());
        assertEquals("chipped.jar is off next boot.", RepairResult.ofToggle(true, "chipped.jar", true).message());
        assertEquals(RepairResult.Kind.STAGED, RepairResult.ofToggle(true, "chipped.jar", true).kind());
        assertEquals("Could not toggle chipped.jar. See the log.",
                RepairResult.ofToggle(false, "chipped.jar", true).message());
        assertEquals(RepairResult.Kind.FAILED, RepairResult.ofToggle(false, "chipped.jar", true).kind());
    }

    @Test
    void theLockedRefusalIsOneSentenceSharedByTheGateAndTheAction() {
        assertEquals("Update in progress. Actions locked.", RepairResult.locked().message());
        assertEquals(RepairResult.locked().message(),
                com.retro.bamfmenu.early.core.repair.api.RepairGate.FILE_SAFE.whyNot(
                        com.retro.bamfmenu.early.core.repair.api.UpdaterState.reading().live(0L, true)));
    }
}
