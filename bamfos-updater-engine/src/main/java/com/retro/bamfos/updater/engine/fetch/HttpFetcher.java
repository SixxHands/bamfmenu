package com.retro.bamfos.updater.engine.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/* HTTP fetcher with manual redirects so we strip the Authorization header before following GitHub's
 * 302 to S3 (S3 rejects requests that still carry GitHub's auth header with 400). */
public final class HttpFetcher {

    public static final class TokenDead extends IOException {
        public final int code;

        public TokenDead(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /** Thrown on GitHub rate limiting. Kept separate from TokenDead so callers don't write the
     *  persistent token-dead marker for a temporary hourly PAT limit that resets automatically. */
    public static final class RateLimited extends IOException {
        public final int code;
        public final long retryAfterMs; // 0 when the response named no retry-after

        public RateLimited(int code, long retryAfterMs, String msg) {
            super(msg);
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** Non-2xx HTTP response, allowing download() to distinguish bad resume offsets (e.g., 416) from
     *  retryable wire drops. Non-final so ReleaseMissing extends it and EngineHealth treats it as external. */
    public static class HttpStatusError extends IOException {
        public final int code;

        public HttpStatusError(int code, String url) {
            super("HTTP " + code + " from " + url);
            this.code = code;
        }
    }

    /** Wraps all socket/transport failures (stalls, bad redirects, oversized bodies, and plain java.io
     *  errors from java.net.http like EOFException or connection resets that have no java.net cause).
     *  Lets EngineHealth and doors separate network drops from local disk or engine bugs. */
    public static final class TransportFault extends IOException {
        public TransportFault(String msg) {
            super(msg);
        }

        public TransportFault(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Per-read stall timeout (not a total download timeout), so large jars on slow connections can still
     *  finish as long as bytes keep arriving. */
    public static final long STALL_MS = 30_000;

    private final long stallMs;

    public HttpFetcher() {
        this(STALL_MS);
    }

    /** Test constructor so tests can override STALL_MS instead of waiting out the real timeout. */
    HttpFetcher(long stallMs) {
        this.stallMs = stallMs;
    }

    /** GET with up to 5 manual auth-stripped hops; retries transient failures 3x with backoff. */
    public HttpResponse<InputStream> get(String url, String bearer, String accept) throws IOException, InterruptedException {
        IOException last = null;
        boolean waited = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return follow(url, bearer, accept, 0);
            } catch (TokenDead dead) {
                throw dead; // never retried: the token will not get better
            } catch (RateLimited limited) {
                // Honor short retry-after delays (GitHub secondary limits), but bail on long hourly limits and
                // skip sleeping on the last attempt since this runs on the boot path.
                if (attempt == 2 || waited || limited.retryAfterMs <= 0 || limited.retryAfterMs > 2000) {
                    throw limited;
                }
                last = limited; // keep the exception typed for the rethrow below; only this branch retries
                waited = true;
                Thread.sleep(limited.retryAfterMs);
            } catch (IOException e) {
                if (e instanceof HttpStatusError status && status.code == 404) {
                    // Don't retry 404s, the file isn't there and waiting three seconds won't make it appear.
                    throw status;
                }
                last = e;
                if (attempt == 2) {
                    break; // don't waste 4s backing off right before rethrowing
                }
                Thread.sleep(1000L << attempt);
            }
        }
        throw last;
    }

    private static final int PEEK_MAX = 4096; // github's error json is a couple hundred bytes
    private static final long PEEK_DEADLINE_MS = 2000; // an error body github already framed lands at once

    /** Checks whether a 403 is a rate limit rather than a dead token (headers first, then a bounded body
     *  peek if a proxy stripped them). Runs the body read on a daemon thread with a wall-clock timeout
     *  since HttpRequest.timeout doesn't cover body reads; oversized or stalled bodies fall through to TokenDead. */
    public static boolean rateLimited(HttpResponse<InputStream> resp) {
        if (resp.headers().firstValue("retry-after").isPresent()
                || resp.headers().firstValue("x-ratelimit-remaining")
                        .map(v -> "0".equals(v.trim())).orElse(false)) {
            return true;
        }
        long len = contentLength(resp);
        if (len <= 0 || len > PEEK_MAX) {
            return false;
        }
        ExecutorService peeker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bamfos-updater-403-peek");
            t.setDaemon(true); // a wedged socket read ignores interrupt, so it must not hold the JVM open
            return t;
        });
        try {
            Future<String> body = peeker.submit(() ->
                    new String(resp.body().readNBytes((int) len), StandardCharsets.UTF_8));
            return body.get(PEEK_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .toLowerCase(Locale.ROOT).contains("rate limit");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); // Don't swallow the interrupt flag; callers may be waiting on it.
            return false;
        } catch (ExecutionException | TimeoutException unreadable) {
            return false; // No rate-limit signal here, so treat it as a bad or expired token.
        } finally {
            peeker.shutdownNow();
        }
    }

    /** Parses Content-Length safely, returning -1 if absent or malformed. Avoids firstValueAsLong's
     *  NumberFormatException escaping get() on junk headers from proxies or captive portals. */
    private static long contentLength(HttpResponse<?> resp) {
        try {
            return resp.headers().firstValueAsLong("content-length").orElse(-1L);
        } catch (NumberFormatException junk) {
            return -1L;
        }
    }

    /** Retry-After in ms, or 0 if missing or in HTTP-date format. */
    private static long retryAfterMs(HttpResponse<?> resp) {
        try {
            return resp.headers().firstValue("retry-after")
                    .map(v -> Long.parseLong(v.trim()) * 1000L).orElse(0L);
        } catch (NumberFormatException httpDate) {
            return 0L;
        }
    }

    private HttpResponse<InputStream> follow(String url, String bearer, String accept, long rangeStart) throws IOException, InterruptedException {
        String current = url;
        boolean auth = true;
        for (int hop = 0; hop < 5; hop++) {
            HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create(current))
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", accept)
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (rangeStart > 0) {
                // resume: the API host ignores this and 302s; S3 answers 206
                rq.header("Range", "bytes=" + rangeStart + "-");
            }
            if (auth && bearer != null && !bearer.isEmpty()) {
                rq.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(rq.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException wire) {
                // Only wrap the send call: URI.create throws on a malformed repo URL (handled by SyncCore.badUrlString),
                // and HTTP responses below already map to specific exception types.
                throw new TransportFault("the request died on the wire (" + wire + ")", wire);
            }
            int code = resp.statusCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                resp.body().close();
                current = resp.headers().firstValue("Location")
                        .orElseThrow(() -> new TransportFault("redirect without Location"));
                auth = false; // Never forward Authorization when redirecting off the API host.
                continue;
            }
            if ((code == 401 || code == 403) && auth) {
                // Only classify 401/403 as TokenDead on hop 0 (S3 redirect errors aren't a dead GitHub token).
                // Check 403 for rate-limit signals first so a throttle doesn't write the persistent token-dead marker.
                boolean limited = code == 403 && rateLimited(resp);
                long wait = retryAfterMs(resp);
                resp.body().close();
                if (limited) {
                    throw new RateLimited(code, wait, "github rate limit hit (403)");
                }
                throw new TokenDead(code, "github answered " + code + ", token expired or revoked");
            }
            if (code == 429) {
                // A 429 always means rate-limited, regardless of host.
                long wait = retryAfterMs(resp);
                resp.body().close();
                throw new RateLimited(code, wait, "github rate limit hit (429)");
            }
            if (code >= 400) {
                resp.body().close();
                throw new HttpStatusError(code, current);
            }
            return resp;
        }
        throw new TransportFault("too many redirects from " + url);
    }

