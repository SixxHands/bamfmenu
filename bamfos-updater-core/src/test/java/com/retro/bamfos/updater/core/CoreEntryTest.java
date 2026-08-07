package com.retro.bamfos.updater.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests CoreEntry's handoff map, crash-mark handling, and seam timing.
 * Uses a fixture EngineEntry jar to observe mark state from inside the engine as it starts.
 */
class CoreEntryTest {

    /** Avoid @TempDir: CoreEntry keeps the engine classloader open for ops, blocking Windows cleanup. */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    @AfterEach
    void sweep() throws IOException {
        try (Stream<Path> walk = Files.walk(tmp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException stillOpen) {
                }
            });
        }
    }

    private Path coreDir() {
        return tmp.resolve(".bamfos-updater").resolve("core");
    }

    private Path enginePath() {
        return tmp.resolve(".bamfos-updater").resolve("engine").resolve("bamfos-updater-engine.jar");
    }

    private Map<String, Object> enter() throws Exception {
        return CoreEntry.enter(new String[]{"--dir", tmp.toString()});
    }

    /**
     * Verifies the core clears its crash mark before EngineEntry.enter runs, not after it returns,
     * so engine faults never demote a healthy core. Uses a startup probe file to avoid flaky sleeps.
     */
    @Test
    void coreClearsTheMarkBeforeTheEngineRuns() throws Exception {
        installFixtureEngine();
        arm("core-0002.jar", "-");

        enter();

        assertEquals("false", Files.readString(tmp.resolve("engine-saw-the-mark.txt")));
        assertFalse(Files.exists(coreDir().resolve("core-unproven")));
    }

    /** Forward the exit code returned by the engine instead of hardcoding 0, since the server stub
     *  passes it to System.exit. */
    @Test
    void theExitKeyIsTheEnginesOwnCode() throws Exception {
        installFixtureEngine();

        assertEquals(com.retro.bamfos.updater.engine.EngineEntry.PROBE_EXIT, enter().get("exit"));
    }

    /**
     * First strike leaves the generation intact (tolerating alt-F4 or power loss); the second strike
     * deletes the jar so bad generations cannot flap.
     */
    @Test
    void aSkippedGenerationIsStruckAtTheSeamAndTheSecondStrikeRemovesIt() throws Exception {
        installFixtureEngine();
        Files.createDirectories(coreDir());
        Files.writeString(coreDir().resolve("core-0003.jar"), "the generation the stub walked past");
        Files.writeString(coreDir().resolve("core-0002.jar"), "the one it ran instead");

        arm("core-0002.jar", "core-0003.jar");
        enter();
        assertEquals(List.of("core-0003.jar\t1"),
                Files.readAllLines(coreDir().resolve("core-strikes"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(coreDir().resolve("core-0003.jar")));

        arm("core-0002.jar", "core-0003.jar");
        enter();
        assertFalse(Files.exists(coreDir().resolve("core-0003.jar")));
        assertTrue(Files.readAllLines(coreDir().resolve("core-rejected"), StandardCharsets.UTF_8).get(0)
                        .startsWith("core-0003.jar\t"));
    }

    /**
     * Double-launch guard: a live sibling owns its mark (pid and start instant in fields 2 and 3),
     * so another instance must neither clear nor strike it.
     */
    @Test
    void aMarkArmedBySomebodyElseIsNeitherSpentNorActedOn() throws Exception {
        installFixtureEngine();
        Files.createDirectories(coreDir());
        Files.writeString(coreDir().resolve("core-0003.jar"), "not ours to judge");
        String foreign = "v1\tcore-0002.jar\t999999999\t1\tcore-0003.jar";
        Files.writeString(coreDir().resolve("core-unproven"), foreign, StandardCharsets.UTF_8);

        assertEquals(com.retro.bamfos.updater.engine.EngineEntry.PROBE_EXIT, enter().get("exit"));

        assertEquals(foreign, Files.readString(coreDir().resolve("core-unproven"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(coreDir().resolve("core-strikes")));
        assertTrue(Files.isRegularFile(coreDir().resolve("core-0003.jar")));
    }

    /** Older engines without an ops handle leave the key unset so the BIOS shows its not-installed state
     *  instead of failing to bind. */
    @Test
    void anEngineWithNoOpsGatewayLeavesTheKeyUnset() throws Exception {
        installFixtureEngine();

        assertFalse(enter().containsKey("ops"));
    }

    /**
     * Exercises reflection against an actual engine jar: verifies the ops handle crosses as a BiFunction
     * and returns "error: ops not bound" without throwing, plus baseline extraction from core resources.
     */
    @Test
    void theRealEngineHandsBackACallableOpsHandle() throws Exception {
        Map<String, Object> answer = enter();

        assertTrue(Files.isRegularFile(enginePath()));
        assertEquals(0, answer.get("exit"));
        Object ops = answer.get("ops");
        assertInstanceOf(BiFunction.class, ops, "java.base or it does not cross the loader at all");
        @SuppressWarnings("unchecked")
        BiFunction<String, Object[], Object> callable = (BiFunction<String, Object[], Object>) ops;
        assertEquals("error: ops not bound", callable.apply("status", new Object[0]));
    }

    /** Checks the reflection entry-point names since a typo compiles fine and breaks engine lookup at runtime. */
    @Test
    void theEngineHandoffLiteralsAreFrozen() {
        assertEquals("com.retro.bamfos.updater.engine.EngineEntry", CoreAbi.ENGINE_ENTRY_CLASS);
        assertEquals("enter", CoreAbi.ENGINE_ENTRY_METHOD);
        assertEquals("com.retro.bamfos.updater.engine.ops.OpsGateway", CoreAbi.OPS_GATEWAY_CLASS);
        assertEquals("invoke", CoreAbi.OPS_INVOKE_METHOD);
    }

    // ---- fixtures ----

    /** Zips the test EngineEntry class into the engine slot with no manifest, since the core loads it by name. */
    private void installFixtureEngine() throws IOException {
        String entry = "com/retro/bamfos/updater/engine/EngineEntry.class";
        Files.createDirectories(enginePath().getParent());
        try (InputStream in = CoreEntryTest.class.getResourceAsStream("/" + entry);
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(enginePath()))) {
            assertNotNull(in);
            zout.putNextEntry(new ZipEntry(entry));
            in.transferTo(zout);
            zout.closeEntry();
        }
    }

    /** Writes a mark matching CoreSlot.arm, stamped with our pid so the core will clear it. */
    private void arm(String running, String skipped) throws IOException {
        ProcessHandle self = ProcessHandle.current();
        Files.createDirectories(coreDir());
        Files.writeString(coreDir().resolve("core-unproven"), "v1\t" + running + "\t" + self.pid() + "\t"
                        + self.info().startInstant().map(Instant::toEpochMilli).orElse(0L) + "\t" + skipped,
                StandardCharsets.UTF_8);
    }
}
