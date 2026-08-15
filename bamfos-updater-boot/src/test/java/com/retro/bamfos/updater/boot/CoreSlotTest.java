package com.retro.bamfos.updater.boot;

import com.retro.bamfos.updater.core.CoreEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests core selection, crash marks, and boot safety against real jars on disk.
 * The boot stub can't self-update after shipping, so we exercise every URLClassLoader failure path.
 */
class CoreSlotTest {

    /** Avoid @TempDir: loaded cores keep their URLClassLoader open for ops, so Windows blocks deletion at teardown. */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    private final List<String> properties = new ArrayList<>();
    private int boxes;

    @AfterEach
    void sweep() throws IOException {
        properties.forEach(System::clearProperty);
        try (Stream<Path> walk = Files.walk(tmp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException stillOpen) {
                }
            });
        }
    }

    // Core selection

    /** Zero-padded four-digit names let lexical sorting match numeric order (0010 beats 0009). */
    @Test
    void theHighestGenerationRuns() throws Exception {
        install("core-0000.jar", "core-0002.jar", "core-0009.jar", "core-0010.jar");

        boot();

        assertEquals(List.of("core-0010.jar"), ranLog());
    }

    /**
     * Two bad generations in a row must descend monotonically; skipping only == instead of >=
     * flaps between 0009 and 0008 forever without ever striking either one.
     */
    @Test
    void theDescentIsMonotone() throws Exception {
        install("core-0009.jar", "core-0008.jar", "core-0007.jar", "core-0000.jar");

        boot();
        boot();
        boot();

        assertEquals(List.of("core-0009.jar", "core-0008.jar", "core-0007.jar"), ranLog());
    }

    /**
     * When the only generation on disk is blamed, fall back to a freshly extracted floor jar
     * instead of re-running the broken core or giving up without an updater.
     */
    @Test
    void aBlamedLastCandidateFallsToTheFloor_neverRunsItselfAgain() throws Exception {
        install("core-0005.jar");
        armedHere("core-0005.jar", "-");

        boot();

        assertEquals(List.of(), ranLog());
        assertArrayEquals(baselineCore(), Files.readAllBytes(coreDir(tmp).resolve("core-0000.jar")));
    }

    /** Field 4 is the skipped generation that gets struck. If the fallback core itself is blamed,
     *  we still run it and write "-" so it doesn't strike and delete itself. */
    @Test
    void weRanTheBlamedFloorSoNobodyIsStruckForIt() throws Exception {
        install("core-0000.jar");
        armedHere("core-0000.jar", "-");

        boot();

        assertEquals(List.of("core-0000.jar"), ranLog());
        assertEquals("core-0000.jar", mark()[1]);
        assertEquals("-", mark()[4]);
    }

    /**
     * Non-matching jars like core-old.jar sort above numeric names and can't be struck,
     * so CoreSlot ignores them without running or deleting them.
     */
    @Test
    void aNameOutsideTheGrammarIsNeverRun_andNeverDeleted() throws Exception {
        install("core-0002.jar");
        CoreFixture.install(tmp, "core-0009 - Copy.jar", "core-old.jar", "core-00010.jar");

        boot();

        assertEquals(List.of("core-0002.jar"), ranLog());
        assertTrue(Files.exists(coreDir(tmp).resolve("core-old.jar")));
        assertTrue(Files.exists(coreDir(tmp).resolve("core-0009 - Copy.jar")));
    }

    /**
     * Arm the mark after loading succeeds and before handover; arming before load lets a corrupt
     * 0009 jar blame the healthy 0008 fallback underneath it.
     */
    @Test
    void onlyTheGenerationWeHandOverToIsArmed() throws Exception {
        notAZip("core-0009.jar");
        install("core-0008.jar");

        boot();

        assertEquals("core-0008.jar", mark()[1]);
        assertEquals(List.of("core-0008.jar"), ranLog());
    }

    /**
     * Mark format compatibility: field 0 is a version token and new fields only append at the end.
     * Uses 3 generations so "blame highest" and "blame field 1" diverge.
     */
    @Test
    void theMarkFormatIsFrozen() throws Exception {
        install("core-0009.jar", "core-0005.jar", "core-0002.jar");

        assertEquals("core-0005.jar", bootWithMark("v2\tcore-0002.jar\t0\t0\t-"));
        assertEquals("core-0005.jar", bootWithMark(""));
        assertEquals("core-0005.jar", bootWithRawMark(new byte[]{(byte) 0xC3, (byte) 0x28}));
        assertEquals("core-0002.jar", bootWithMark("v1\tcore-0005.jar\t" + pid() + "\t" + started()
                        + "\tcore-0007.jar\tsomething a newer core added"));
    }

    // ---- the double launch ----

    /**
     * Ignore a mark from a live sibling instance mid-boot instead of demoting its core or overwriting it.
     * Spawns an actual child JVM because our own pid is ignored and OS start times can't be faked.
     */
    @Test
    void aLiveSiblingIsNeitherJudgedNorOverwritten() throws Exception {
        install("core-0009.jar", "core-0002.jar");
        Process sibling = spawnSibling();
        try {
            String armed = "v1\tcore-0009.jar\t" + sibling.pid() + "\t" + startInstantOf(sibling) + "\t-";
            writeMark(armed);

            boot();

            assertEquals(List.of("core-0009.jar"), ranLog());
            assertEquals(armed, Files.readString(markFile(), StandardCharsets.UTF_8));
            assertTrue(sibling.isAlive());
        } finally {
            sibling.destroyForcibly();
        }
    }

    /** Pids get recycled, so a live pid with a different start time means the original process is dead
     *  and its mark still counts. Without the start-time check, a pid collision would skip the rollback. */
    @Test
    void aRecycledPidStillEarnsAVerdict() throws Exception {
        install("core-0009.jar", "core-0002.jar");
        Process sibling = spawnSibling();
        try {
            writeMark("v1\tcore-0009.jar\t" + sibling.pid() + "\t" + (startInstantOf(sibling) + 60_000) + "\t-");

            boot();

            assertEquals(List.of("core-0002.jar"), ranLog());
            assertEquals("core-0009.jar", mark()[4]);
        } finally {
            sibling.destroyForcibly();
        }
    }

    // Embedded fallback

    /** Last-resort fallback: extracts the known-good baseline core embedded in the stub jar. */
    @Test
    void theFloorComesOutOfOurOwnResource() throws Exception {
        Map<?, ?> answer = boot();

        assertNotNull(answer);
        assertArrayEquals(baselineCore(), Files.readAllBytes(coreDir(tmp).resolve("core-0000.jar")));
    }

    /**
     * Windows-only (POSIX replaces open files): a failed URLClassLoader must be closed
     * or Windows locks the broken floor jar and blocks re-extraction over it.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRefusedCoreIsClosedSoTheFloorCanReplaceIt() throws Exception {
        zipWithNoEntryClass("core-0000.jar");

        boot();

        assertArrayEquals(baselineCore(), Files.readAllBytes(coreDir(tmp).resolve("core-0000.jar")));
    }

    // ---- what crosses the loader ----

    /**
     * Core loads with the platform classloader as parent so it cannot link against frozen stub classes.
     * Also verifies the server exit code crosses the boundary in a plain java.base Map.
     */
    @Test
    void theCoreCannotSeeTheStubAndItsAnswerStillCrosses() throws Exception {
        install("core-0009.jar");

        Map<?, ?> answer = boot();

        assertEquals(Boolean.FALSE, answer.get("sawTheStub"));
        assertEquals(7, answer.get("exit"));
    }

    /**
     * If a core starts running and throws, return an empty map instead of null so we don't
     * fall back and run a second core in the same boot.
     */
    @Test
    void aCoreThatRanAndFailedDoesNotLetASecondOneStart() throws Exception {
        install("core-0009.jar", "core-0008.jar");
        behaviour("core-0009.jar", "throws");

        Map<?, ?> answer = boot();

        assertEquals(List.of("core-0009.jar"), ranLog());
        assertTrue(answer.isEmpty());
    }

    /**
     * Boot-safety gate: run() must always return a map across broken disk states and never throw.
     * Putting a regular file at the updater dir path also covers the no-floor-available branch.
     */
    @Test
    void nothingThrowsOutOfRun() throws Exception {
        lawFive("there is no core folder at all", box -> {
        });
        lawFive("the core folder is empty", box -> Files.createDirectories(coreDir(box)));
        lawFive("a FILE squats where the core folder goes", box -> {
            Files.createDirectories(box.resolve(".bamfos-updater"));
            Files.writeString(box.resolve(".bamfos-updater").resolve("core"), "not a folder");
        });
        lawFive("the core is not a zip at all", box -> notAZip(box, "core-0009.jar"));
        lawFive("the core will not initialise", box -> {
            install(box, "core-0019.jar");
            behaviour("core-0019.jar", "wont-init");
        });
        lawFive("the core throws out of enter", box -> {
            install(box, "core-0018.jar");
            behaviour("core-0018.jar", "throws");
        });
        lawFive("the core answers null", box -> {
            install(box, "core-0017.jar");
            behaviour("core-0017.jar", "null");
        });
        lawFive("the core answers something that is not a map", box -> {
            install(box, "core-0016.jar");
            behaviour("core-0016.jar", "not-a-map");
        });
    }

    // ---- fixtures ----

    @FunctionalInterface
    private interface Disk {
        void set(Path box) throws Exception;
    }

    private void lawFive(String state, Disk setup) throws Exception {
        Path box = Files.createDirectories(tmp.resolve("box-" + boxes++));
        setup.set(box);
        Map<?, ?> answer = assertDoesNotThrow(
                () -> CoreSlot.run(box, new String[]{"--dir", box.toString()}), state);
        assertNotNull(answer, String.valueOf(state));
    }

    private Map<?, ?> boot() {
        return CoreSlot.run(tmp, new String[]{"--dir", tmp.toString()});
    }

    /** Runs a single boot with the given mark content and returns the core name that ran. */
    private String bootWithMark(String contents) throws Exception {
        return bootWithRawMark(contents.getBytes(StandardCharsets.UTF_8));
    }

    private String bootWithRawMark(byte[] contents) throws Exception {
        Files.createDirectories(coreDir(tmp));
        Files.write(markFile(), contents);
        Files.deleteIfExists(tmp.resolve(CoreEntry.RAN_LOG));

        boot();

        assertEquals("v1", mark()[0]);
        assertEquals(5, mark().length);
        List<String> ran = ranLog();
        assertEquals(1, ran.size());
        return ran.get(0);
    }

    private static Path coreDir(Path box) {
        return CoreFixture.coreDir(box);
    }

    private Path markFile() {
        return coreDir(tmp).resolve("core-unproven");
    }

    private String[] mark() throws IOException {
        return Files.readString(markFile(), StandardCharsets.UTF_8).split("\t");
    }

    private void writeMark(String contents) throws IOException {
        Files.createDirectories(coreDir(tmp));
        Files.writeString(markFile(), contents, StandardCharsets.UTF_8);
    }

    /** Writes a mark matching CoreSlot.arm, stamped with our pid so the next boot acts on it. */
    private void armedHere(String running, String skipped) throws IOException {
        writeMark("v1\t" + running + "\t" + pid() + "\t" + started() + "\t" + skipped);
    }

    private static long pid() {
        return ProcessHandle.current().pid();
    }

    private static long started() {
        return ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    private List<String> ranLog() throws IOException {
        Path log = tmp.resolve(CoreEntry.RAN_LOG);
        return Files.isRegularFile(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
    }

    private void behaviour(String jar, String how) {
        String key = CoreEntry.BEHAVIOUR + jar;
        properties.add(key);
        System.setProperty(key, how);
    }

    private void install(String... jars) throws IOException {
        CoreFixture.install(tmp, jars);
    }

    private void install(Path box, String... jars) throws IOException {
        CoreFixture.install(box, jars);
    }

    private void notAZip(String jar) throws IOException {
        notAZip(tmp, jar);
    }

    private void notAZip(Path box, String jar) throws IOException {
        Files.createDirectories(coreDir(box));
        Files.write(coreDir(box).resolve(jar), new byte[]{0x00, 0x01, 0x02, 0x03});
    }

    /** Valid empty zip so URLClassLoader opens a file handle on it before failing to find the entry class. */
    private void zipWithNoEntryClass(String jar) throws IOException {
        Files.createDirectories(coreDir(tmp));
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(coreDir(tmp).resolve(jar)))) {
            zout.putNextEntry(new ZipEntry("nothing/useful.txt"));
            zout.write("no entry class in here".getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
    }

    private static byte[] baselineCore() throws IOException {
        try (InputStream in = CoreSlot.class.getResourceAsStream("/baseline/bamfos-updater-core.jar")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    /** Spawns a sleeping child JVM from the test classpath; callers kill it in finally. */
    private static Process spawnSibling() throws Exception {
        Path classes = Paths.get(Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String java = ProcessHandle.current().info().command()
                .orElse(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        Process sibling = new ProcessBuilder(java, "-cp", classes.toString(), Sleeper.class.getName())
                .redirectErrorStream(true).start();
        if (sibling.waitFor(1, TimeUnit.SECONDS)) {
            fail("the sibling JVM exited (" + sibling.exitValue() + ") instead of parking, so there was no "
                    + "live pid to test against: " + new String(sibling.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8));
        }
        return sibling;
    }

    /** Make sure the child pid is alive and has an OS start time before writing the mark, otherwise
     *  both tests would just hit the dead-process path. */
    private static long startInstantOf(Process sibling) {
        assertTrue(sibling.isAlive());
        long started = sibling.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        assertTrue(started > 0);
        return started;
    }
}
