package com.retro.bamfos.updater.boot;

import com.retro.bamfos.updater.core.CoreEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies CoreSlot.clientBoot runs once per JVM and only binds a valid ops handle.
 * Tested here instead of ClientLocator because ClientLocator cannot run outside FML.
 */
class ClientDoorHandoffTest {

    /** Keep this property name literal: two different shim builds in mods/ must agree on the exact string
     *  for the double-boot guard to work, so renaming it breaks cross-version compatibility. */
    private static final String ONCE = "bamfos.updater.core.ran";

    /** Don't use @TempDir: a loaded core keeps its jar open for the session, so Windows can't delete it. */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    private final List<String> properties = new ArrayList<>();
    private int boxes;

    @AfterEach
    void sweep() throws IOException {
        System.clearProperty(ONCE);
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

    /**
     * Duplicate shim jars in mods/ both get service-loaded; without this guard, the second
     * run would overwrite the first mark and double-sync the same directory.
     */
    @Test
    void theSecondServicePromotedShimDoesNothing() throws Exception {
        Path box = box();
        CoreFixture.install(box, "core-0009.jar");

        CoreSlot.clientBoot(box);
        String markAfterTheFirst = Files.readString(mark(box), StandardCharsets.UTF_8);

        CoreSlot.clientBoot(box);

        assertEquals(List.of("core-0009.jar"), ranLog(box));
        assertEquals(markAfterTheFirst, Files.readString(mark(box), StandardCharsets.UTF_8));
    }

    /**
     * Missing or wrong-type handles must degrade to "not installed" rather than crashing older engines.
     * Kept in one test with the valid handle last because the static bridge binding is never cleared.
     */
    @Test
    void opsBindsOnlyWhenTheCoreReallySentAJavaBaseFunction() throws Exception {
        assertFalse(UpdaterBridge.present());

        knock("core-0009.jar", null);
        assertFalse(UpdaterBridge.present());

        knock("core-0008.jar", "bad-ops");
        assertFalse(UpdaterBridge.present());

        knock("core-0007.jar", "ops");
        assertTrue(UpdaterBridge.present());
        assertEquals("ok:status", UpdaterBridge.ops("status", new Object[0]));
    }

    /** Boot safety: client boot still returns cleanly on an empty directory. */
    @Test
    void anEmptyBoxCostsTheBootNothing() {
        assertDoesNotThrow(() -> CoreSlot.clientBoot(box()));
    }

    // ---- fixtures ----

    /** Runs one client boot against an isolated instance folder. */
    private void knock(String jar, String behaviour) throws IOException {
        Path box = box();
        CoreFixture.install(box, jar);
        if (behaviour != null) {
            String key = CoreEntry.BEHAVIOUR + jar;
            properties.add(key);
            System.setProperty(key, behaviour);
        }
        System.clearProperty(ONCE);
        CoreSlot.clientBoot(box);
        assertEquals(List.of(jar), ranLog(box));
    }

    private Path box() throws IOException {
        return Files.createDirectories(tmp.resolve("box-" + boxes++));
    }

    private static Path mark(Path box) {
        return CoreFixture.coreDir(box).resolve("core-unproven");
    }

    private static List<String> ranLog(Path box) throws IOException {
        Path log = box.resolve(CoreEntry.RAN_LOG);
        return Files.isRegularFile(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
    }
}
