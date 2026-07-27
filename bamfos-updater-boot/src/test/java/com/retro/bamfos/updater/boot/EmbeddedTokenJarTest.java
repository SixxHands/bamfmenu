package com.retro.bamfos.updater.boot;

import com.retro.bamfos.updater.engine.config.EmbeddedToken;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the build.gradle encoder against the engine decoder using the shipped shim jar.
 * Verifies both with-token and tokenless builds without skipping.
 * IMPORTANT: compare sha256 hashes ONLY and never assert or log the raw token value.
 */
class EmbeddedTokenJarTest {

    /** Long enough to avoid false positives, short enough to catch a token split across pieces. */
    private static final int RUN = 16;

    private static Path shimJar() {
        String p = System.getProperty("bamfos.test.shimJar");
        assertNotNull(p);
        Path jar = Paths.get(p);
        assertTrue(Files.isRegularFile(jar), String.valueOf(jar));
        return jar;
    }

    @Test
    void builtShim_roundTripsTheBuildInputThroughTheEngineReader() throws IOException {
        assertEquals(sha256(TokenFixture.buildInputToken()), sha256(EmbeddedToken.fromJar(shimJar())));
    }

    // Make sure the embedded token doesn't fall out of a zip peek or strings dump (checks substrings too, not just the full token), they would have to do work to find it and that's fine by me if they did.
    @Test
    void builtShim_neverCarriesTheValueInPlaintext() throws IOException {
        Path jar = shimJar();
        String token = TokenFixture.buildInputToken();
        if (token.isEmpty()) {
            // Building without a token input must still produce a valid jar with nothing embedded.
            assertFalse(hasShards(jar));
            return;
        }
        assertTrue(hasShards(jar));

        byte[] raw = token.getBytes(StandardCharsets.ISO_8859_1);
        List<byte[]> haystacks = new ArrayList<>();
        collect(Files.readAllBytes(jar), 2, haystacks);
        for (byte[] hay : haystacks) {
            for (int at = 0; at <= Math.max(0, raw.length - RUN); at++) {
                byte[] window = new byte[Math.min(RUN, raw.length)];
                System.arraycopy(raw, at, window, 0, window.length);
                assertFalse(indexOf(hay, window) >= 0, String.valueOf(window.length) + " / " + String.valueOf(at));
            }
        }
    }

    private static boolean hasShards(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("baseline/idx/0") != null
                    && z.getEntry("baseline/idx/1") != null
                    && z.getEntry("baseline/idx/2") != null;
        }
    }

    /** Collects raw jar bytes plus every decompressed entry, recursing into nested jars. */
    private static void collect(byte[] data, int depth, List<byte[]> into) {
        into.add(data);
        if (depth == 0) {
            return;
        }
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    collect(z.readAllBytes(), depth - 1, into);
                }
            }
        } catch (IOException notAZip) {
            // Not a nested zip; raw entry bytes were already added above.
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException never) {
            throw new IllegalStateException(never);
        }
    }
}
