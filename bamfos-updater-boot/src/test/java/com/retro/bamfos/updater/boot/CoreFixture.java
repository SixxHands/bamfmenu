package com.retro.bamfos.updater.boot;

import com.retro.bamfos.updater.core.CoreEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds core-NNNN.jar test fixtures for CoreSlot and client handoff tests. */
final class CoreFixture {

    private static final String ENTRY = "com/retro/bamfos/updater/core/CoreEntry.class";

    static Path coreDir(Path box) {
        return box.resolve(".bamfos-updater").resolve("core");
    }

    /** Writes just the entry class with no manifest since the stub loads it directly by class name. */
    static void install(Path box, String... jars) throws IOException {
        byte[] fixture = fixtureClass();
        Files.createDirectories(coreDir(box));
        for (String jar : jars) {
            try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(coreDir(box).resolve(jar)))) {
                zout.putNextEntry(new ZipEntry(ENTRY));
                zout.write(fixture);
                zout.closeEntry();
            }
        }
    }

    /**
     * Uses the real CoreEntry name, so we check a fixture-only marker string to catch
     * the core module accidentally leaking onto the boot test classpath.
     */
    private static byte[] fixtureClass() throws IOException {
        try (InputStream in = CoreFixture.class.getResourceAsStream("/" + ENTRY)) {
            assertNotNull(in);
            byte[] bytes = in.readAllBytes();
            assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains(CoreEntry.RAN_LOG));
            return bytes;
        }
    }

    private CoreFixture() {
    }
}
