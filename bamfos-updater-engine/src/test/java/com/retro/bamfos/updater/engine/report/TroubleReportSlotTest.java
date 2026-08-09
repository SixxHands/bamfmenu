package com.retro.bamfos.updater.engine.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests trouble report placement, naming, and strict pruning in crash-reports so real crash logs stay safe. */
class TroubleReportSlotTest {

    @TempDir
    Path dir;

    /** State that triggers the same fault on every boot so tests can run repeated boots. */
    private void aBoxOnTheFloor() throws IOException {
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.lastSync(dir, "ok");
    }

    private void boot() {
        TroubleReport.consider(dir, "client");
    }

    /** Fresh installs that haven't crashed won't have a crash-reports folder yet, so create it if needed. */
    @Test
    void itCreatesCrashReportsAndLandsThere() throws IOException {
        aBoxOnTheFloor();
        assertFalse(Files.exists(dir.resolve("crash-reports")));

        boot();

        List<Path> mine = ReportFixture.reports(dir);
        assertEquals(1, mine.size());
        assertEquals(dir.resolve("crash-reports"), mine.get(0).getParent());
    }

    /**
     * Matches vanilla CrashReport timestamp punctuation for clean folder sorting, while avoiding the `crash-`
     * prefix that launchers treat as a game crash.
     */
    @Test
    void theNameSortsWithVanillaCrashesAndCannotBeMistakenForOne() throws IOException {
        aBoxOnTheFloor();

        boot();

        String name = ReportFixture.reports(dir).get(0).getFileName().toString();
        assertTrue(name.matches("bamfos-updater-\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}\\.txt"), name);
        assertFalse(name.startsWith("crash-"));
    }

    /** Fall back to slot 2 in our own directory when crash-reports isn't writable (using a file at that path
     *  simulates a read-only folder or antivirus lock). */
    @Test
    void anUnwritableCrashReportsFallsBackToOurOwnFolder() throws IOException {
        aBoxOnTheFloor();
        Files.writeString(dir.resolve("crash-reports"), "somebody put a file where the folder goes");

        boot();

        List<Path> mine = ReportFixture.reports(dir);
        assertEquals(1, mine.size());
        assertEquals(ReportFixture.own(dir).resolve("reports"), mine.get(0).getParent());
    }

    /**
     * Walks the entire tree to verify no third fallback slot is used when both configured slots are unavailable.
     */
    @Test
    void neitherSlotMeansNoFileAnywhereAndNothingThrown() throws IOException {
        aBoxOnTheFloor();
        Files.writeString(dir.resolve("crash-reports"), "not a folder");
        Files.writeString(ReportFixture.own(dir).resolve("reports"), "also not a folder");

        boot();

        List<Path> anywhere;
        try (var walk = Files.walk(dir)) {
            anywhere = walk.filter(p -> p.getFileName().toString().startsWith("bamfos-updater-")
                    && p.getFileName().toString().endsWith(".txt")).toList();
        }
        assertEquals(List.of(), anywhere);
    }

    /** Pruning matches exact prefix and suffix only, never a loose pattern that could delete real crash reports. */
    @Test
    void thePruneKeepsFiveOfOursAndWillNotTouchARealCrashReport() throws IOException {
        Path crashes = dir.resolve("crash-reports");
        Files.createDirectories(crashes);
        Path foreign = crashes.resolve("crash-2026-08-07_19.42.11-client.txt");
        Files.writeString(foreign, "a real one, and it is not ours to delete");
        Path alsoForeign = crashes.resolve("hs_err_pid4242.log");
        Files.writeString(alsoForeign, "the JVM's own");

        for (int i = 1; i <= 7; i++) {
            Path p = crashes.resolve("bamfos-updater-2026-08-0" + i + "_10.00.00.txt");
            Files.writeString(p, "report " + i);
            p.toFile().setLastModified(1_700_000_000_000L + i * 60_000L);
        }

        ReportSlot.prune(dir);

        assertTrue(Files.isRegularFile(foreign));
        assertTrue(Files.isRegularFile(alsoForeign));
        assertEquals(5, ReportFixture.reports(dir).size());
        assertFalse(Files.exists(crashes.resolve("bamfos-updater-2026-08-01_10.00.00.txt")));
        assertTrue(Files.isRegularFile(crashes.resolve("bamfos-updater-2026-08-07_10.00.00.txt")));
    }

    /** A repeated fault bumps the counter on the existing report instead of creating duplicate files. */
    @Test
    void theSameFaultBumpsTheHeaderInsteadOfWritingASecondFile() throws IOException {
        aBoxOnTheFloor();

        boot(); // core-refused
        boot(); // floor-only is a different fault, so it should get a separate report file
        assertEquals(2, ReportFixture.reports(dir).size());
        boot(); // floor-only again

        assertEquals(2, ReportFixture.reports(dir).size());
        assertTrue(anyReportSays("This has now happened 2 times"));
    }

    /** A different fingerprint always gets a separate report file right away, no matter how recently
     *  another report was written. */
    @Test
    void aDifferentFaultIsAlwaysItsOwnFileStraightAway() throws IOException {
        aBoxOnTheFloor();

        boot();
        boot();

        List<Path> mine = ReportFixture.reports(dir);
        assertEquals(2, mine.size());
        assertTrue(anyReportSays("core-refused"));
        assertTrue(anyReportSays("floor-only"));
    }

    /** If the player deleted the old report, write a fresh one while preserving the repeat count. */
    @Test
    void aDeletedReportIsWrittenAgainAndTheCountSurvives() throws IOException {
        aBoxOnTheFloor();
        boot();
        boot();
        Path floor = null;
        for (Path p : ReportFixture.reports(dir)) {
            if (ReportFixture.read(p).contains("floor-only")) {
                floor = p;
            }
        }
        assertTrue(floor != null);
        Files.delete(floor);

        boot();

        assertTrue(anyReportSays("This has now happened 2 times"));
    }

    private boolean anyReportSays(String needle) throws IOException {
        for (Path p : ReportFixture.reports(dir)) {
            if (Files.readString(p, StandardCharsets.UTF_8).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
