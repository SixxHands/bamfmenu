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

/** Tests when trouble reports trigger and verifies external/transient conditions stay silent. */
class TroubleReportTriggerTest {

    @TempDir
    Path dir;

    private void boot() {
        TroubleReport.consider(dir, "client");
    }

    private List<Path> written() {
        return ReportFixture.reports(dir);
    }

    private String only() throws IOException {
        List<Path> all = written();
        assertEquals(1, all.size());
        return ReportFixture.read(all.get(0));
    }

    /** Normal healthy boots must not write a trouble report. */
    @Test
    void aConvergedBoxWritesNothingAtAll() throws IOException {
        ReportFixture.lastSync(dir, "ok");

        boot();

        assertEquals(List.of(), written());
    }

    /** A second strike triggers refusal and deletion (CoreHealth.strike), so entries here won't run again. */
    @Test
    void aRefusedCoreEarnsExactlyOneFile() throws IOException {
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.coreJar(dir, "core-0003.jar");
        ReportFixture.lastSync(dir, "ok");

        boot();

        String body = only();
        assertTrue(body.contains("core-refused"), body);
        assertTrue(body.contains("core-0004.jar"));
        assertTrue(body.startsWith("BAMFOS UPDATER PROBLEM REPORT"));
        assertTrue(body.contains("Send this whole file to the pack owner"));
    }

    /** A single strike isn't enough to refuse since an alt-F4, power loss, or transient error leaves one
     *  behind; both guards require two strikes first. */
    @Test
    void oneStrikeAgainstACoreIsNotAReport() throws IOException {
        Files.createDirectories(ReportFixture.own(dir).resolve("core"));
        Files.writeString(ReportFixture.own(dir).resolve("core").resolve("core-strikes"),
                "core-0004.jar\t1\n", StandardCharsets.UTF_8);
        ReportFixture.lastSync(dir, "ok");

        boot();

        assertEquals(List.of(), written());
    }

    /**
     * Dead tokens stay out of trouble reports since they're already shown on boot and affect everyone on rotation.
     */
    @Test
    void aDeadTokenIsNotATrigger() throws IOException {
        Files.createDirectories(ReportFixture.own(dir));
        Files.writeString(ReportFixture.own(dir).resolve("token-dead"), "401", StandardCharsets.UTF_8);
        ReportFixture.lastSync(dir, "token-dead");

        boot();
        boot();
        boot();

        assertEquals(List.of(), written());
    }

    /** `offline` neither increments nor resets the streak, so consecutive offline boots never trigger a report. */
    @Test
    void threeOfflineBootsAreNotAStreak() throws IOException {
        ReportFixture.lastSync(dir, "offline");

        boot();
        boot();
        boot();

        assertEquals(List.of(), written());
    }

    /** Three consecutive failures should write a report, since disk state may be partial after an exception. */
    @Test
    void threeOutrightFailuresEarnOne() throws IOException {
        ReportFixture.lastSync(dir, "failed");

        boot();
        assertEquals(List.of(), written());
        boot();
        assertEquals(List.of(), written());
        boot();

        assertTrue(only().contains("failed-streak"));
    }

    /**
     * Floor-only is a state after all offered generations are rejected: boot 1 reports the refusal, and boot 2
     * reports the remaining floor-only state.
     */
    @Test
    void aLadderDownToTheFloorGetsItsOwnFileAfterTheRefusalOne() throws IOException {
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.lastSync(dir, "ok");

        boot();
        assertEquals(1, written().size());
        boot();

        assertEquals(2, written().size());
        assertTrue(ReportFixture.read(written().get(0)).contains("floor-only")
                        || ReportFixture.read(written().get(1)).contains("floor-only"));
    }

    /**
     * Not floor-only when a newer unrejected generation still exists, since future cores will still be accepted.
     */
    @Test
    void aRefusalWithAGenerationStillStandingIsNotTheFloorCase() throws IOException {
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.coreJar(dir, "core-0005.jar");
        ReportFixture.lastSync(dir, "ok");

        boot();
        assertEquals(1, written().size());
        boot();

        assertEquals(1, written().size());
    }

