package com.retro.bamfos.updater.engine.cargo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Core delivery tests: the stub keeps the active core jar open while running, so updates must
 * write a new generation filename and never restore a refused generation. */
class CoreCargoTest {

    @TempDir
    Path box;

    private Path core() {
        return box.resolve(".bamfos-updater").resolve("core");
    }

    /** Builds a mock engine jar matching the real layout: counter in manifest, core jar under /core/. */
    private Path engineJar(String name, String gen, byte[] cargo) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (gen != null) {
            mf.getMainAttributes().putValue("Updater-Core-Gen", gen);
        }
        Path jar = box.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            if (cargo != null) {
                out.putNextEntry(new ZipEntry("core/bamfos-updater-core.jar"));
                out.write(cargo);
                out.closeEntry();
            }
        }
        return jar;
    }

    private byte[] fakeCore(String marker) {
        return marker.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void placeFrom_writesTheGenerationTheStagedEngineCarries() throws IOException {
        CoreCargo.placeFrom(box, engineJar("staged.jar", "0007", fakeCore("gen 7")));
        assertArrayEquals(fakeCore("gen 7"), Files.readAllBytes(core().resolve("core-0007.jar")));
        try (var list = Files.list(core())) {
            assertEquals(1, list.count());
        }
    }

    /** Each new core generation uses a new filename so we never overwrite the jar the stub has open
     * (which corrupts the classloader on Linux or fails with AccessDenied on Windows). */
    @Test
    void placeFrom_neverOverwritesAGenerationAlreadyOnDisk() throws IOException {
        Files.createDirectories(core());
        Files.write(core().resolve("core-0007.jar"), fakeCore("the core that is RUNNING"));
        CoreCargo.placeFrom(box, engineJar("staged.jar", "0007", fakeCore("different bytes, same name")));
        assertArrayEquals(fakeCore("the core that is RUNNING"),
                Files.readAllBytes(core().resolve("core-0007.jar")));
    }

    /** CoreHealth deletes a jar on its second failure to mark it refused. The engine must not
     *  re-extract that generation on the next boot. */
    @Test
    void placeFrom_respectsCoreRejected() throws IOException {
        Files.createDirectories(core());
        Files.writeString(core().resolve("core-rejected"), "core-0007.jar\t1754000000000\n");
        CoreCargo.placeFrom(box, engineJar("staged.jar", "0007", fakeCore("burned")));
        assertFalse(Files.exists(core().resolve("core-0007.jar")));
    }

    /** Refusals are per generation, so refusing 0007 must still allow 0008 to install. */
    @Test
    void placeFrom_stillDeliversAGenerationTheListDoesNotName() throws IOException {
        Files.createDirectories(core());
        Files.writeString(core().resolve("core-rejected"), "core-0007.jar\t1754000000000\n");
        CoreCargo.placeFrom(box, engineJar("staged.jar", "0008", fakeCore("the fix")));
        assertTrue(Files.exists(core().resolve("core-0008.jar")));
    }

    /** 0000 is reserved for the baseline jar extracted from stub resources, and non-4-digit names
     *  break lexical sorting in the stub. */
    @Test
    void placeFrom_refusesTheReservedFloorAndAnUnorderableCounter() throws IOException {
        CoreCargo.placeFrom(box, engineJar("floor.jar", "0000", fakeCore("over the floor")));
        CoreCargo.placeFrom(box, engineJar("short.jar", "7", fakeCore("unorderable")));
        CoreCargo.placeFrom(box, engineJar("wide.jar", "10000", fakeCore("past four digits")));
        assertFalse(Files.exists(core().resolve("core-0000.jar")));
        assertFalse(Files.exists(core().resolve("core-7.jar")));
        assertFalse(Files.exists(core().resolve("core-10000.jar")));
    }

    /** Older engines without embedded cargo boot normally and leave existing core generations untouched. */
    @Test
    void placeFrom_saysNothingAboutAnEngineWithNoCargo() throws IOException {
        CoreCargo.placeFrom(box, engineJar("old.jar", null, null));
        CoreCargo.placeFrom(box, engineJar("half.jar", "0009", null));
        assertFalse(Files.isDirectory(core()));
    }

    /** Runs on the boot path against a downloaded file, so a truncated or corrupt .new must log and never throw. */
    @Test
    void placeFrom_survivesAJarThatIsNotAJar() throws IOException {
        Path junk = box.resolve("truncated.jar");
        Files.write(junk, new byte[]{'P', 'K', 3, 4, 0, 0});
        CoreCargo.placeFrom(box, junk);
        CoreCargo.placeFrom(box, box.resolve("nothing-here.jar"));
    }

    /** The operator's way back in. Both files, because a strike short of a refusal still counts toward one. */
    @Test
    void forgiveRejectedCores_clearsBothMemories() throws IOException {
        Files.createDirectories(core());
        Files.writeString(core().resolve("core-rejected"), "core-0007.jar\t1754000000000\n");
        Files.writeString(core().resolve("core-strikes"), "core-0008.jar\t1\n");
        CoreCargo.forgiveRejectedCores(box);
        assertFalse(Files.exists(core().resolve("core-rejected")));
        assertFalse(Files.exists(core().resolve("core-strikes")));
        CoreCargo.forgiveRejectedCores(box); // Common case: nothing to clean up.
    }

    /** Verifies the generated counter, embedded jar, and extraction against real build output so a
     * moved resource can't leave installs stuck on the baseline generation. */
    @Test
    void place_writesTheCoreThisEngineWasBuiltWith() throws IOException {
        assertTrue(CoreGen.GEN.matches("\\d{4}"));
        assertFalse(CoreGen.GEN.equals("0000"));
        try (InputStream in = CoreCargo.class.getResourceAsStream("/core/bamfos-updater-core.jar")) {
            assertNotNull(in);
        }
        CoreCargo.place(box);
        Path placed = core().resolve("core-" + CoreGen.GEN + ".jar");
        assertTrue(Files.exists(placed));
        assertTrue(entryNames(placed).contains("com/retro/bamfos/updater/core/CoreEntry.class"));
    }

    private String entryNames(Path jar) throws IOException {
        StringBuilder names = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.append(e.getName()).append('\n');
            }
        }
        return names.toString();
    }
}
