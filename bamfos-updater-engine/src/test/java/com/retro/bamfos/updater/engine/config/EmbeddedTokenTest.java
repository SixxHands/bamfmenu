package com.retro.bamfos.updater.engine.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests embedded token lookup, precedence, and fallback paths. Shards are hand-encoded here rather than
 * calling the Gradle encoder; EmbeddedTokenJarTest verifies the real built jar end to end. */
class EmbeddedTokenTest {

    private static final byte[] MASK = "7c1f9a4d3e60b285".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path dir;

    /** Mock shim jar containing the same three token shards produced by the build. */
    private void shim(Path jar, String token) throws IOException {
        Files.createDirectories(jar.getParent());
        byte[] raw = token.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (raw[i] ^ MASK[i % MASK.length]);
        }
        String b64 = Base64.getEncoder().encodeToString(raw);
        int a = b64.length() / 3;
        int b = 2 * b64.length() / 3;
        String[] parts = {b64.substring(0, a), b64.substring(a, b), b64.substring(b)};
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
            for (int i = 0; i < parts.length; i++) {
                z.putNextEntry(new ZipEntry("baseline/idx/" + i));
                z.write(parts[i].getBytes(StandardCharsets.ISO_8859_1));
                z.closeEntry();
            }
        }
    }

    private void props(String body) throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
    }

    /** Explicit config token must beat the embedded fallback so operators can swap in a new PAT without a rebuild. */
    @Test
    void propertiesToken_beatsTheEmbeddedOne() throws IOException {
        shim(dir.resolve("mods").resolve("bamfos-updater-boot.jar"), "embedded-value");
        props("github_token=pasted-by-an-operator\nrepo=o/r\n");
        assertEquals("pasted-by-an-operator", UpdaterConfig.load(dir).githubToken());

        // Properties.load keeps trailing whitespace after the separator, which often happens with
        // pasted PATs. If left untrimmed, GitHub rejects the Authorization header with 401.
        props("github_token=pasted-by-an-operator   \nrepo=o/r\n");
        assertEquals("pasted-by-an-operator", UpdaterConfig.load(dir).githubToken());
    }

    /** Default install has no token property configured (just the jars, no properties file). */
    @Test
    void noPropertiesFile_usesTheEmbeddedOne() throws IOException {
        shim(dir.resolve("mods").resolve("bamfos-updater-boot.jar"), "embedded-value");
        assertEquals("embedded-value", UpdaterConfig.load(dir).githubToken());
    }

    /** Treat a blank github_token= line as absent so seed configs fall back to the embedded token instead
     * of sending an empty Authorization header and 401ing. */
    @Test
    void blankPropertiesToken_fallsThroughToTheEmbeddedOne() throws IOException {
        shim(dir.resolve("mods").resolve("bamfos-updater-boot.jar"), "embedded-value");
        props("github_token=\nrepo=o/r\n");
        assertEquals("embedded-value", UpdaterConfig.load(dir).githubToken());
        props("github_token=    \nrepo=o/r\n");
        assertEquals("embedded-value", UpdaterConfig.load(dir).githubToken());
    }

    /** On servers the watchdog runs in a new JVM after the shim exits, so it has to find the shim at the
     * server root rather than only in mods/. */
    @Test
    void serverRootShim_isFound() throws IOException {
        shim(dir.resolve("bamfos-updater-boot.jar"), "embedded-value");
        assertEquals("embedded-value", UpdaterConfig.load(dir).githubToken());
    }

    /** A hand-dropped build keeps the version in its name; only the managed copy is renamed. */
    @Test
    void versionedShimName_isStillFound() throws IOException {
        shim(dir.resolve("mods").resolve("bamfos-updater-boot-0.1.0.jar"), "embedded-value");
        assertEquals("embedded-value", UpdaterConfig.load(dir).githubToken());
    }

    /** no shim, junk where a shim should be, or a shim built with no token input, all answer "". */
    @Test
    void everyMissingOrBrokenCase_degradesToEmptyAndNeverThrows() throws IOException {
        assertEquals("", UpdaterConfig.load(dir).githubToken());

        Path mods = dir.resolve("mods");
        Files.createDirectories(mods);
        Path jar = mods.resolve("bamfos-updater-boot.jar");
        Files.write(jar, new byte[]{0x00, 0x01, 0x02, 0x03}); // not a zip at all
        assertEquals("", UpdaterConfig.load(dir).githubToken());

        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
        }
        assertEquals("", UpdaterConfig.load(dir).githubToken());

        assertTrue(UpdaterConfig.load(dir).autoRestart());
    }

    /** All three base64 shards are required; a jar with only shard 0 must return "" rather than partial data. */
    @Test
    void aShardOnItsOwn_yieldsNothing() throws IOException {
        Path jar = dir.resolve("bamfos-updater-boot.jar");
        shim(jar, "embedded-value");
        byte[] whole = Files.readAllBytes(jar);
        String peek = new String(whole, StandardCharsets.ISO_8859_1);
        assertFalse(peek.contains("embedded-value"));

        Path stripped = dir.resolve("mods").resolve("bamfos-updater-boot.jar");
        Files.createDirectories(stripped.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(stripped))) {
            z.putNextEntry(new ZipEntry("baseline/idx/0"));
            z.write("AAAA".getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
        }
        Files.delete(jar);
        assertEquals("", UpdaterConfig.load(dir).githubToken());
    }
}
