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

    /** Same config file with a UTF-8 BOM prefix (as written by PowerShell 5.1 -Encoding utf8 or
     *  Notepad "UTF-8 with BOM"). */
    private void writeBommed(String body) throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(p.getParent());
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        raw.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        raw.writeBytes(body.getBytes(StandardCharsets.ISO_8859_1));
        Files.write(p, raw.toByteArray());
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

    /** toString() must redact githubToken so debug logs pasted into chat cannot leak the shared embedded PAT. */
    @Test
    void toString_saysWhetherThereIsATokenAndNeverWhatItIs() throws IOException {
        // Assemble the fake token at runtime so TokenLeakGateTest does not flag a PAT-shaped literal in source.
        String secret = "ghp_" + "x".repeat(40);
        write("github_token=" + secret + "\nrepo=o/r\ntag=v0.2.2\n");
        String drawn = UpdaterConfig.load(dir).toString();
        assertFalse(drawn.contains(secret), String.valueOf(drawn));
        assertTrue(drawn.contains("githubToken=<set>"), drawn);
        assertTrue(drawn.contains("o/r") && drawn.contains("v0.2.2"), String.valueOf(drawn));

        write("repo=o/r\n");
        assertTrue(UpdaterConfig.load(dir).toString().contains("githubToken=<none>"));
    }

    /** Strip a leading UTF-8 BOM (PowerShell/Notepad) before Properties.load, or U+FEFF becomes part of the
     * github_token key and the override reads as absent. */
    @Test
    void bomOnLineOne_doesNotEatTheToken() throws IOException {
        writeBommed("github_token=tok\nrepo=o/r\n");
        UpdaterConfig cfg = UpdaterConfig.load(dir);
        assertEquals("tok", cfg.githubToken());
        assertEquals("o/r", cfg.repo());
    }

    /** Test BOM stripping in the latin-1 fallback: 0xE9 makes UTF-8 readString throw, so the latin-1
     * fallback reads the UTF-8 BOM as three chars. Both paths need to strip it. */
    @Test
    void bomOnAnAnsiFile_stillDoesNotEatTheToken() throws IOException {
        writeBommed("github_token=tok\nnotes=caf" + (char) 0xE9 + "\n"); // a pasted e-acute, not UTF-8
        assertEquals("tok", UpdaterConfig.load(dir).githubToken());
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
