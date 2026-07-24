package com.retro.bamfos.updater.engine.fetch;

import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Offline GitHub API fixture tests: cross-release backrefs, 302-to-S3 Authorization stripping, and
 * 401 token-death handling. */
class FixtureServerTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private String base;
    private static final byte[] ASSET = "jar bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INNER = "config bytes".getBytes(StandardCharsets.UTF_8);
    private volatile boolean s3SawAuth;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        byte[] zip = zipWith("config/a.toml", INNER);
        // two releases: v2 (current) references v1's asset via backref; asset ids are fixed
        server.createContext("/repos/o/r/releases/tags/v1", ex ->
                json(ex, "{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"old.jar\",\"id\":11}]}"));
        server.createContext("/repos/o/r/releases/tags/v2", ex ->
                json(ex, "{\"tag_name\":\"v2\",\"assets\":[{\"name\":\"new.jar\",\"id\":21},{\"name\":\"data.zip\",\"id\":22}]}"));
        server.createContext("/repos/o/r/releases/assets/", ex -> {
            if (ex.getRequestHeaders().getFirst("Authorization") == null) {
                ex.sendResponseHeaders(401, -1); // the API side DOES require auth
                return;
            }
            String id = ex.getRequestURI().getPath().substring("/repos/o/r/releases/assets/".length());
            ex.getResponseHeaders().add("Location", base + "/s3/" + id);
            ex.sendResponseHeaders(302, -1);
        });
        server.createContext("/s3/", ex -> {
            if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                s3SawAuth = true;
                ex.sendResponseHeaders(400, -1); // Simulated S3 rejection response.
                return;
            }
            byte[] body = ex.getRequestURI().getPath().endsWith("/22") ? zip : ASSET;
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/dead/repos/o/r/releases/tags/v2", ex -> ex.sendResponseHeaders(401, -1));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void assetDownload_stripsAuthOnTheS3Hop() throws Exception {
        GithubReleases gh = new GithubReleases(base, "o/r", "tok", "v2", tmp);
        Path dest = tmp.resolve("new.jar");
        gh.fetch(entry("mods/new.jar", "new.jar", null, null), dest);
        assertArrayEquals(ASSET, Files.readAllBytes(dest));
        assertFalse(s3SawAuth);
    }

    @Test
    void crossReleaseBackref_fetchesFromTheOlderTag() throws Exception {
        GithubReleases gh = new GithubReleases(base, "o/r", "tok", "v2", tmp);
        Path dest = tmp.resolve("old.jar");
        gh.fetch(entry("mods/old.jar", "old.jar", "v1", null), dest); // incremental model in action
        assertArrayEquals(ASSET, Files.readAllBytes(dest));
    }

    @Test
    void releaseZipInner_extracts() throws Exception {
        GithubReleases gh = new GithubReleases(base, "o/r", "tok", "v2", tmp);
        Path dest = tmp.resolve("a.toml");
        gh.fetch(entry("config/a.toml", "data.zip", null, "config/a.toml"), dest);
        assertArrayEquals(INNER, Files.readAllBytes(dest));
    }

    @Test
    void tokenDeath_isItsOwnState_neverRetried() {
        GithubReleases gh = new GithubReleases(base + "/dead", "o/r", "expired", "v2", tmp);
        long t0 = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        assertInstanceOf(HttpFetcher.TokenDead.class, e, "401 must be TokenDead, not a generic failure");
        assertTrue((System.nanoTime() - t0) < 900_000_000L);
    }

    private static FileEntry entry(String path, String asset, String release, String inner) {
        FileEntry.Source src = inner != null
                ? new FileEntry.Source(FileEntry.Source.Type.RELEASE_ZIP, asset, inner, release, null, null)
                : new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET, asset, null, release, null, null);
        return new FileEntry(path, "0".repeat(64), 1, FileEntry.Side.BOTH, FileEntry.Policy.MANAGED,
                FileEntry.Clazz.HOT, src, List.of(), false, null, FileEntry.OnRemove.DELETE);
    }

    private static void json(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        if (ex.getRequestHeaders().getFirst("Authorization") == null) {
            ex.sendResponseHeaders(401, -1);
            return;
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static byte[] zipWith(String inner, byte[] bytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(inner));
            z.write(bytes);
            z.closeEntry();
        }
        return bos.toByteArray();
    }
}