    /**
     * Requires both held files and exhausted retries (matching ServerDoor.selfHeal), so converged or
     * still-retrying installs stay quiet.
     */
    @Test
    void heldFilesNeedToBeSpentAndTheSyncNeedsToHaveNotConverged() throws IOException {
        long now = System.currentTimeMillis();
        Files.createDirectories(ReportFixture.own(dir));
        Files.writeString(ReportFixture.own(dir).resolve("held-strikes"),
                "mods/stuck.jar\tabc\treplace\t3\t" + now + "\n", StandardCharsets.UTF_8);
        ReportFixture.lastSync(dir, "ok");

        boot();
        assertEquals(List.of(), written());

        ReportFixture.lastSync(dir, "pending");
        boot();

        String body = only();
        assertTrue(body.contains("held-settled"));
        // Report only the held count, never the player's local file path.
        assertTrue(body.contains("files given up on 1"), body);
        assertFalse(body.contains("mods/stuck.jar"));
    }

    /** Write at most one report per boot even if multiple triggers are active at once. */
    @Test
    void aBoxInThreeStatesAtOnceStillGetsOneFile() throws IOException {
        long now = System.currentTimeMillis();
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        Files.writeString(ReportFixture.own(dir).resolve("held-strikes"),
                "mods/stuck.jar\tabc\treplace\t3\t" + now + "\n", StandardCharsets.UTF_8);
        ReportFixture.lastSync(dir, "failed");

        boot();

        assertTrue(only().contains("core-refused"));
    }

    /**
     * A note left by the core takes highest priority since it records an earlier boot that couldn't write a
     * report itself, and we delete it after reading so it doesn't create duplicate reports.
     */
    @Test
    void aPendingCoreNoteBeatsEveryOtherTriggerAndIsSupersededAndDeleted() throws IOException {
        Path note = dir.resolve("crash-reports").resolve("bamfos-updater-2026-08-07_19.42.11-note.txt");
        Files.createDirectories(note.getParent());
        Files.writeString(note, String.join("\n",
                "BAMFOS UPDATER PROBLEM REPORT", "",
                "note-format 1", "at 1786000000000", "side client", "java 17", "pid 4242",
                "core core-0004.jar", "cause java.io.IOException", "cause-2 none", ""),
                StandardCharsets.UTF_8);
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.lastSync(dir, "failed");

        boot();

        assertFalse(Files.exists(note));
        String body = only();
        assertTrue(body.contains("no-engine"), body);
        assertTrue(body.contains("java.io.IOException"));
        assertTrue(body.contains("core-0004.jar"));
    }

    /**
     * Keep the core note if writing the report fails (simulated by placing a directory at the part path),
     * so a failed write doesn't destroy the only evidence.
     */
    @Test
    void aReportThatCouldNotBeWrittenLeavesTheNoteAloneForTheNextBoot() throws IOException {
        Path note = writeNote();
        ReportFixture.lastSync(dir, "failed");
        boot();
        assertFalse(Files.exists(note));
        Path standing = written().get(0);
        String before = ReportFixture.read(standing);

        // same fault again, but block the rewrite by putting a directory where the temp file goes
        Path blocked = standing.resolveSibling(standing.getFileName() + ".part");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("keep"), "so the cleanup delete cannot clear it either");
        writeNote();

        boot();

        assertTrue(Files.exists(note));
        assertEquals(before, ReportFixture.read(standing));
    }

    /** Sample core note matching the format CoreNote writes when the engine fails to load. */
    private Path writeNote() throws IOException {
        Path note = dir.resolve("crash-reports").resolve("bamfos-updater-2026-08-07_19.42.11-note.txt");
        Files.createDirectories(note.getParent());
        Files.writeString(note, String.join("\n",
                "BAMFOS UPDATER PROBLEM REPORT", "",
                "note-format 1", "at 1786000000000", "side client", "java 17", "pid 4242",
                "core core-0004.jar", "cause java.io.IOException", "cause-2 none", ""),
                StandardCharsets.UTF_8);
        return note;
    }

    /** The two call sites, checked as source because a door test would need a network. Removing either one is
     *  silent everywhere else: the feature just stops existing and every test in this file still passes. */
    @Test
    void bothDoorsCallItFromTheirFinally() throws IOException {
        Path doors = ReportFixture.repoRoot()
                .resolve("bamfos-updater-engine/src/main/java/com/retro/bamfos/updater/engine/door");
        String client = Files.readString(doors.resolve("ClientDoor.java"), StandardCharsets.UTF_8);
        String server = Files.readString(doors.resolve("ServerDoor.java"), StandardCharsets.UTF_8);

        assertTrue(client.contains("TroubleReport.consider(dir, \"client\")"));
        assertTrue(client.indexOf("TroubleReport.consider") < client.indexOf("bus.end(end)"));
        assertTrue(server.contains("TroubleReport.consider(dir, \"server\")"));
    }
}
