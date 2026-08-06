package com.retro.bamfos.updater.engine.fetch;

import com.retro.bamfos.updater.engine.guard.EngineHealth;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Bad redirect Location headers must throw TransportFault (not IAE), and presigned S3 URLs must
 * never leak into exception messages written to logs. */
class RedirectHopTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private static final byte[] BYTES = "the asset".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // Relative 302 redirect (some proxies rewrite Location headers this way).
        server.createContext("/relative", ex -> {
            ex.getResponseHeaders().add("Location", "/s3/9");
            ex.sendResponseHeaders(302, -1);
        });
        server.createContext("/s3/9", ex -> {
            ex.sendResponseHeaders(200, BYTES.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(BYTES);
            }
        });
        // Present-but-blank header: firstValue returns a non-empty Optional, so absent-only guards miss it.
        server.createContext("/blank", ex -> {
            ex.getResponseHeaders().add("Location", "");
            ex.sendResponseHeaders(302, -1);
        });
        server.createContext("/scheme", ex -> {
            ex.getResponseHeaders().add("Location", "ftp://example.invalid/x");
            ex.sendResponseHeaders(302, -1);
        });
        // Common case for the auth-stripped hop: an expired presigned URL returning 4xx.
        server.createContext("/signed", ex -> {
            ex.getResponseHeaders().add("Location",
                    base + "/denied/a.jar?X-Amz-Credential=AKIAFAKE%2Freq&X-Amz-Signature=deadbeefcafe");
            ex.sendResponseHeaders(302, -1);
        });
        server.createContext("/denied", ex -> ex.sendResponseHeaders(403, -1));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aRelativeLocation_resolvesAgainstTheHostThatSentIt() throws Exception {
        Path dest = dir.resolve("a.bin");
        new HttpFetcher().download(base + "/relative", "tok", "application/octet-stream", dest);
        assertArrayEquals(BYTES, Files.readAllBytes(dest));
    }

    @Test
    void aBlankLocation_isATransportFault_neverAnUncheckedEscape() {
        HttpFetcher.TransportFault e = assertThrows(HttpFetcher.TransportFault.class,
                () -> new HttpFetcher().getBody(base + "/blank", "tok", "application/json"));
        assertTrue(e.getMessage().contains("without Location"), String.valueOf(e.getMessage()));
        assertTrue(EngineHealth.attributable(e));
    }

    @Test
    void aNonHttpLocation_isATransportFault_neverAnUncheckedEscape() {
        HttpFetcher.TransportFault e = assertThrows(HttpFetcher.TransportFault.class,
                () -> new HttpFetcher().getBody(base + "/scheme", "tok", "application/json"));
        assertTrue(e.getMessage().contains("Location"), e.getMessage());
    }

    /** 401/403 errors on the auth-stripped hop come from the S3 URL, which includes X-Amz-Credential
     *  and X-Amz-Signature, and that error message gets logged. Keep the status code and path, strip the query. */
    @Test
    void aPresignedHopUrl_neverLeaksItsQueryIntoTheMessage() {
        IOException e = assertThrows(IOException.class,
                () -> new HttpFetcher().getBody(base + "/signed", "tok", "application/json"));
        assertInstanceOf(HttpFetcher.HttpStatusError.class, e, "a 403 off the hop is a status answer");
        assertFalse(e.getMessage().contains("X-Amz-Signature"), String.valueOf(e.getMessage()));
        assertFalse(e.getMessage().contains("deadbeefcafe"), String.valueOf(e.getMessage()));
        assertTrue(e.getMessage().contains("HTTP 403"), String.valueOf(e.getMessage()));
        assertTrue(e.getMessage().contains("/denied/a.jar"), String.valueOf(e.getMessage()));
    }
}
