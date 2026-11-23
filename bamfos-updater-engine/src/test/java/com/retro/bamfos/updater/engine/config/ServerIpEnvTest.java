package com.retro.bamfos.updater.engine.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerIpEnvTest {

    @TempDir
    Path dir;

    @Test
    void bundledPublicServerIpEnvIsEmpty() {
        assertEquals("", ServerIpEnv.raw());
        assertEquals("", ServerIpEnv.host());
        assertEquals(25565, ServerIpEnv.port(25565));
    }

    @Test
    void parseValueReadsKeyValueAndIgnoresComments() {
        String text = "# comment\n\nOTHER=ignored\nSERVER_IP=\"play.example.com:25570\"\n";
        assertEquals("play.example.com:25570", ServerIpEnv.parseValue(text));
        assertEquals("play.example.com", ServerIpEnv.extractHost("play.example.com:25570"));
        assertEquals(25570, ServerIpEnv.extractPort("play.example.com:25570", 25565));
    }

    @Test
    void parseValueReadsEmptyAndIpv6() {
        assertEquals("", ServerIpEnv.parseValue("SERVER_IP=\n"));
        assertEquals("::1", ServerIpEnv.extractHost("[::1]:25566"));
        assertEquals(25566, ServerIpEnv.extractPort("[::1]:25566", 25565));
        assertEquals(25565, ServerIpEnv.extractPort("play.example.com", 25565));
    }

    @Test
    void updaterConfigDefaultsMatchBundledServerIpEnv() throws IOException {
        UpdaterConfig cfg = UpdaterConfig.load(dir);
        assertEquals(ServerIpEnv.host(), cfg.serverHost());
        assertEquals(ServerIpEnv.port(25565), cfg.serverPort());
    }
}
