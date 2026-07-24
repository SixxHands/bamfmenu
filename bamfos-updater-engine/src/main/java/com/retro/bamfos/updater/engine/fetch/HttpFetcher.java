package com.retro.bamfos.updater.engine.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * The download primitive with THE landmine defused.
 * The gap. Private release assets answer with a 302 to S3, and S3 rejects requests that
 * still carry the Authorization header with a confusing 400. java.net.http follows redirects
 * header-and-all. The guard. Redirects are NEVER automatic here: we hop manually and the
 * hop request carries NO Authorization. This single bug is the most common failure in every
 * homebrew GitHub updater; the fixture-server integration test exists to keep it dead.
 * 401/403 raise TokenDead, a DIFFERENT state from network failure -- expiry must be loud forever,
 * not identical to a wifi blip.
 */
public final class HttpFetcher {

    public static final class TokenDead extends IOException {
        public TokenDead(String msg) {
            super(msg);
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** GET with up to 5 manual auth-stripped hops; retries transient failures 3x with backoff. */
    public HttpResponse<InputStream> get(String url, String bearer, String accept) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return follow(url, bearer, accept);
            } catch (TokenDead dead) {
                throw dead; // never retried: the token will not get better
            } catch (IOException e) {
                last = e;
                Thread.sleep(1000L << attempt);
            }
        }
        throw last;
    }

    private HttpResponse<InputStream> follow(String url, String bearer, String accept) throws IOException, InterruptedException {
        String current = url;
        boolean auth = true;
        for (int hop = 0; hop < 5; hop++) {
            HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create(current))
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", accept)
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (auth && bearer != null && !bearer.isEmpty()) {
                rq.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<InputStream> resp = client.send(rq.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                resp.body().close();
                current = resp.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("redirect without Location"));
                auth = false; // Never forward Authorization when redirecting off the API host.
                continue;
            }
            if (code == 401 || code == 403) {
                resp.body().close();
                throw new TokenDead("github answered " + code + " - token expired or revoked");
            }
            if (code >= 400) {
                resp.body().close();
                throw new IOException("HTTP " + code + " from " + current);
            }
            return resp;
        }
        throw new IOException("too many redirects from " + url);
    }

    /** Streams a GET to dest via a .part sibling + atomic move; caller verifies the hash. */
    public void download(String url, String bearer, String accept, Path dest) throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".dl");
        HttpResponse<InputStream> resp = get(url, bearer, accept);
        try (InputStream in = resp.body()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    }
}
