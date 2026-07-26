package com.retro.bamfos.updater.engine.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Players hand-edit this file, so parsing needs to survive typos. One bad line used to drop the
 *  entire config including the token, leaving the updater polling a private repo anonymously after
 *  a single startup warning. */
class UpdaterConfigTest {

    @TempDir
    Path dir;

    private void write(String body) throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
    }

    /** A raw Windows path like C:\\users looks like an invalid \\u escape to Properties.load and
     *  causes loading the entire file to fail. */
    @Test
    void malformedEscape_keepsTheParseableLines() throws IOException {
        write("github_token=tok\nrepo=o/r\nnotes=C:\\users\\dave\\packs\nserver_host=1.2.3.4\n"
                + "# a comment survives being ignored\npin=mods/dev.jar\n");
        UpdaterConfig cfg = UpdaterConfig.load(dir);
        assertEquals("tok", cfg.githubToken());
        assertEquals("o/r", cfg.repo());
        assertEquals("1.2.3.4", cfg.serverHost());
        assertEquals(java.util.List.of("mods/dev.jar"), cfg.pins());
    }

    @Test
    void badPortValues_fallBack() throws IOException {
        write("server_port=99999\n");
        assertEquals(25565, UpdaterConfig.load(dir).serverPort());
        write("server_port=-1\n");
        assertEquals(25565, UpdaterConfig.load(dir).serverPort());
        write("server_port=0\n");
        assertEquals(25565, UpdaterConfig.load(dir).serverPort());
        write("server_port=zzz\n");
        assertEquals(25565, UpdaterConfig.load(dir).serverPort());
        write("server_port=25577\n");
        assertEquals(25577, UpdaterConfig.load(dir).serverPort());
    }

    @Test
    void absentFile_isAllDefaults() throws IOException {
        UpdaterConfig cfg = UpdaterConfig.load(dir);
        assertEquals("", cfg.githubToken());
        assertEquals("SixxHands/bamfos-pack", cfg.repo());
        assertEquals("", cfg.serverHost());
        assertEquals(25565, cfg.serverPort());
        assertTrue(cfg.pins().isEmpty());
        assertEquals("", cfg.tagPin());
        assertTrue(cfg.autoRestart());
    }

    /** The watchdog reads its keys from the same file via rawProps, so the fallback needs to keep them
     * or a bad line resets the poll interval without warning. */
    @Test
    void rawProps_carriesTheWatchdogKeysThroughAMalformedLine() throws IOException {
        write("notes=C:\\users\\dave\nwatchdog_poll_seconds=120\nwatchdog_countdown_seconds=30\n");
        var raw = UpdaterConfig.rawProps(dir);
        assertEquals("120", raw.getProperty("watchdog_poll_seconds"));
        assertEquals("30", raw.getProperty("watchdog_countdown_seconds"));
    }
}
