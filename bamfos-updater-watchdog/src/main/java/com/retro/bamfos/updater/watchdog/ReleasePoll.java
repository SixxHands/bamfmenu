package com.retro.bamfos.updater.watchdog;

import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.json.MiniJson;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

// Conditional GET /releases/latest with If-None-Match (304 when unchanged). Uses the same endpoint as the server door so they never disagree on "latest".
final class ReleasePoll {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    final String apiBase;
    private final String repo;
    private final String token;
    private String etag;
    private String lastTag = "";
    boolean tokenDead;
    int deadCode; // actual HTTP status from GitHub so the warning logs the code we received
    boolean rateLimited;
    /** True when /releases/latest returned 404 (no published release), kept separate from empty Optional
     *  so operators don't mistake an unpublished channel for being offline. */
    boolean noRelease;

    ReleasePoll(String repo, String token) {
        this(GithubReleases.realApi(), repo, token);
    }

    /** Reuses the fetcher's BAMFOS_API_BASE resolver (loopback-only so PATs cannot be redirected) so local
     *  test fixtures work and the door and watchdog never disagree on the API base. */
    ReleasePoll(String apiBase, String repo, String token) {
        this.apiBase = apiBase;
        this.repo = repo;
        this.token = token;
    }

    /** Only marks a token dead when the request actually sent one, so the anonymous fallback poll never
     *  reports a bogus token failure. */
    static boolean tokenDeadFrom(int code, boolean auth, boolean limitProven) {
        return auth && (code == 401 || (code == 403 && !limitProven));
    }

    /** Returns the latest release tag, or empty on error or an uncached 304. */
    Optional<String> latestTag() {
        try {
            HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create(
                            apiBase + "/repos/" + repo + "/releases/latest"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            boolean auth = token != null && !token.isEmpty();
            if (auth) {
                rq.header("Authorization", "Bearer " + token);
            }
            if (etag != null) {
                rq.header("If-None-Match", etag);
            }
            // Reset per-cycle flags up front so a network exception below can't leave stale 403/404 state behind.
            // Keep tokenDead until a 200 or authenticated 304 proves the credential works again.
            noRelease = false;
            rateLimited = false;
            HttpResponse<InputStream> resp = client.send(rq.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() == 304) {
                    // An authenticated 304 proves the token still works, so clear the error flag here too.
                    // Otherwise a temporary failure would keep warning until the next 200.
                    tokenDead = false;
                    rateLimited = false;
                    return lastTag.isEmpty() ? Optional.empty() : Optional.of(lastTag);
                }
                if (resp.statusCode() == 429) {
                    rateLimited = true;
                    tokenDead = false;
                    return Optional.empty();
                }
                if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    // GitHub uses 403 for rate limits too, so handle 401/403 together and check throttling before flagging tokenDead.
                    rateLimited = resp.statusCode() == 403 && HttpFetcher.rateLimited(resp);
                    tokenDead = tokenDeadFrom(resp.statusCode(), auth, rateLimited);
                    deadCode = resp.statusCode();
                    return Optional.empty();
                }
                if (resp.statusCode() == 404) {
                    // /releases/latest 404s when only drafts/prereleases exist; flag it so operators don't see a network error.
                    noRelease = true;
                    rateLimited = false;
                    return Optional.empty();
                }
                if (resp.statusCode() != 200) {
                    rateLimited = false; // clear the flag so a later 500 doesn't leave stale error state
                    return Optional.empty();
                }
                tokenDead = false;
                rateLimited = false;
                etag = resp.headers().firstValue("ETag").orElse(null);
                // Bounded read: HttpClient's timeout stops at headers, so a stalled body would park the poller forever.
                Object root = MiniJson.parse(new String(
                        HttpFetcher.readAllBounded(in, 8 << 20), StandardCharsets.UTF_8));
                if (root instanceof Map<?, ?> m && m.get("tag_name") instanceof String tag && !tag.isEmpty()) {
                    lastTag = tag;
                    return Optional.of(tag);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // offline poll = no signal; next interval retries
        }
        return Optional.empty();
    }
}
