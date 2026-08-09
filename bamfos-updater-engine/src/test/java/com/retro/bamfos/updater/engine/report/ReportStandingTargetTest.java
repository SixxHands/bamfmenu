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

/**
 * Tests guards on `.bamfos-updater/report-state`, whose target path is read from disk before overwriting,
 * plus accurate sync-status formatting.
 */
class ReportStandingTargetTest {

    @TempDir
    Path dir;

    /** State that triggers on every boot so a second boot takes the repeat/rewrite path. */
    private void aBoxOnTheFloor() throws IOException {
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.lastSync(dir, "ok");
    }

    private void boot() {
        TroubleReport.consider(dir, "client");
    }

    /** Runs two boots so ReportState.find hits and exercises the standing-target overwrite path. */
    private void bootUntilTheFingerprintSettles() throws IOException {
        aBoxOnTheFloor();
        boot();
        boot();
    }

    /** Set the file column in report-state to a target path and check whether the next boot overwrites it. */
    private void aimTheStandingReportAt(String relative) throws IOException {
        Path state = ReportFixture.own(dir).resolve("report-state");
        List<String> rows = Files.readAllLines(state, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            String[] cell = row.split("\t", -1);
            if (cell.length >= 5) {
                cell[cell.length - 1] = relative;
                out.append(String.join("\t", cell)).append("\n");
            } else {
                out.append(row).append("\n");
            }
        }
        Files.writeString(state, out.toString(), StandardCharsets.UTF_8);
    }

    /** Rejects `..` traversal in report-state so repeat writes can't overwrite files outside the report slot. */
    @Test
    void aHandEditedRowCannotAimTheRewriteOutsideTheInstance() throws IOException {
        bootUntilTheFingerprintSettles();
        Path victim = dir.getParent().resolve("victim.txt");
        Files.writeString(victim, "somebody else's file", StandardCharsets.UTF_8);
        aimTheStandingReportAt("../victim.txt");

        boot();

        assertEquals("somebody else's file", Files.readString(victim, StandardCharsets.UTF_8));
    }

    /** A file inside the instance that isn't a report or inside a report slot (like a world or config). */
    @Test
    void aRowNamingAFileOfSomebodyElsesIsNotWrittenOver() throws IOException {
        bootUntilTheFingerprintSettles();
        Path theirs = dir.resolve("options.txt");
        Files.writeString(theirs, "renderDistance:8", StandardCharsets.UTF_8);
        aimTheStandingReportAt("options.txt");

        boot();

        assertEquals("renderDistance:8", Files.readString(theirs, StandardCharsets.UTF_8));
    }

    /**
     * Rejects a report-named file outside the report slot (in the instance root), exercising the parent-dir
     * check that foreign filenames like options.txt don't reach.
     */
    @Test
    void aFileWearingOurNameOutsideASlotIsStillNotWrittenOver() throws IOException {
        bootUntilTheFingerprintSettles();
        Path decoy = dir.resolve(ReportSlot.PREFIX + "decoy" + ReportSlot.SUFFIX);
        Files.writeString(decoy, "not in a slot, not ours to touch", StandardCharsets.UTF_8);
        aimTheStandingReportAt(decoy.getFileName().toString());

        boot();

        assertEquals("not in a slot, not ours to touch", Files.readString(decoy, StandardCharsets.UTF_8));
    }

    /**
     * Verifies the target guard still allows normal repeat overwrites so report count stops growing
     * (count currently settles at 2 after boot 2, tracked separately).
     */
    @Test
    void aStandingReportIsReplacedRatherThanPilingUpForever() throws IOException {
        bootUntilTheFingerprintSettles();
        int settled = ReportFixture.reports(dir).size();

        boot();
        boot();

        assertEquals(settled, ReportFixture.reports(dir).size());
    }

    /**
     * Avoid matching `ok` as a substring inside `token-dead` and printing `last sync ok` when the token is dead.
     */
    @Test
    void aDeadTokenIsNeverReportedAsAGoodSync() throws IOException {
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.lastSync(dir, "token-dead");

        boot();

        // check the row instead of the whole file, since "ok" appears inside normal words in the header
        String row = "";
        for (String line : ReportFixture.read(ReportFixture.reports(dir).get(0)).split("\n")) {
            if (line.contains("last sync")) {
                row = line.trim();
            }
        }
        assertTrue(row.contains("token-dead"), String.valueOf(row));
        assertFalse(row.matches(".*\\bok\\b.*"), String.valueOf(row));
    }
}
