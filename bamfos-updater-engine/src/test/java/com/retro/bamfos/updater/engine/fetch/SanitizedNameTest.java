package com.retro.bamfos.updater.engine.fetch;

import com.retro.bamfos.updater.engine.manifest.FileEntry;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GitHub sanitizes asset filenames on upload ("ATi Structures Vanilla
 *  V1.4.4 (1.21+).jar" is stored as "ATi.Structures.Vanilla.V1.4.4.1.21+.jar"), the manifest's
 *  raw name must still resolve, or every spaced-name mod is unfetchable forever. */
class SanitizedNameTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private static final byte[] BYTES = "the mod".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/o/r/releases/tags/vT", ex -> {
            byte[] b = ("{\"tag_name\":\"vT\",\"assets\":[{\"name\":\"ATi.Structures.Vanilla.V1.4.4.1.21+.jar\",\"id\":9}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        server.createContext("/repos/o/r/releases/assets/9", ex -> {
            ex.sendResponseHeaders(200, BYTES.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(BYTES);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void rawManifestName_resolvesAgainstSanitizedStore() throws Exception {
        GithubReleases gh = new GithubReleases(base, "o/r", "tok", "vT", dir);
        FileEntry e = new FileEntry("mods/ATi Structures Vanilla V1.4.4 (1.21+).jar", "0".repeat(64), 1,
                FileEntry.Side.SERVER, FileEntry.Policy.MANAGED, FileEntry.Clazz.HOT,
                new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET,
                        "ATi Structures Vanilla V1.4.4 (1.21+).jar", null, null, null, null),
                List.of(), false, null, FileEntry.OnRemove.DELETE, null);
        Path dest = dir.resolve("out.jar");
        gh.fetch(e, dest);
        assertArrayEquals(BYTES, Files.readAllBytes(dest));
    }

    @Test
    void trulyAbsentAsset_stillFailsLoudly() {
        GithubReleases gh = new GithubReleases(base, "o/r", "tok", "vT", dir);
        FileEntry e = new FileEntry("mods/ghost.jar", "0".repeat(64), 1,
                FileEntry.Side.SERVER, FileEntry.Policy.MANAGED, FileEntry.Clazz.HOT,
                new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET, "ghost.jar", null, null, null, null),
                List.of(), false, null, FileEntry.OnRemove.DELETE, null);
        IOException ex = assertThrows(IOException.class, () -> gh.fetch(e, dir.resolve("x")));
        assertTrue(ex.getMessage().contains("not on release"));
    }
}
