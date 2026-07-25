package com.retro.bamfos.updater.watchdog;

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
    private final String repo;
    private final String token;
    private String etag;
    private String lastTag = "";
    boolean tokenDead;

    ReleasePoll(String repo, String token) {
        this.repo = repo;
        this.token = token;
    }

    /** Returns the latest release tag, or empty on error or an uncached 304. */
    Optional<String> latestTag() {
        try {
            HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create(
                            "https://api.github.com/repos/" + repo + "/releases/latest"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (token != null && !token.isEmpty()) {
                rq.header("Authorization", "Bearer " + token);
            }
            if (etag != null) {
                rq.header("If-None-Match", etag);
            }
            HttpResponse<InputStream> resp = client.send(rq.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() == 304) {
                    return lastTag.isEmpty() ? Optional.empty() : Optional.of(lastTag);
                }
                if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    tokenDead = true; // loud persistent state, same rule as the engine
                    return Optional.empty();
                }
                if (resp.statusCode() != 200) {
                    return Optional.empty();
                }
                tokenDead = false;
                etag = resp.headers().firstValue("ETag").orElse(null);
                Object root = MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
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
