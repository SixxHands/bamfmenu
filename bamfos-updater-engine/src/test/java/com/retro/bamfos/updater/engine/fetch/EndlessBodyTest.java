package com.retro.bamfos.updater.engine.fetch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Enforce a byte ceiling on chunked downloads so an endless stream cannot keep resetting the stall
 * timer and fill the disk (uses a small test cap instead of the shipped 2 GiB ASSET_MAX). */
class EndlessBodyTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private static final long CAP = 64 * 1024;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // Send 5 MB chunked against a 64 KB cap. Kept bounded so a regression fails the assertion instead
        // of hanging the test suite.
        server.createContext("/runaway", ex -> {
            ex.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 80; i++) {
                    out.write(chunk);
                }
            } catch (IOException clientHungUp) {
                // the fetcher cutting the stream mid-body is the expected outcome here
            }
        });
        server.createContext("/modest", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(new byte[10 * 1024]);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aChunkedBodyPastTheCap_isCutOff_insteadOfWritingUntilTheDiskRefuses() {
        HttpFetcher f = new HttpFetcher(HttpFetcher.STALL_MS, CAP);
        IOException e = assertThrows(IOException.class,
                () -> f.download(base + "/runaway", "tok", "application/octet-stream", dir.resolve("big.bin")));
        assertInstanceOf(HttpFetcher.TransportFault.class, e, "a runaway stream is the world's fault: " + e);
        assertTrue(e.getMessage().contains("cap"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("big.bin")));
    }

    @Test
    void aChunkedBodyUnderTheCap_stillConverges() throws Exception {
        HttpFetcher f = new HttpFetcher(HttpFetcher.STALL_MS, CAP);
        Path dest = dir.resolve("small.bin");
        f.download(base + "/modest", "tok", "application/octet-stream", dest);
        assertEquals(10 * 1024, Files.size(dest));
    }
}
