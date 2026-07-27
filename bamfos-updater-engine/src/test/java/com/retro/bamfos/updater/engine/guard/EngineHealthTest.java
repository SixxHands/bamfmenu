package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the engine side of boot-once verification: clearing probation and deciding
 *  whether a failed run still proves the engine loaded fine. */
class EngineHealthTest {

    @TempDir
    Path dir;

    private Path flag() {
        return dir.resolve(".bamfos-updater").resolve("engine").resolve("engine-probation");
    }

    @Test
    void proven_clearsTheFlag_andIsSilentWhenThereIsNone() throws IOException {
        Files.createDirectories(flag().getParent());
        Files.writeString(flag(), "a promoted sha");
        EngineHealth.proven(dir);
        assertFalse(Files.exists(flag()));
        EngineHealth.proven(dir); // normal boot with no probation flag should be a silent no-op
    }

    /** External faults clear probation, while engine-side faults (e.g. missing manifest.json on a valid
     * release response) leave probation armed for rollback. */
    @Test
    void attributable_blamesTheWorld_neverTheEnginesOwnBookkeeping() {
        assertTrue(EngineHealth.attributable(new IOException("connect", new ConnectException("refused"))));
        assertTrue(EngineHealth.attributable(new UnknownHostException("api.github.com")));
        assertTrue(EngineHealth.attributable(new javax.net.ssl.SSLHandshakeException("proxy")));
        assertTrue(EngineHealth.attributable(new HttpFetcher.RateLimited(403, 0, "throttled")));
        assertTrue(EngineHealth.attributable(new HttpFetcher.TokenDead(401, "revoked")));
        assertTrue(EngineHealth.attributable(new HttpFetcher.HttpStatusError(404, "the release is gone")));
        assertTrue(EngineHealth.attributable(new HttpFetcher.TransportFault("the download stalled")));

        assertFalse(EngineHealth.attributable(
                        new IOException("asset 'manifest.json' not on release latest (load-bearing release deleted?)")));
    }

    /** Cause chains can cycle, and this runs during boot, so make sure we don't loop forever. */
    @Test
    void attributable_survivesACyclicCauseChain() {
        IOException a = new IOException("a");
        IOException b = new IOException("b", a);
        a.initCause(b);
        assertFalse(EngineHealth.attributable(b));
    }
}
