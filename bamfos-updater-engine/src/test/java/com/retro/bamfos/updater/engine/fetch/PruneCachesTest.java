package com.retro.bamfos.updater.engine.fetch;

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

/** Cache pruning must keep the current tag's .dl resume partials while sweeping partials from
 * older tags. */
class PruneCachesTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private static final byte[] MANIFEST = "{\"pack\":true}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/o/r/releases/tags/vT", ex -> {
            byte[] b = "{\"tag_name\":\"vT\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1}]}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        server.createContext("/repos/o/r/releases/assets/1", ex -> {
            ex.sendResponseHeaders(200, MANIFEST.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(MANIFEST);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theSweep_keepsThisTagsResumePartial_andStillEatsDeadTagLitter() throws Exception {
        Files.write(dir.resolve("zip-vT-data.zip.dl"), new byte[]{1, 2, 3}); // Partial download left behind by a failed attempt.
        Files.write(dir.resolve("zip-vOLD-data.zip"), new byte[]{4});
        Files.write(dir.resolve("zip-vOLD-data.zip.dl"), new byte[]{5});
        Files.write(dir.resolve("manifest-vOLD.json"), new byte[]{6});
        new GithubReleases(base, "o/r", "tok", "vT", dir).manifestJson();
        assertTrue(Files.exists(dir.resolve("zip-vT-data.zip.dl")));
        assertFalse(Files.exists(dir.resolve("zip-vOLD-data.zip")));
        assertFalse(Files.exists(dir.resolve("zip-vOLD-data.zip.dl")));
        assertFalse(Files.exists(dir.resolve("manifest-vOLD.json")));
    }
}
