package com.retro.bamfos.updater.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests each baseline lookup step individually, since core test resources always include an engine jar
 * and supplier() would otherwise never fall through to the stub jar.
 */
class BaselineEngineTest {

    @TempDir
    Path tmp;

    private static final byte[] PAYLOAD = "pretend this is 200KB of engine".getBytes(StandardCharsets.UTF_8);

    /** Client layout: finds the boot stub jar in mods/. */
    @Test
    void theStubInModsIsWhereTheBaselineComesFrom() throws Exception {
        stub(tmp.resolve("mods").resolve("bamfos-updater-boot.jar"), PAYLOAD);

        assertArrayEquals(PAYLOAD, read(BaselineEngine.fromStub(tmp)));
    }

    /** Server layout: finds the boot stub jar at the instance root instead of mods/. */
    @Test
    void theStubAtTheInstanceRootIsFoundToo() throws Exception {
        stub(tmp.resolve("bamfos-updater-boot.jar"), PAYLOAD);

        assertArrayEquals(PAYLOAD, read(BaselineEngine.fromStub(tmp)));
    }

    /** Matches versioned release jar names (e.g. hand-dropped assets), same scan as EmbeddedToken. */
    @Test
    void aVersionedHandDroppedStubStillCounts() throws Exception {
        stub(tmp.resolve("mods").resolve("bamfos-updater-boot-0.1.0.jar"), PAYLOAD);

        assertArrayEquals(PAYLOAD, read(BaselineEngine.fromStub(tmp)));
    }

    /** Ignore staged .jar.new files since they haven't been activated yet. */
    @Test
    void aStagedNewIsNotAStub() throws Exception {
        stub(tmp.resolve("mods").resolve("bamfos-updater-boot.jar.new"), PAYLOAD);

        assertNull(BaselineEngine.fromStub(tmp));
    }

    /** No stub, no entry, no zip: all of them answer null, and EngineLoader.extract turns null into a named
     *  IOException. Nothing here is ever allowed to throw, because this runs on the boot path. */
    @Test
    void everyMissingThingAnswersNull() throws Exception {
        assertNull(BaselineEngine.fromStub(tmp));

        Path stub = tmp.resolve("mods").resolve("bamfos-updater-boot.jar");
        Files.createDirectories(stub.getParent());
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(stub))) {
            zout.putNextEntry(new ZipEntry("something/else.txt"));
            zout.write(PAYLOAD);
            zout.closeEntry();
        }
        assertNull(BaselineEngine.fromStub(tmp));

        Files.write(stub, "not a zip at all".getBytes(StandardCharsets.UTF_8));
        assertNull(BaselineEngine.fromStub(tmp));
    }

    /**
     * Must read eagerly and close the ZipFile; an open handle on the stub jar blocks wrapper
     * swaps on Windows. Deleting the jar below verifies no handle leaked.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theStubJarIsNotLeftOpen() throws Exception {
        Path stub = tmp.resolve("mods").resolve("bamfos-updater-boot.jar");
        stub(stub, PAYLOAD);

        assertNotNull(BaselineEngine.fromStub(tmp));

        Files.delete(stub); // throws on Windows if the zip handle is still open
    }

    /** An embedded engine baseline inside the core jar takes precedence over the stub jar. */
    @Test
    void theCoresOwnCopyIsTriedFirst() throws Exception {
        stub(tmp.resolve("mods").resolve("bamfos-updater-boot.jar"), PAYLOAD);

        byte[] own;
        try (InputStream in = EngineLoader.embeddedBaseline().get()) {
            assertNotNull(in);
            own = in.readAllBytes();
        }
        assertArrayEquals(own, read(BaselineEngine.supplier(tmp).get()));
    }

    private static void stub(Path at, byte[] payload) throws IOException {
        Files.createDirectories(at.getParent());
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(at))) {
            zout.putNextEntry(new ZipEntry("baseline/bamfos-updater-engine.jar"));
            zout.write(payload);
            zout.closeEntry();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        assertNotNull(in);
        try (in) {
            return in.readAllBytes();
        }
    }
}
