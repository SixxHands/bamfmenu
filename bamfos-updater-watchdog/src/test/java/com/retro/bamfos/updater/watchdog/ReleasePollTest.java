package com.retro.bamfos.updater.watchdog;

import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ReleasePoll and WatchdogBrain together against a local loopback fixture via the fetcher's
 * BAMFOS_API_BASE seam, since BamfosWatchdog itself is a @Mod class outside plain JUnit.
 */
class ReleasePollTest {

    private HttpServer server;
    private String base;
    /* Records each poll request. Synchronized because the HttpServer thread appends while the test thread
       reads; without synchronization, the 401/429 size assertions could flake and read 0. */
    private final List<String> ifNoneMatch = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger status = new AtomicInteger(200);
    private String body = "{\"tag_name\":\"v0.2.6\"}";

    private static final long COOLDOWN_MS = 6L * 60 * 60 * 1000;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/o/r/releases/latest", ex -> {
            ifNoneMatch.add(ex.getRequestHeaders().getFirst("If-None-Match"));
            int code = status.get();
            // return 304 with an empty body when the ETag matches, just like GitHub
            if ("\"tag-v0.2.6\"".equals(ex.getRequestHeaders().getFirst("If-None-Match")) && code == 200) {
                ex.sendResponseHeaders(304, -1);
                ex.close();
                return;
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("ETag", "\"tag-v0.2.6\"");
            if (code == 200) {
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(b);
                }
            } else {
                ex.sendResponseHeaders(code, -1);
                ex.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aFixtureCanPlayGithub_andTheTagItServesEarnsTheRestart() {
        ReleasePoll poll = new ReleasePoll(base, "o/r", "tok");
        assertEquals("v0.2.6", poll.latestTag().orElse(""));
        assertFalse(poll.tokenDead);
        assertFalse(poll.rateLimited);
        // make sure the tag is passed through to the decision so announcements fire
        assertEquals(WatchdogBrain.Decision.RESTART,
                WatchdogBrain.decide(poll.latestTag().orElse(""), "v0.2.4", true, "", 0L, 0,
                        WatchdogBrain.MAX_RESTART_ATTEMPTS, System.currentTimeMillis(), COOLDOWN_MS));
    }

    @Test
    void theSecondPollIsConditional_and304KeepsTheTagItAlreadyKnows() {
        ReleasePoll poll = new ReleasePoll(base, "o/r", "tok");
        assertEquals("v0.2.6", poll.latestTag().orElse(""));
        assertEquals("v0.2.6", poll.latestTag().orElse(""));
        assertNull(ifNoneMatch.get(0));
        assertEquals("\"tag-v0.2.6\"", ifNoneMatch.get(1));
        assertFalse(poll.tokenDead);
    }

    @Test
    void anAuthenticated401IsTokenDeath_andItNamesTheCodeGithubAnswered() {
        status.set(401);
        ReleasePoll poll = new ReleasePoll(base, "o/r", "tok");
        assertTrue(poll.latestTag().isEmpty());
        // Confirm the local fixture handled the request, since real GitHub also returns 401 for a bogus token.
        assertEquals(1, ifNoneMatch.size());
        assertTrue(poll.tokenDead);
        assertEquals(401, poll.deadCode);
        assertEquals(WatchdogBrain.Decision.NOTHING,
                WatchdogBrain.decide("", "v0.2.4", true, "", 0L, 0,
                        WatchdogBrain.MAX_RESTART_ATTEMPTS, System.currentTimeMillis(), COOLDOWN_MS));
    }

    @Test
    void a429IsATransientThrottle_neverADeadToken() {
        status.set(429);
        ReleasePoll poll = new ReleasePoll(base, "o/r", "tok");
        assertTrue(poll.latestTag().isEmpty());
        assertEquals(1, ifNoneMatch.size());
        assertTrue(poll.rateLimited);
        assertFalse(poll.tokenDead);
    }

    /**
     * Verifies a 404 from /releases/latest sets noPublishedRelease (distinct from offline) and clears
     * once a release is published.
     */
    @Test
    void a404IsTheChannelHavingNoRelease_neverAnOfflineBoxOrADeadToken() {
        status.set(404);
        ReleasePoll poll = new ReleasePoll(base, "o/r", "tok");
        assertTrue(poll.latestTag().isEmpty());
        assertEquals(1, ifNoneMatch.size());
        assertTrue(poll.noRelease);
        assertFalse(poll.tokenDead);
        assertFalse(poll.rateLimited);

        status.set(200); // Publish a release on the fixture.
        assertEquals("v0.2.6", poll.latestTag().orElse(""));
        assertFalse(poll.noRelease);
    }

    /** Verifies the default constructor uses the env-aware resolver and falls back to the default API URL. */
    @Test
    void theShippedConstructorResolvesThroughTheSameSeamAsTheFetcher() {
        assertEquals(GithubReleases.realApi(), new ReleasePoll("o/r", "tok").apiBase);
    }
}
