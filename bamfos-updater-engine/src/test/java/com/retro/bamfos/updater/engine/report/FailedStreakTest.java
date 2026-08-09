package com.retro.bamfos.updater.engine.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests T6 failed-streak reporting: `pending` clears the streak like `ok`/`staged`, fingerprints stay stable
 * across sync results so one streak writes one file, and cleared streaks stop rewriting reports.
 */
class FailedStreakTest {

    @TempDir
    Path dir;

    private void boot(String result) throws IOException {
        ReportFixture.lastSync(dir, result);
        TroubleReport.consider(dir, "client");
    }

    private int files() {
        return ReportFixture.reports(dir).size();
    }

    /** ok, staged, and pending all count as converged. Skipping pending used to leave stale reports behind. */
    @Test
    void everyConvergedWordClearsTheStreakAndPendingIsOneOfThem() {
        for (String converged : new String[] {"ok", "staged", "pending"}) {
            ReportState.noteSyncResult(dir, "failed");
            ReportState.noteSyncResult(dir, "failed");
            ReportState.noteSyncResult(dir, "failed");
            assertEquals(3, ReportState.failedStreak(dir));

            ReportState.noteSyncResult(dir, converged);

            assertEquals(0, ReportState.failedStreak(dir),
                    converged + " means sync converged, so the failure streak should reset");
        }
    }

    /**
     * Exclude lastSyncResult from the streak fingerprint so changing error states don't spawn duplicate reports.
     */
    @Test
    void oneFailingStreakIsOneFileHoweverTheResultWordMoves() throws IOException {
        boot("failed");
        assertEquals(0, files());
        boot("failed");
        assertEquals(0, files());
        boot("failed");
        assertEquals(1, files());

        // four more boots with different non-converged, non-failure outcomes
        boot("offline");
        boot("limited");
        boot("no-release");
        boot("token-dead");

        assertEquals(1, files());
        assertTrue(ReportFixture.read(ReportFixture.reports(dir).get(0)).contains("failed-streak"));
    }

    /**
     * Rewrite the full body on repeat streak fires so the reported failure count updates instead of freezing at 3.
     */
    @Test
    void theFailureCountInTheFileIsTheCountRightNow() throws IOException {
        for (int failed = 0; failed < 5; failed++) {
            boot("failed");
        }

        assertEquals(1, files());
        String body = ReportFixture.read(ReportFixture.reports(dir).get(0));
        assertEquals(5, ReportState.failedStreak(dir));
        assertTrue(body.contains("failed outright  5"), body);
        assertFalse(body.contains("failed outright  3"));
        assertTrue(body.contains("This has now happened 3 times"));
    }

    /** After five non-failing boots, the failure streak expires and we stop rewriting the report file. */
    @Test
    void aStreakThatStopsFailingEventuallyStopsShouting() throws IOException {
        boot("failed");
        boot("failed");
        boot("failed");
        assertEquals(1, files());
        String afterTheFault = ReportFixture.read(ReportFixture.reports(dir).get(0));
        assertTrue(afterTheFault.contains("failed outright  3"), afterTheFault);

        for (int quiet = 0; quiet < 5; quiet++) {
            boot("offline");
        }

        assertEquals(0, ReportState.failedStreak(dir));
        // seven more boots shouldn't rewrite the file (the header count would change if it did)
        String before = ReportFixture.read(ReportFixture.reports(dir).get(0));
        for (int quiet = 0; quiet < 7; quiet++) {
            boot("offline");
        }
        assertEquals(1, files());
        assertEquals(before, ReportFixture.read(ReportFixture.reports(dir).get(0)));
    }
}