    /** Streams a GET to dest via a .dl sibling and atomic move (caller verifies hash). Retries mid-body
     *  drops using HTTP Range (206 appends, 200 restarts from byte 0). */
    public void download(String url, String bearer, String accept, Path dest) throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".dl");
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                long have = Files.isRegularFile(part) ? Files.size(part) : 0;
                HttpResponse<InputStream> resp = follow(url, bearer, accept, have);
                boolean append = resp.statusCode() == 206 && have > 0;
                try (InputStream in = resp.body();
                     java.io.OutputStream out = Files.newOutputStream(part,
                             java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.WRITE,
                             append ? java.nio.file.StandardOpenOption.APPEND
                                    : java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                    copyBounded(in, out, stallMs, -1);
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (TokenDead | RateLimited stop) {
                throw stop; // neither gets better by asking again inside a second
            } catch (IOException e) {
                last = e;
                if (e instanceof HttpStatusError h && h.code >= 400 && h.code < 500
                        && Files.isRegularFile(part) && Files.size(part) > 0) {
                    // Server rejected our Range offset (e.g., 416 on an orphaned complete .dl); delete the
                    // partial so the next retry starts fresh.
                    Files.deleteIfExists(part);
                }
                if (attempt == 2) {
                    break; // Same as get(): don't waste 4s backing off right before throwing.
                }
                Thread.sleep(1000L << attempt);
            }
        }
        throw last;
    }

