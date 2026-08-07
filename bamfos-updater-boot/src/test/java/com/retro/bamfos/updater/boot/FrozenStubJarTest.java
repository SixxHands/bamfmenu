package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the shipped shim jar (bamfos.test.shimJar) for packaging regressions the compiler
 * won't catch: missing embedded baseline jars and static class initialisers.
 */
class FrozenStubJarTest {

    private static Path shimJar() {
        String p = System.getProperty("bamfos.test.shimJar");
        assertNotNull(p);
        Path jar = Paths.get(p);
        assertTrue(Files.isRegularFile(jar), String.valueOf(jar));
        return jar;
    }

    /** Verifies both embedded baseline jars (core floor and engine fallback) are packaged in the shim jar. */
    @Test
    void theShippedStubCarriesBothBaselines() throws IOException {
        assertReadableZip("baseline/bamfos-updater-core.jar");
        assertReadableZip("baseline/bamfos-updater-engine.jar");
    }

    /**
     * FML instantiates ClientLocator via ServiceLoader outside our try/catch, so any <clinit>
     * failure crashes boot. Applied to all stub classes; compile-time constants emit no <clinit>.
     */
    @Test
    void noShippedClassDeclaresAStaticInitialiser() throws IOException {
        List<String> withInit = new ArrayList<>();
        int classes = 0;
        try (ZipFile z = new ZipFile(shimJar().toFile())) {
            for (ZipEntry e : z.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                classes++;
                byte[] bytes = z.getInputStream(e).readAllBytes();
                // <clinit> appears in the constant pool as a plain UTF-8 entry, so a byte scan suffices
                if (new String(bytes, StandardCharsets.ISO_8859_1).contains("<clinit>")) {
                    withInit.add(e.getName());
                }
            }
        }
        assertTrue(classes > 0);
        assertEquals(List.of(), withInit);
    }

    private static void assertReadableZip(String entry) throws IOException {
        byte[] nested;
        try (ZipFile z = new ZipFile(shimJar().toFile())) {
            ZipEntry e = z.getEntry(entry);
            assertNotNull(e, entry + " is not in the shipped stub");
            nested = z.getInputStream(e).readAllBytes();
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(nested))) {
            assertNotNull(in.getNextEntry(), entry + " is in the jar but it is not a readable zip");
        }
    }
}
