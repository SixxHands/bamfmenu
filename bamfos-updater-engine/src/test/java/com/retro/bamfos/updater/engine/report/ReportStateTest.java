package com.retro.bamfos.updater.engine.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for report state persistence: streak counters, dedupe rows, and hand-edited files. */
class ReportStateTest {

    @TempDir
    Path dir;

    /**
     * `offline`, `limited`, and `no-release` are external conditions rather than local failures, so they must
     * neither increment nor reset the failure streak.
     */
    @Test
    void onlyFailedMovesTheStreakAndOnlyOkOrStagedClearsIt() {
        ReportState.noteSyncResult(dir, "failed");
        ReportState.noteSyncResult(dir, "failed");
        assertEquals(2, ReportState.failedStreak(dir));

        ReportState.noteSyncResult(dir, "offline");
        ReportState.noteSyncResult(dir, "limited");
        ReportState.noteSyncResult(dir, "no-release");
        ReportState.noteSyncResult(dir, "token-dead");
        assertEquals(2, ReportState.failedStreak(dir));

        ReportState.noteSyncResult(dir, "ok");
        assertEquals(0, ReportState.failedStreak(dir));

        ReportState.noteSyncResult(dir, "failed");
        ReportState.noteSyncResult(dir, "staged");
        assertEquals(0, ReportState.failedStreak(dir));
    }

    /** firstAt is shown as "first seen" in the header, so bumping a repeat must not overwrite it. */
    @Test
    void aBumpKeepsFirstAtAndMovesEverythingElse() {
        ReportState.Row one = ReportState.record(dir, "aaaaaaaaaaaa", 1000L, "crash-reports/a.txt");
        assertEquals(1, one.boots());

        ReportState.Row two = ReportState.record(dir, "aaaaaaaaaaaa", 9000L, "crash-reports/a.txt");

        assertEquals(2, two.boots());
        assertEquals(1000L, two.firstAt());
        assertEquals(9000L, two.lastAt());
        assertEquals(9000L, ReportState.lastReportAt(dir));
    }

    /** Saved fault records and the streak counter share a writer, so writing a record must not corrupt
     *  the counter. */
    @Test
    void aRecordedFaultAndTheStreakCounterDoNotDisturbEachOther() {
        ReportState.noteSyncResult(dir, "failed");
        ReportState.record(dir, "bbbbbbbbbbbb", 5000L, "crash-reports/b.txt");

        assertEquals(1, ReportState.failedStreak(dir));
        assertTrue(ReportState.find(dir, "bbbbbbbbbbbb").isPresent());
    }

    /** Keep history size bounded without evicting the two reserved counters (dropping @streak would reset
     *  the T6 threshold). */
    @Test
    void theHistoryIsCappedAndTheCountersAreNot() throws IOException {
        ReportState.noteSyncResult(dir, "failed");
        for (int i = 0; i < 12; i++) {
            ReportState.record(dir, String.format("%012d", i), 1000L + i, "crash-reports/" + i + ".txt");
        }

        long rows = Files.readAllLines(stateFile(), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.contains("@")).count();
        assertEquals(8, rows);
        assertTrue(ReportState.find(dir, String.format("%012d", 11)).isPresent());
        assertFalse(ReportState.find(dir, String.format("%012d", 0)).isPresent());
        assertEquals(1, ReportState.failedStreak(dir));
    }

    /**
     * Treat a corrupted or hand-edited report-state file as empty (like HeldStrikes) instead of blocking reports.
     */
    @Test
    void aHandEditedFileReadsAsNoMemoryAndNeverThrows() throws IOException {
        Files.createDirectories(ReportFixture.own(dir));
        Files.writeString(stateFile(), "not a report state at all\n"
                + "v1\tjunk\n"
                + "v1\taaaaaaaaaaaa\tyesterday\tsoon\tloads\tcrash-reports/a.txt\n"
                + "v1\t@streak\tloads\n"
                + "v1\t@nonsense\tsoon\n", StandardCharsets.UTF_8);

        assertEquals(0, ReportState.failedStreak(dir));
        assertFalse(ReportState.find(dir, "aaaaaaaaaaaa").isPresent());
        assertEquals(1, ReportState.record(dir, "aaaaaaaaaaaa", 1L, "x.txt").boots());
    }

    private Path stateFile() {
        return ReportFixture.own(dir).resolve("report-state");
    }
}