    /** Fetches a small response body (like release JSON) into memory with size and time limits. */
    public byte[] getBody(String url, String bearer, String accept) throws IOException, InterruptedException {
        try (InputStream in = get(url, bearer, accept).body()) {
            return readAllBounded(in, JSON_MAX, stallMs);
        }
    }

    /** Release JSON with ~270 assets is a few hundred KB; cap leaves plenty of headroom. */
    private static final int JSON_MAX = 8 << 20; // 8 MB

    public static byte[] readAllBounded(InputStream in, int maxBytes) throws IOException, InterruptedException {
        return readAllBounded(in, maxBytes, STALL_MS);
    }

    /** Bounded replacement for readAllBytes() with a stall deadline and maxBytes cap so wedged reads
     *  don't hang boot/watchdog polling and oversized responses can't OOM the process. */
    static byte[] readAllBounded(InputStream in, int maxBytes, long stallMs) throws IOException, InterruptedException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        copyBounded(in, buf, stallMs, maxBytes);
        return buf.toByteArray();
    }

    /**
     * Copies a response body with a STALL deadline, reset by every chunk that lands, because NOTHING above this
     * line has one: HttpRequest.timeout stops covering the exchange the moment the headers arrive, and a read
     * off an ofInputStream body then blocks forever on a path that broke without an RST (AP roam, router
     * reboot, laptop resume, a hotel portal holding the socket open). No TCP keepalive, no FML discovery
     * watchdog, no total budget on the door: on the client that is a game that never opens a window while the
     * terminal keeps spinning a Fetching line that looks alive, and on the server the wrapper never reaches
     * its launcher line, so an unattended box stays down until a human kills the JVM.
     * The copy rides a daemon thread we are willing to abandon and we CLOSE the body to break it, because a
     * wedged socket read ignores interrupt. maxBytes < 0 means no size cap (a payload's size is the
     * manifest's business and the caller's hash check is what judges the bytes).
     */
    private static void copyBounded(InputStream in, java.io.OutputStream out, long stallMs, long maxBytes)
            throws IOException, InterruptedException {
        AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        ExecutorService pump = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bamfos-updater-body");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> copy = pump.submit(() -> {
                byte[] chunk = new byte[64 * 1024];
                long total = 0;
                while (true) {
                    int n;
                    try {
                        n = in.read(chunk);
                    } catch (IOException wire) {
                        // Wrap socket read errors as TransportFault, leaving out.write untyped so local disk errors
                        // aren't misread as "github unreachable" and don't clear engine probation.
                        throw new TransportFault("the body died mid-transfer (" + wire + ")", wire);
                    }
                    if (n == -1) {
                        return null;
                    }
                    total += n;
                    if (maxBytes >= 0 && total > maxBytes) {
                        throw new TransportFault("response body is longer than the " + maxBytes + " byte cap");
                    }
                    out.write(chunk, 0, n);
                    lastProgress.set(System.nanoTime());
                }
            });
            while (true) {
                try {
                    copy.get(250, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException stillGoing) {
                    if (System.nanoTime() - lastProgress.get() > stallMs * 1_000_000L) {
                        in.close(); // Closing the stream is the only way to unblock the read; cancel(true) won't.
                        copy.cancel(true);
                        throw new TransportFault("the download stalled, no bytes for " + stallMs + "ms");
                    }
                } catch (ExecutionException failed) {
                    throw failed.getCause() instanceof IOException io ? io
                            : new IOException("download failed", failed.getCause());
                }
            }
        } finally {
            pump.shutdownNow();
        }
    }
}
