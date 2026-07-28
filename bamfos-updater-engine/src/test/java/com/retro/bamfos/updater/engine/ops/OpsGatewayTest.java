package com.retro.bamfos.updater.engine.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UpdaterOps and the BIOS share no classes, so op names are plain strings and the compiler won't catch
 * a missing switch case. Unwired ops return an error string that OpsBridge converts to NO_ANSWER.
 */
class OpsGatewayTest {

    /* Server-only watchdog ops kept off the BIOS gateway; new UpdaterOps methods must be wired or listed here. */
    private static final Set<String> NOT_ON_THE_GATEWAY = Set.of(
            "serverSyncNow",  // server sync, run by /bamfos sync
            "serverRepair",   // server repair, run by /bamfos repair (BIOS repair() is client-only;
                              // calling that on a server would plan the wrong files)
            "clearTokenDead"  // ditto, /bamfos cleartoken
    );

    /**
     * Verifies every public op is wired or excluded by scanning the class constant pool (works from a jar).
     * Misspelled case labels still appear in the pool, so the round-trip test below covers those.
     */
    @Test
    void everyPublicOpIsEitherOnTheGatewayOrExplicitlyNot() throws IOException {
        String pool = classBytes();
        List<String> missing = new ArrayList<>();
        for (Method m : UpdaterOps.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            String name = m.getName();
            if (NOT_ON_THE_GATEWAY.contains(name) || pool.contains(name)) {
                continue;
            }
            missing.add(name);
        }
        assertTrue(missing.isEmpty(), String.valueOf(missing));
    }

    /** Every name in this list should still exist, otherwise the entry is stale. */
    @Test
    void theExclusionListNamesRealMethods() {
        List<String> real = new ArrayList<>();
        for (Method m : UpdaterOps.class.getDeclaredMethods()) {
            real.add(m.getName());
        }
        for (String excluded : NOT_ON_THE_GATEWAY) {
            assertTrue(real.contains(excluded), excluded + " is on the exclusion list but is not a method");
        }
    }

    /**
     * Calls setServer through the gateway like the BIOS does and expects an Integer, since an unwired
     * op returns an error String that OpsBridge turns into NO_ANSWER.
     */
    @Test
    void setServerCrossesTheGatewayAndAnswersWithACode() throws IOException {
        Path dir = Files.createTempDirectory("gateway");
        OpsGateway.bind(dir, null);

        // port 0 is out of range, so setServer returns 2 without touching disk or network
        Object answer = OpsGateway.invoke("setServer", new Object[]{"1.1.1.1", 0});

        assertFalse(answer instanceof String, String.valueOf(answer));
        assertEquals(2, answer);
    }

    /** Port comes in boxed across module boundaries (no shared types), so test type coercion. */
    @Test
    void aPortThatIsNotANumberIsRefusedNotThrown() throws IOException {
        Path dir = Files.createTempDirectory("gateway-junk");
        OpsGateway.bind(dir, null);

        Object answer = OpsGateway.invoke("setServer", new Object[]{"1.1.1.1", "25565"});

        assertEquals(2, answer);
    }

    /** Make sure the default branch still works too. */
    @Test
    void aGenuinelyUnknownOpStillSaysSo() throws IOException {
        OpsGateway.bind(Files.createTempDirectory("gateway-unknown"), null);
        assertEquals("error: unknown op nonesuch", OpsGateway.invoke("nonesuch", new Object[0]));
    }

    private static String classBytes() throws IOException {
        try (InputStream in = OpsGateway.class.getResourceAsStream("OpsGateway.class")) {
            assertNotNull(in);
            // Latin-1 maps every byte 1:1, so UTF-8 entries in the constant pool stay intact.
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
