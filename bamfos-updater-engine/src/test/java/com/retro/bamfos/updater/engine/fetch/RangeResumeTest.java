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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** sync polish: a mid-body drop resumes with Range instead of re-downloading from byte 0. */
class RangeResumeTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private static final byte[] BODY = new byte[100_000];

    static {
        for (int i = 0; i < BODY.length; i++) {
            BODY[i] = (byte) (i * 31);
        }
    }

    private final AtomicInteger hits = new AtomicInteger();
    private volatile String lastRangeHeader;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/asset", ex -> {
            int hit = hits.incrementAndGet();
            lastRangeHeader = ex.getRequestHeaders().getFirst("Range");
            if (hit == 1) {
                // Advertise the full length, send half, and close the connection to simulate a dropped download.
                ex.sendResponseHeaders(200, BODY.length);
                OutputStream out = ex.getResponseBody();
                out.write(BODY, 0, BODY.length / 2);
                out.flush();
                ex.close();
                return;
            }
            // resumed request: honor the Range like S3 does
            long from = lastRangeHeader != null && lastRangeHeader.startsWith("bytes=")
                    ? Long.parseLong(lastRangeHeader.substring(6, lastRangeHeader.length() - 1)) : 0;
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (BODY.length - 1) + "/" + BODY.length);
            ex.sendResponseHeaders(from > 0 ? 206 : 200, BODY.length - from);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(BODY, (int) from, (int) (BODY.length - from));
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void midBodyDrop_resumesWithRange_bytesSpliceCorrectly() throws Exception {
        Path dest = dir.resolve("asset.bin");
        new HttpFetcher().download(base + "/asset", "tok", "application/octet-stream", dest);
        assertEquals(2, hits.get());
        assertEquals("bytes=" + (BODY.length / 2) + "-", lastRangeHeader);
        assertArrayEquals(BODY, Files.readAllBytes(dest));
        assertFalse(Files.exists(dir.resolve("asset.bin.dl")));
    }

    @Test
    void completeDlOrphaned_416OnResume_dropsItAndRestartsFromZero() throws Exception {
        // If a crash or locked move leaves a full-sized .dl file, requesting bytes=<fullLen>- gets HTTP 416
        // from S3 until we discard the leftover partial.
        server.createContext("/poison", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null) {
                ex.sendResponseHeaders(416, -1); // Range Not Satisfiable: offset is at/past the asset end
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, BODY.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(BODY);
            }
        });
        Path dest = dir.resolve("poison.bin");
        Files.write(dir.resolve("poison.bin.dl"), BODY); // a complete orphaned partial
        new HttpFetcher().download(base + "/poison", "tok", "application/octet-stream", dest);
        assertArrayEquals(BODY, Files.readAllBytes(dest));
        assertFalse(Files.exists(dir.resolve("poison.bin.dl")));
    }

    @Test
    void hostIgnoresRange_fullRestartStillConverges() throws Exception {
        // pre-plant a bogus partial: a host answering 200 must trigger truncate-and-restart
        Path dest = dir.resolve("asset.bin");
        Files.createDirectories(dest.getParent() == null ? dir : dest.getParent());
        Files.write(dir.resolve("asset.bin.dl"), new byte[]{9, 9, 9});
        hits.set(1); // Since our fixture honors Range, use a separate endpoint that ignores it and returns 200 with the full body.
        server.createContext("/plain", ex -> {
            ex.sendResponseHeaders(200, BODY.length); // 200 despite any Range = host ignored it
            try (OutputStream out = ex.getResponseBody()) {
                out.write(BODY);
            }
        });
        new HttpFetcher().download(base + "/plain", "tok", "application/octet-stream", dest);
        assertArrayEquals(BODY, Files.readAllBytes(dest));
    }
}
