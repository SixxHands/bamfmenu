package com.retro.bamfos.updater.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests CoreHealth bookkeeping: mark fields, two-strike rejection, and jar pruning. */
class CoreHealthTest {

    @TempDir
    Path tmp;

    private Path gen(String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, name);
        return p;
    }

    /**
     * First strike is tolerated (alt-F4 or power loss); second strike deletes the jar so those
     * bytes cannot re-run.
     */
    @Test
    void twoStrikesRefuseAndRemoveTheGeneration() throws Exception {
        Path bad = gen("core-0009.jar");

        assertFalse(CoreHealth.strike(tmp, "core-0009.jar"));
        assertTrue(Files.isRegularFile(bad));
        assertEquals(List.of("core-0009.jar\t1"),
                Files.readAllLines(tmp.resolve("core-strikes"), StandardCharsets.UTF_8));

        assertTrue(CoreHealth.strike(tmp, "core-0009.jar"));
        assertFalse(Files.exists(bad));
        assertTrue(Files.readAllLines(tmp.resolve("core-rejected"), StandardCharsets.UTF_8).get(0)
                        .startsWith("core-0009.jar\t"));
        assertEquals(List.of(), Files.readAllLines(tmp.resolve("core-strikes"), StandardCharsets.UTF_8));
    }

    /** Never strike or delete the embedded fallback jar. */
    @Test
    void theFloorIsNeverStruck() throws Exception {
        Path floor = gen("core-0000.jar");

        assertFalse(CoreHealth.strike(tmp, "core-0000.jar"));
        assertFalse(CoreHealth.strike(tmp, "core-0000.jar"));

        assertTrue(Files.isRegularFile(floor));
        assertFalse(Files.exists(tmp.resolve("core-strikes")));
    }

    /**
     * Rejects invalid names from the mark file before resolving paths so a relative path
     * can't delete files outside the core directory.
     */
    @Test
    void aNameOffTheFrozenGrammarIsRefusedBeforeAnythingResolvesIt() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("core"));
        Path victim = tmp.resolve("victim.jar");
        Files.writeString(victim, "not the updater's to delete");
        Path shortWidth = dir.resolve("core-12.jar");
        Files.writeString(shortWidth, "not a generation, whatever it looks like");

        for (String junk : List.of("../victim.jar", "core-12.jar", "core-00009.jar", "core-000a.jar", "")) {
            assertFalse(CoreHealth.strike(dir, junk), junk);
            assertFalse(CoreHealth.strike(dir, junk), junk + " (second strike must also be ignored)");
        }

        assertTrue(Files.isRegularFile(victim));
        assertTrue(Files.isRegularFile(shortWidth));
        assertFalse(Files.exists(dir.resolve("core-strikes")));
    }

    /**
     * Keeps the two newest generations, the embedded fallback, and the running jar (pinned explicitly
     * in case the stub skipped broken jars newer than it), pruning older ones.
     */
    @Test
    void pruneKeepsTheTopTwoTheFloorAndWhateverIsRunning() throws Exception {
        gen("core-0000.jar");
        gen("core-0001.jar");
        gen("core-0002.jar");
        gen("core-0003.jar");
        gen("core-0004.jar");
        Files.writeString(tmp.resolve("core-strikes"), "not a jar, not ours to prune");

        CoreHealth.prune(tmp, "core-0001.jar");

        assertTrue(Files.isRegularFile(tmp.resolve("core-0004.jar")));
        assertTrue(Files.isRegularFile(tmp.resolve("core-0003.jar")));
        assertTrue(Files.isRegularFile(tmp.resolve("core-0001.jar")));
        assertTrue(Files.isRegularFile(tmp.resolve("core-0000.jar")));
        assertFalse(Files.exists(tmp.resolve("core-0002.jar")));
        assertTrue(Files.isRegularFile(tmp.resolve("core-strikes")));
    }

    /**
     * Mark fields are positional and append-only, so today's reader still parses fields 0 to 4
     * when a newer core adds a sixth field.
     */
    @Test
    void theMarkIsReadByPosition() throws Exception {
        write(mark("core-0007.jar", "core-0008.jar") + "\tsomething a later core added");

        String[] read = CoreHealth.readMark(tmp);
        assertTrue(CoreHealth.armedHere(read));
        assertEquals("core-0007.jar", CoreHealth.running(read));
        assertEquals("core-0008.jar", CoreHealth.skipped(read));
    }

    /** When the stub had to run the blamed jar anyway, it writes "-" as the skipped field so the
     *  fallback core won't strike and delete itself. */
    @Test
    void aDashMeansNothingWasSkipped() throws Exception {
        write(mark("core-0007.jar", "-"));

        assertEquals("", CoreHealth.skipped(CoreHealth.readMark(tmp)));
    }

    /** Only the process matching fields 2 and 3 may clear the mark, so we don't wipe an active mark
     *  from another instance booting in the same profile. */
    @Test
    void aMarkFromAnotherProcessIsNotOurs() throws Exception {
        write("v1\tcore-0007.jar\t999999999\t1\t-");
        assertFalse(CoreHealth.armedHere(CoreHealth.readMark(tmp)));

        // Simulate a recycled pid: same pid, different start time.
        write("v1\tcore-0007.jar\t" + ProcessHandle.current().pid() + "\t1\t-");
        assertFalse(CoreHealth.armedHere(CoreHealth.readMark(tmp)));

        write("core-0007.jar\t" + ProcessHandle.current().pid() + "\t" + started() + "\t-");
        assertFalse(CoreHealth.armedHere(CoreHealth.readMark(tmp)));

        write("v1\tcore-0007.jar");
        assertFalse(CoreHealth.armedHere(CoreHealth.readMark(tmp)));
    }

    /** Missing mark means unarmed, while an unreadable mark still counts as armed and is left in place
     *  so the stub falls back on the next boot. */
    @Test
    void aMissingMarkReadsAsNothing() {
        assertNull(CoreHealth.readMark(tmp));
        assertFalse(CoreHealth.armedHere(null));
        assertEquals("", CoreHealth.running(null));
        assertEquals("", CoreHealth.skipped(null));
    }

    private String mark(String running, String skipped) {
        return "v1\t" + running + "\t" + ProcessHandle.current().pid() + "\t" + started() + "\t" + skipped;
    }

    private static long started() {
        return ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    private void write(String line) throws IOException {
        Files.writeString(tmp.resolve("core-unproven"), line, StandardCharsets.UTF_8);
    }
}
