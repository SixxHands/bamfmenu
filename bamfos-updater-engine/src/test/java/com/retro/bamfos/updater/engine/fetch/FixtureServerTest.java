package com.retro.bamfos.updater.engine.fetch;

import com.retro.bamfos.updater.engine.guard.EngineHealth;
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
import java.util.Map;
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
    private volatile int briefLimitHits;
    /** Counted, not timed: elapsed time cannot tell "asked once" from "asked three times fast". */
    private final java.util.concurrent.atomic.AtomicInteger goneHits = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger brokenHits = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.CountDownLatch stallRelease = new java.util.concurrent.CountDownLatch(1);

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
        // GitHub rate limit: 403 with rate-limit headers (not a bad token).
        server.createContext("/limited/repos/o/r/releases/tags/v2", ex -> {
            ex.getResponseHeaders().add("x-ratelimit-remaining", "0");
            ex.getResponseHeaders().add("retry-after", "60");
            ex.sendResponseHeaders(403, -1);
        });
        // Plain 403 with no rate-limit headers or body should be treated as an auth failure.
        server.createContext("/scope/repos/o/r/releases/tags/v2", ex -> ex.sendResponseHeaders(403, -1));
        server.createContext("/toomany/repos/o/r/releases/tags/v2", ex -> ex.sendResponseHeaders(429, -1));
        // Rate-limit headers stripped, message in body with a Content-Length header.
        server.createContext("/bodylimit/repos/o/r/releases/tags/v2", ex -> {
            byte[] b = "{\"message\":\"API rate limit exceeded for user 1\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(403, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        // Same rate-limit body without Content-Length (chunked encoding, like some proxies send).
        server.createContext("/chunked/repos/o/r/releases/tags/v2", ex -> {
            ex.sendResponseHeaders(403, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write("{\"message\":\"API rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8));
            }
        });
        // Captive-portal stall: promises 200 bytes, sends 40, then blocks on a teardown latch so stop() finishes.
        server.createContext("/stalled/repos/o/r/releases/tags/v2", ex -> {
            ex.sendResponseHeaders(403, 200);
            OutputStream out = ex.getResponseBody();
            out.write("{\"message\":\"API rate limit exce".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                stallRelease.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        // Missing release tag (404 shouldn't be retried).
        server.createContext("/gone/repos/o/r/releases/tags/v2", ex -> {
            goneHits.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
        });
        server.createContext("/broken/", ex -> {
            brokenHits.incrementAndGet();
            ex.sendResponseHeaders(500, -1);
        });
        // Accept-then-silent peer: java.net.http throws without a java.net cause, so send() must type it.
        server.createContext("/hangup/", com.sun.net.httpserver.HttpExchange::close);
        // 200 response that sends a few bytes and stalls without closing (like a dropped connection),
        // which used to hang FML discovery or the server wrapper indefinitely.
        server.createContext("/stalledbody/", ex -> {
            ex.sendResponseHeaders(200, 4096);
            OutputStream out = ex.getResponseBody();
            out.write(new byte[64]);
            out.flush();
            try {
                stallRelease.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        // Secondary rate limit: returns Retry-After once, then succeeds.
        server.createContext("/brief/repos/o/r/releases/tags/v2", ex -> {
            if (briefLimitHits++ == 0) {
                ex.getResponseHeaders().add("retry-after", "1");
                ex.sendResponseHeaders(403, -1);
                return;
            }
            json(ex, "{\"tag_name\":\"v2\",\"assets\":[]}");
        });
        // Use a thread pool instead of the default single-threaded executor so stalled handlers don't block
        // subsequent requests from retry tests.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fixture-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    @AfterEach
    void stop() {
        stallRelease.countDown(); // Release stalled handlers before stop() so teardown doesn't block on them.
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
        assertEquals(401, ((HttpFetcher.TokenDead) e).code);
        assertTrue((System.nanoTime() - t0) < 900_000_000L);
    }

    /** GitHub also returns 403 for rate limits; misclassifying it as token death writes a persistent
     * marker for a working credential. */
    @Test
    void rateLimit403_isNotTokenDeath() {
        GithubReleases gh = new GithubReleases(base + "/limited", "o/r", "alive", "v2", tmp);
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        assertInstanceOf(HttpFetcher.RateLimited.class, e, "a limit-headered 403 is a pause, not a dead token");
        assertFalse(e instanceof HttpFetcher.TokenDead);
        assertEquals(60_000L, ((HttpFetcher.RateLimited) e).retryAfterMs);
    }

    @Test
    void bare403_isStillTokenDeath() {
        GithubReleases gh = new GithubReleases(base + "/scope", "o/r", "wrongscope", "v2", tmp);
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        assertInstanceOf(HttpFetcher.TokenDead.class, e,
                "a 403 with nothing saying rate limit is a real permission failure");
        assertEquals(403, ((HttpFetcher.TokenDead) e).code);
    }

    /** Wait out a short retry-after instead of aborting the whole sync over a one-second pause.
     *  Longer retry-after values are the hourly rate limit, so skip waiting and fail right away. */
    @Test
    void shortRetryAfter_isWaitedOutOnce_thenTheSyncProceeds() throws Exception {
        GithubReleases gh = new GithubReleases(base + "/brief", "o/r", "alive", "v2", tmp);
        long t0 = System.nanoTime();
        gh.release("v2"); // throws if the wait is not honored, because nothing else retries a RateLimited
        assertTrue((System.nanoTime() - t0) >= 900_000_000L);
        assertEquals(2, briefLimitHits);
    }

    /** Fallback when a proxy strips the rate-limit headers: detect rate limiting from the 403 body
     * instead of treating it as a bad token. */
    @Test
    void limitNamedOnlyInTheBody_isStillRateLimited() {
        GithubReleases gh = new GithubReleases(base + "/bodylimit", "o/r", "alive", "v2", tmp);
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        assertInstanceOf(HttpFetcher.RateLimited.class, e, "the body is the fallback when headers are gone");
    }

    /** Skip body peeking on chunked 403s (no content-length and no HttpRequest.timeout on streams) so a
     * stalling portal cannot hang FML discovery; fall through to token death. */
    @Test
    void chunked403_isNotPeekedAtAll_evenWhenTheBodyNamesTheLimit() {
        GithubReleases gh = new GithubReleases(base + "/chunked", "o/r", "alive", "v2", tmp);
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        assertInstanceOf(HttpFetcher.TokenDead.class, e,
                "an unbounded body read on the boot path is the Law 5 failure, never worth the diagnosis");
    }

    /** A content-length header can still stall mid-body (readNBytes blocks waiting for all bytes), so
     * the wall-clock timeout must abort the peek before it hangs FML discovery. */
    @Test
    void promisedLengthThatNeverArrives_failsFast_insteadOfParkingTheBoot() {
        GithubReleases gh = new GithubReleases(base + "/stalled", "o/r", "alive", "v2", tmp);
        long t0 = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertInstanceOf(HttpFetcher.TokenDead.class, e,
                "nothing was proven, so it falls through to token death like every other unreadable peek");
        assertTrue(ms < 10_000, String.valueOf(ms));
    }

    /** Don't sleep after the final failed attempt when we're about to throw anyway (1s + 2s, not
     * 1s + 2s + 4s during FML startup). */
    @Test
    void theLastAttemptThrowsWithoutSleepingOnTheBackoff() {
        HttpFetcher f = new HttpFetcher();
        long t0 = System.nanoTime();
        assertThrows(IOException.class, () -> f.get(base + "/broken/x", "tok", "application/json"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5_000, String.valueOf(ms));
    }

    /** HttpRequest.timeout ends once headers arrive, so downloads need a stall clock (not a total
     * timeout, so slow valid jars still finish) to avoid hanging boot forever. */
    @Test
    void aBodyThatStallsMidTransfer_failsOnTheStallClock_insteadOfParkingTheBoot() {
        HttpFetcher f = new HttpFetcher(700);
        long t0 = System.nanoTime();
        assertThrows(IOException.class,
                () -> f.download(base + "/stalledbody/x", "tok", "application/octet-stream", tmp.resolve("x.jar")));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 10_000, String.valueOf(ms));
    }

    /** Same stall test for the small-body path (used by boot lookups and the watchdog poll thread),
     * where a stuck readAllBytes would wedge polling until restart. */
    @Test
    void readAllBounded_givesUpOnAStalledStream_andCapsTheSize() {
        java.io.InputStream wedged = new java.io.InputStream() {
            int left = 8;

            @Override
            public int read() {
                if (left-- > 0) {
                    return 'x';
                }
                try {
                    Thread.sleep(60_000); // Endpoint that accepts the connection and stalls mid-body.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        long t0 = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> HttpFetcher.readAllBounded(wedged, 4096, 400));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 8_000, String.valueOf(ms));
        assertTrue(e.getMessage().contains("stalled"), e.getMessage());

        byte[] tooBig = new byte[9000];
        assertThrows(IOException.class,
                () -> HttpFetcher.readAllBounded(new java.io.ByteArrayInputStream(tooBig), 4096, 400),
                "oversized response without matching limit must throw instead of causing OOM");
    }

    /** Guard against firstValueAsLong throwing on a malformed Content-Length header during 403
     * classification; called directly because the JDK client rejects bad headers earlier. */
    @Test
    void aJunkContentLength_doesNotEscapeTheThrottleClassifier() {
        assertFalse(HttpFetcher.rateLimited(resp403(Map.of("content-length", List.of("1,234")))));
        assertTrue(HttpFetcher.rateLimited(resp403(Map.of("x-ratelimit-remaining", List.of("0")))));
    }

    /** Same final-attempt backoff fix for per-file downloads: sleep 1s + 2s then throw, without a wasted
     * 4s sleep after the last try. */
    @Test
    void download_alsoThrowsWithoutSleepingOnTheLastBackoff() {
        HttpFetcher f = new HttpFetcher();
        long t0 = System.nanoTime();
        assertThrows(IOException.class,
                () -> f.download(base + "/broken/x", "tok", "application/octet-stream", tmp.resolve("y.jar")));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5_000, String.valueOf(ms));
    }

    /** Minimal response containing only the headers the classifier inspects. */
    private static java.net.http.HttpResponse<java.io.InputStream> resp403(Map<String, List<String>> headers) {
        java.net.http.HttpHeaders h = java.net.http.HttpHeaders.of(headers, (a, b) -> true);
        return new java.net.http.HttpResponse<>() {
            @Override
            public int statusCode() {
                return 403;
            }

            @Override
            public java.net.http.HttpRequest request() {
                return null;
            }

            @Override
            public java.util.Optional<java.net.http.HttpResponse<java.io.InputStream>> previousResponse() {
                return java.util.Optional.empty();
            }

            @Override
            public java.net.http.HttpHeaders headers() {
                return h;
            }

            @Override
            public java.io.InputStream body() {
                return java.io.InputStream.nullInputStream();
            }

            @Override
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                return java.util.Optional.empty();
            }

            @Override
            public java.net.URI uri() {
                return java.net.URI.create("http://127.0.0.1/");
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }
        };
    }

    /** A 404 for a missing release tag must throw ReleaseMissing immediately without retrying, while
     * 500s still ride the retry ladder. */
    @Test
    void a404OnTagResolution_isTypedAndAskedOnce_while5xxStillRidesTheLadder() {
        GithubReleases gh = new GithubReleases(base + "/gone", "o/r", "alive", "v2", tmp);
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        assertInstanceOf(GithubReleases.ReleaseMissing.class, e, "the doors name this state, so it needs a type");
        assertEquals("v2", ((GithubReleases.ReleaseMissing) e).tag);
        assertEquals("o/r", ((GithubReleases.ReleaseMissing) e).repo);
        // Check request count instead of elapsed time so slow CI or a cold HttpClient doesn't cause flaky failures.
        assertEquals(1, goneHits.get());

        HttpFetcher f = new HttpFetcher();
        assertThrows(IOException.class, () -> f.get(base + "/broken/x", "tok", "application/json"));
        assertEquals(3, brokenHits.get());
    }

    /** A 404 on an incremental backref release must not throw ReleaseMissing (which logs "nothing to
     * sync to" after the target release already resolved). */
    @Test
    void a404OnABackrefRelease_isNotTheTargetGoingMissing() {
        GithubReleases gh = new GithubReleases(base, "o/r", "tok", "v2", tmp);
        IOException e = assertThrows(IOException.class,
                () -> gh.fetch(entry("mods/old.jar", "old.jar", "vDEAD", null), tmp.resolve("old.jar")));
        assertInstanceOf(HttpFetcher.HttpStatusError.class, e, "still a status answer github gave us");
        assertFalse(e instanceof GithubReleases.ReleaseMissing);
        assertTrue(e.getMessage().contains("vDEAD"), String.valueOf(e.getMessage()));
    }

    /** Wrap bare java.io network errors from java.net.http as TransportFault so EngineHealth does not
     * blame a flaky connection on the engine and roll it back. */
    @Test
    void aHostThatAnswersNothing_arrivesTypedAsTheWorldsFault() {
        HttpFetcher f = new HttpFetcher();
        IOException e = assertThrows(IOException.class,
                () -> f.getBody(base + "/hangup/x", "tok", "application/json"));
        assertInstanceOf(HttpFetcher.TransportFault.class, e,
                "it came off the socket, so it is the world's fault by construction: " + e);
        assertTrue(EngineHealth.attributable(e));
    }

    /** Wrap mid-transfer read failures as TransportFault, while leaving local out.write untyped so a
     * full disk never reads as a network outage or clears probation. */
    @Test
    void aBodyThatDiesMidTransfer_isTypedAtTheSource() {
        java.io.InputStream cut = new java.io.InputStream() {
            int left = 8;

            @Override
            public int read() throws IOException {
                if (left-- > 0) {
                    return 'x';
                }
                throw new IOException("Connection reset by peer");
            }
        };
        IOException e = assertThrows(IOException.class, () -> HttpFetcher.readAllBounded(cut, 4096, 4000));
        assertInstanceOf(HttpFetcher.TransportFault.class, e, "a dead path, not our bookkeeping: " + e);
        assertTrue(EngineHealth.attributable(e));
        assertEquals("Connection reset by peer", e.getCause().getMessage());
    }

    @Test
    void http429_isRateLimited() {
        GithubReleases gh = new GithubReleases(base + "/toomany", "o/r", "alive", "v2", tmp);
        long t0 = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> gh.release("v2"));
        assertInstanceOf(HttpFetcher.RateLimited.class, e, "429 means exactly one thing on any host");
        assertTrue((System.nanoTime() - t0) < 900_000_000L);
    }

    private static FileEntry entry(String path, String asset, String release, String inner) {
        FileEntry.Source src = inner != null
                ? new FileEntry.Source(FileEntry.Source.Type.RELEASE_ZIP, asset, inner, release, null, null)
                : new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET, asset, null, release, null, null);
        return new FileEntry(path, "0".repeat(64), 1, FileEntry.Side.BOTH, FileEntry.Policy.MANAGED,
                FileEntry.Clazz.HOT, src, List.of(), false, null, FileEntry.OnRemove.DELETE, null);
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
