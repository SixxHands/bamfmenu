package com.retro.bamfos.updater.engine.ping;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the parser against a live fixture captured from the real server. */
class BetterStatusTest {

    @Test
    void parsesTheLiveFixture() throws IOException {
        String fixture;
        try (InputStream in = getClass().getResourceAsStream("/slp-status-v0.2.4.json")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Optional<BetterStatus> bs = BetterStatus.parse(fixture);
        assertTrue(bs.isPresent());
        assertEquals("BAMFOS", bs.get().name());
        assertEquals("v0.2.4", bs.get().version()); // v-prefix included, byte-for-byte = release tag
    }

    @Test
    void noSignalCases() {
        assertTrue(BetterStatus.parse("{\"version\":{\"name\":\"1.21.1\"}}").isEmpty());
        assertTrue(BetterStatus.parse("{\"betterStatus\":{\"name\":\"CHANGE_ME\",\"version\":\"CHANGE_ME\"}}").isEmpty());
        assertTrue(BetterStatus.parse("not json").isEmpty());
    }
}
