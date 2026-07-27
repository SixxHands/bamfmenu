package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.EngineEntry;
import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.util.HashUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end client door test: converges to what the server broadcasts using an in-test SLP server
 * over a live socket (no mocked ping path). */
class ClientDoorTest {

    @TempDir
    Path dir;

    private HttpServer http;
    private String base;
    private ServerSocket slp;
    private int slpPort;
    private volatile String statusJson;
    /** Asset 5 is the shim. Count requests to prove we skipped the fetch, since the bytes on disk look
     * the same either way. */
    private final java.util.concurrent.atomic.AtomicInteger shimFetches = new java.util.concurrent.atomic.AtomicInteger();
    /** Same idea for a missing tag: one 404 vs three retries saves ~3.4s per boot. */
    private final java.util.concurrent.atomic.AtomicInteger goneHits = new java.util.concurrent.atomic.AtomicInteger();
    /** Asset 6 is the engine. Count requests to make sure a rejected engine isn't re-downloaded into an
     * unused .new file. */
    private final java.util.concurrent.atomic.AtomicInteger engineFetches = new java.util.concurrent.atomic.AtomicInteger();

    private static final byte[] CM = Fx.jar("cm");
    private static final byte[] SRV = Fx.jar("srv");
    private static final byte[] BCC_NEW = "modpackVersion = \"vNEW\"\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SHIM_NEW = "brand new shim".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENGINE_NEW = "brand new engine".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void start() throws IOException {
        // Minimal SLP test server: reads handshake + status request, writes framed status JSON.
        slp = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        slpPort = slp.getLocalPort();
        statusJson = status("vNEW");
        Thread t = new Thread(() -> {
            while (!slp.isClosed()) {
                try (Socket s = slp.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    skipFrame(in); // handshake
                    skipFrame(in); // status request
                    byte[] json = statusJson.getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    payload.write(0); // packet id: status response
                    writeVarInt(payload, json.length);
                    payload.write(json);
                    ByteArrayOutputStream frame = new ByteArrayOutputStream();
                    writeVarInt(frame, payload.size());
                    payload.writeTo(frame);
                    s.getOutputStream().write(frame.toByteArray());
                    s.getOutputStream().flush();
                } catch (IOException closed) {
                    // slp.close() lands here, test is over
                }
            }
        });
        t.setDaemon(true);
        t.start();

        // Release server fixture using the same 302-to-S3 redirect flow as GitHub.
        byte[] manifest = manifest("vNEW",
                entry("config/bcc-common.toml", BCC_NEW, "both", "bcc.toml", null),
                entry("mods/cm.jar", CM, "client", "cm.jar", "cm"),
                entry("mods/srv.jar", SRV, "server", "srv.jar", "srv"),
                entry(SyncCore.SHIM_CLIENT_PATH, SHIM_NEW, "client", "shim.jar", null),
                entry(SyncCore.ENGINE_PATH, ENGINE_NEW, "both", "engine.jar", null));
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + http.getAddress().getPort();
        // Include a release notes body so the PACK UPDATE headline formatting path actually gets tested.
        http.createContext("/repos/o/r/releases/tags/vNEW", ex -> json(ex,
                "{\"tag_name\":\"vNEW\",\"body\":\"Cobblemon 1.6.1 and a nether fix\\n\\nthe rest of the notes\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1},{\"name\":\"bcc.toml\",\"id\":2},{\"name\":\"cm.jar\",\"id\":3},{\"name\":\"srv.jar\",\"id\":4},{\"name\":\"shim.jar\",\"id\":5},{\"name\":\"engine.jar\",\"id\":6}]}"));
        http.createContext("/repos/o/r/releases/assets/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/repos/o/r/releases/assets/".length());
            ex.getResponseHeaders().add("Location", base + "/s3/" + id);
            ex.sendResponseHeaders(302, -1);
        });
        http.createContext("/s3/", ex -> {
            String asset = ex.getRequestURI().getPath().substring(4);
            if (asset.equals("5")) {
                shimFetches.incrementAndGet();
            }
            if (asset.equals("6")) {
                engineFetches.incrementAndGet();
            }
            byte[] body = switch (asset) {
                case "1" -> manifest;
                case "2" -> BCC_NEW;
                case "3" -> CM;
                case "4" -> SRV;
                case "5" -> SHIM_NEW;
                case "6" -> ENGINE_NEW;
                default -> new byte[0];
            };
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        // Channel with no release for the broadcast tag; counted so we verify the 404 is not retried.
        http.createContext("/gone/repos/", ex -> {
            goneHits.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
        });
        // Valid release with no manifest.json attached (bad release metadata, not a network error).
        http.createContext("/nomanifest/repos/", ex -> json(ex,
                "{\"tag_name\":\"vNEW\",\"assets\":[{\"name\":\"cm.jar\",\"id\":3}]}"));
        // Simulated GitHub rate limit: 403 response with rate-limit headers.
        http.createContext("/limited/repos/o/r/releases/tags/vNEW", ex -> {
            ex.getResponseHeaders().add("x-ratelimit-remaining", "0");
            ex.sendResponseHeaders(403, -1);
        });
        http.start();

        props("server_host=127.0.0.1\nserver_port=" + slpPort + "\n");
    }

    @AfterEach
    void stop() throws IOException {
        http.stop(0);
        slp.close();
    }

    @Test
    void broadcastMatchesLocal_noop() throws IOException {
        bcc("vNEW");
        Fx.write(dir, ".bamfos-updater/manifest-current.json", "{}".getBytes(StandardCharsets.UTF_8)); // Steady state: write both bcc and the ledger at the same version so the fast path triggers
        // (bcc alone could have been written by a partial sync).
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.save(Ledger.pathIn(dir));
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods")));
        // Pin the quiet-state bus lines in order, including IDENT (values are checked in UpdaterFeedTest).
        assertEquals(List.of(
                "LOG\tcom.bamf.updater: kmod start",
                "LOG\tcom.bamf.updater: module initialized",
                "IDENT\to/r\t" + EngineEntry.version() + "\t", // the fixture's repo, straight off the config
                "LOG\tupdater: packages current (vNEW), nothing to do",
                "END\tskip"),
                Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log")));
    }

    /**
     * The double-launch boot, which is the ONE boot where IDENT's position is load-bearing: the door returns at
     * the lock-held line, so if IDENT were sent inside the lock the three hardware rows would sit on
     * `Detecting ...` for the entire session, which is the exact thing UpdaterFeed's rows exist not to do. A
     * live foreign PID in the lock file is the only way to make acquire() refuse (our own PID reclaims it), and
     * the parent-or-any-live-process pick is the same one ServerDoorTest uses.
     */
    @Test
    void secondInstance_identsBeforeTheLockLine() throws IOException {
        bcc("vNEW");
        Files.createDirectories(InstanceLock.pathIn(dir).getParent());
        long other = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive())
                .findFirst().orElseThrow().pid();
        Files.writeString(InstanceLock.pathIn(dir), Long.toString(other));

        assertEquals(0, new ClientDoor(base).run(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        int ident = indexOfPrefix(bus, "IDENT\t");
        int held = bus.indexOf("LOG\tupdater: instance lock held, sync skipped");
        assertTrue(held >= 0, String.valueOf(bus));
        assertTrue(ident >= 0 && ident < held, String.valueOf(bus));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    private static int indexOfPrefix(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** Do not take the packages-current shortcut when installed_version lags behind bcc-common.toml;
     * a deferred row writes no journal, so the ledger version is the only signal that a retry is needed. */
    @Test
    void aPartiallyAppliedSync_isRePlannedOnTheNextBoot() throws IOException {
        bcc("vOLD");
        byte[] theirBuild = "the player's own build of cm".getBytes(StandardCharsets.UTF_8);
        Fx.write(dir, "mods/cm.jar", theirBuild); // edited AND the pack moved it: this rule, so a .bak is owed
        Ledger led = new Ledger();
        led.installedVersion = "vOLD";
        led.files.put("mods/cm.jar", new Ledger.Entry("the-sha-we-placed-back-then"));
        led.save(Ledger.pathIn(dir));
        // Use a non-empty directory at the .bak path to simulate a file locked by antivirus.
        Files.createDirectories(dir.resolve("mods/cm.jar.bak-vOLD/wedge"));
        // Keep shim and engine up to date so pendingSelf doesn't mask the result below.
        Fx.write(dir, SyncCore.SHIM_CLIENT_PATH, SHIM_NEW);
        Fx.write(dir, SyncCore.ENGINE_PATH, ENGINE_NEW);

        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(BCC_NEW, Files.readAllBytes(dir.resolve("config/bcc-common.toml")));
        // sync() used to infer pending status from the journal alone, so an all-deferred run wrote
        // "updated vOLD -> vNEW" to last-sync.json even though installed_version stayed at vOLD.
        assertTrue(Files.readString(dir.resolve(".bamfos-updater").resolve("last-sync.json"))
                        .contains("some apply on next boot"));
        assertEquals("pending", lastSync().get("result"));
        assertArrayEquals(theirBuild, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertEquals("vOLD", Ledger.load(Ledger.pathIn(dir)).installedVersion);
        assertFalse(Files.exists(dir.resolve(".bamfos-updater/journal.json")));

        // Remove the blocker directory. The stale ledger should force this boot to re-plan.
        Files.delete(dir.resolve("mods/cm.jar.bak-vOLD/wedge"));
        Files.delete(dir.resolve("mods/cm.jar.bak-vOLD"));
        assertEquals(0, new ClientDoor(base).run(dir));

        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertArrayEquals(theirBuild, Files.readAllBytes(dir.resolve("mods/cm.jar.bak-vOLD")));
        assertEquals("vNEW", Ledger.load(Ledger.pathIn(dir)).installedVersion);
        assertTrue(Files.readString(dir.resolve(".bamfos-updater").resolve("bus.log"))
                        .contains("last sync left rows unapplied"));
        assertFalse(Files.readString(dir.resolve(".bamfos-updater").resolve("last-sync.json"))
                        .contains("some apply on next boot"));
        assertEquals("ok", lastSync().get("result"));
    }

    /** Log the failing path for both stuck and abandoned ops so unrecoverable targets can be diagnosed
     * instead of showing only a bare count. */
    @Test
    void stepZero_namesThePathsItCouldNotFinish() throws IOException {
        Fx.write(dir, ".bamfos-updater/staging/there.part", CM);
        Files.createDirectories(dir.resolve("mods/locked.jar/wedge")); // portable stand-in for an AV hold
        Files.write(dir.resolve("mods/locked.jar/wedge/f"), CM);
        Journal j = new Journal();
        j.targetVersion = "vNEW";
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/gone.part", "mods/vanished.jar",
                HashUtils.sha256(CM)));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/there.part", "mods/locked.jar",
                HashUtils.sha256(CM)));
        j.save(Journal.pathIn(dir));

        String out = captureOut(() -> SyncCore.stepZero(dir));

        assertTrue(out.contains("could not be re-verified"), String.valueOf(out));
        assertTrue(out.contains("mods/vanished.jar"), String.valueOf(out));
        assertTrue(out.contains("mods/locked.jar"), String.valueOf(out));
    }

    private interface Body {
        void run() throws IOException;
    }

    private static String captureOut(Body body) throws IOException {
        java.io.PrintStream real = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(real);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** Corrupt journal or ledger files must recover instead of throwing on every boot and wedging updates
     * until deleted by hand. */
    @Test
    void unreadableStateFiles_neverBrickTheDoor() throws IOException {
        bcc("vOLD");
        Path bam = dir.resolve(".bamfos-updater");
        Files.createDirectories(bam);
        Files.writeString(bam.resolve("journal.json"), "{\"target_version\":\"vNEW\",\"ops\":[{\"kind\":\"mov");
        Files.writeString(bam.resolve("ledger.json"), "{\"installed_version\":");

        assertEquals(0, new ClientDoor(base).run(dir));

        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertEquals("vNEW", Ledger.load(Ledger.pathIn(dir)).installedVersion);
        assertFalse(Files.exists(bam.resolve("journal.json")));
    }

    /** Verify staging gets pruned. Manifest JSONs and release zips are cached per tag, so old copies
     * would pile up across releases if we didn't clean them out. */
    @Test
    void staleReleaseCaches_areSwept_soStagingCannotGrowForever() throws IOException {
        bcc("vOLD");
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);
        Files.write(staging.resolve("zip-vOLD-data-common.zip"), new byte[4096]); // Stale zip from the previous release.
        Files.writeString(staging.resolve("manifest-vOLD.json"), "{}");

        assertEquals(0, new ClientDoor(base).run(dir));

        assertFalse(Files.exists(staging.resolve("zip-vOLD-data-common.zip")));
        assertFalse(Files.exists(staging.resolve("manifest-vOLD.json")));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
    }

    @Test
    void firstCurrentBoot_runsOnceToBackfillCaches() throws IOException {
        bcc("vNEW"); // current but never synced: the BIOS caches don't exist yet
        assertEquals(0, new ClientDoor(base).run(dir));
        assertTrue(Files.exists(dir.resolve(".bamfos-updater/manifest-current.json")));
        // Deleting a managed mod while on the current version must trigger a restore, not a no-op
        // (otherwise an antivirus deleting one jar leaves the client broken even though the version matches).
        Files.deleteIfExists(dir.resolve("mods/cm.jar"));
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
    }

    @Test
    void broadcastDiffers_syncsAndBccFollows() throws IOException {
        bcc("vOLD");
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve(SyncCore.SHIM_CLIENT_PATH), "old shim on disk");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertFalse(Files.exists(dir.resolve("mods/srv.jar")));
        assertArrayEquals(BCC_NEW, Files.readAllBytes(dir.resolve("config/bcc-common.toml")));
        assertArrayEquals(SHIM_NEW, Files.readAllBytes(dir.resolve(SyncCore.SHIM_CLIENT_PATH + ".new")));
        assertEquals("old shim on disk", Files.readString(dir.resolve(SyncCore.SHIM_CLIENT_PATH)));
        assertArrayEquals(ENGINE_NEW, Files.readAllBytes(dir.resolve(SyncCore.ENGINE_PATH + ".new")));
        // the success state, pinned on the WRITER side too. UpdaterFeed matches these four strings by literal
        // across a filesystem bus, so renaming one compiles fine and just renders a row contradicting the
        // other: a sync that fully worked would close the POST block on "Update stopped ....... FAILED" with
        // "updater: sync aborted, boot continues" under it, and nothing anywhere would be red
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertEquals("END\tok", bus.get(bus.size() - 1));
        // the headline the status line carries mid-sync: first line of the notes only, and a COMMA, because the
        // house rule bans a dash as punctuation and dash form was corrected to match
        assertTrue(bus.contains("NOTE\tPACK UPDATE vNEW, Cobblemon 1.6.1 and a nether fix"), bus.toString());

        // bcc now matches vNEW so the same broadcast is a no-op, unless a managed mod is missing
        // (which still gets restored).
        Files.delete(dir.resolve("mods/cm.jar"));
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
    }

    /** The client shim does not self-promote, so a second sync must skip re-downloading identical shim .new
     * bytes and must not set pendingSelf (which prints REBOOT TO APPLY). */
    @Test
    void aWaitingClientShim_isNotReFetched_andNeverPromisesAReboot() throws IOException {
        bcc("vOLD");
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve(SyncCore.SHIM_CLIENT_PATH), "old shim on disk");
        // the engine already current, so ONLY the shim is stale: an engine .new keeps pendingSelf true on its
        // own and it earns that, the shim promotes it before the next load
        Fx.write(dir, SyncCore.ENGINE_PATH, ENGINE_NEW);

        String first = captureOut(() -> assertEquals(0, new ClientDoor(base).run(dir)));

        assertArrayEquals(SHIM_NEW, Files.readAllBytes(dir.resolve(SyncCore.SHIM_CLIENT_PATH + ".new")));
        assertEquals(1, shimFetches.get());
        assertTrue(first.contains("nothing on the client swaps it in"), String.valueOf(first));
        assertFalse(Files.readString(dir.resolve(".bamfos-updater").resolve("last-sync.json"))
                        .contains("some apply on next boot"));

        // Missing managed mod forces a full sync even when the version matches.
        // Regression test: this case used to re-download the shim unnecessarily.
        Files.delete(dir.resolve("mods/cm.jar"));
        String second = captureOut(() -> assertEquals(0, new ClientDoor(base).run(dir)));

        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertEquals(1, shimFetches.get());
        assertArrayEquals(SHIM_NEW, Files.readAllBytes(dir.resolve(SyncCore.SHIM_CLIENT_PATH + ".new")));
        assertEquals("old shim on disk", Files.readString(dir.resolve(SyncCore.SHIM_CLIENT_PATH)));
        assertTrue(second.contains("nothing on the client swaps it in"), String.valueOf(second));
    }

    /** The anti-loop guard escalates to the blue screen after no-op cycles. A boot with no carrier never
     * ran a sync, so it shouldn't count toward that limit and break the next retry. */
    @Test
    void skippedSync_recordsNoCycle() throws IOException {
        props("server_host=127.0.0.1\nserver_port=1\n"); // closed port: no carrier, sync never runs
        bcc("vOLD");
        Path crumb = com.retro.bamfos.updater.engine.guard.Breadcrumb.pathIn(dir);
        Files.createDirectories(crumb.getParent());
        Files.writeString(crumb, "{\"server\":\"1.2.3.4\",\"reason\":\"nothing we can name\","
                + "\"fingerprint\":\"ffff0000\",\"at\":1}");

        assertEquals(0, new ClientDoor(base).run(dir));

        var hist = com.retro.bamfos.updater.engine.guard.DisconnectHistory.load(dir);
        assertNull(hist.entries.get("ffff0000"));
    }

    @Test
    void currentVersion_missingManagedMod_isRestored() throws IOException {
        bcc("vNEW");
        assertEquals(0, new ClientDoor(base).run(dir)); // backfills the caches
        Files.delete(dir.resolve("mods/cm.jar"));       // antivirus / user deletion, version unchanged
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
    }

    /** Pinned paths must be excluded from firstMissingManaged so deleting a pinned file does not break the
     * fast path and force a network sync on every boot. */
    @Test
    void firstMissingManaged_skipsPinnedPaths() throws IOException {
        Ledger led = new Ledger();
        led.files.put("mods/cm.jar", new Ledger.Entry("some-sha")); // placed by us, then deleted by them
        led.save(Ledger.pathIn(dir));

        assertTrue(SyncCore.firstMissingManaged(dir, List.of("mods/cm.jar")).isEmpty());
        assertEquals("mods/cm.jar", SyncCore.firstMissingManaged(dir, List.of()).orElseThrow());
    }

    @Test
    void pinnedMissingFile_isNotRestored() throws IOException {
        bcc("vNEW");
        assertEquals(0, new ClientDoor(base).run(dir)); // unpinned first, so cm.jar really gets installed
        assertTrue(Files.isRegularFile(dir.resolve("mods/cm.jar")));
        props("server_host=127.0.0.1\nserver_port=" + slpPort + "\npin=mods/cm.jar\n"); // now they pin it
        Files.delete(dir.resolve("mods/cm.jar"));                                       // and delete it
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods/cm.jar")));
    }

    /** Rate-limit 403s must not write the token-dead marker, which would falsely flag a working token as revoked. */
    @Test
    void rateLimitedBoot_neverWritesTheTokenDeadMarker() throws IOException {
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base + "/limited").run(dir));
        assertFalse(Files.exists(dir.resolve(".bamfos-updater").resolve("token-dead")));
        // Needs a separate END state: rate limits usually hit mid-download, and a FAILED end prints
        // "sync aborted" right below the rate-limited line, which contradicts it.
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertEquals("END\tlimited", bus.get(bus.size() - 1));
        assertEquals("limited", lastSync().get("result"));
    }

    /**
     * THE STATE THE OWNER'S PACK WAS IN when this landed: the server
     * broadcasts a version the repo has no release for, so every real client resolves a tag github 404s. All
     * three halves of that bug on one boot. The player used to read exactly one line, "updater: sync aborted,
     * boot continues", which is abortLine's deliberately vague catch-all, so he could not tell this from real
     * breakage and the only person who could fix it got no signal at all. The END state used to be the literal
     * "fail" for a box where the ping worked, the token worked and the engine worked. And the 404 rode the
     * transient-failure retry ladder, which is 3.4s of every boot spent on an answer no retry can change.
     */
    @Test
    void aBroadcastTagWithNoRelease_isNamed_getsItsOwnEndState_andIsAskedOnce() throws IOException {
        bcc("vOLD"); // the broadcast is vNEW, and this channel has no release for it
        onProbation(); // Engine was also just promoted, since both conditions can happen on the same boot.
        assertEquals(0, new ClientDoor(base + "/gone").run(dir));

        assertFalse(Files.exists(probation()));

        assertEquals(1, goneHits.get());
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.contains("LOG\tupdater: no release named vNEW on the channel, boot continues"), String.valueOf(bus));
        assertEquals("END\tno-release", bus.get(bus.size() - 1));
        assertEquals("no-release", lastSync().get("result"));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    /** Client offline and failure paths must overwrite last-sync.json; runs a success first to prove a
     * stale "ok" record cannot outlive later failures. */
    @Test
    void aFailedClientSync_stopsTheBiosRowFromReportingTheLastGoodSync() throws IOException {
        bcc("vOLD");
        // Shim and engine already match so the setup run finishes cleanly with ok instead of staging.
        Fx.write(dir, SyncCore.SHIM_CLIENT_PATH, SHIM_NEW);
        Fx.write(dir, SyncCore.ENGINE_PATH, ENGINE_NEW);
        assertEquals(0, new ClientDoor(base).run(dir));
        assertEquals("ok", lastSync().get("result"));

        bcc("vOLD"); // Bump the broadcast version so the next boot attempts a sync.
        assertEquals(0, new ClientDoor(base + "/nomanifest").run(dir));

        assertEquals("failed", lastSync().get("result"));
        String summary = (String) lastSync().get("summary");
        assertTrue(summary.contains("manifest.json"), String.valueOf(summary));
        assertFalse(summary.contains("updated vOLD -> vNEW"), String.valueOf(summary));
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertEquals("END\tfail", bus.get(bus.size() - 1));
    }

    /** A refused engine sha must not be re-downloaded or re-staged as engine.jar.new on later syncs, and
     * must not trigger REBOOT TO APPLY. */
    @Test
    void aRefusedEngine_isNotReDownloaded_andNeverPromisesAReboot() throws IOException {
        bcc("vOLD");
        Fx.write(dir, SyncCore.SHIM_CLIENT_PATH, SHIM_NEW); // Shim is already current, so only the engine could be pending.
        Fx.write(dir, SyncCore.ENGINE_PATH, "the engine that still works".getBytes(StandardCharsets.UTF_8));
        // Matches what EngineGuard writes after the second strike (the rejected sha and timestamp).
        Fx.write(dir, ".bamfos-updater/engine/engine-rejected",
                (HashUtils.sha256(ENGINE_NEW) + "\t1700000000000" + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8));

        String first = captureOut(() -> assertEquals(0, new ClientDoor(base).run(dir)));

        assertEquals(0, engineFetches.get());
        assertFalse(Files.exists(dir.resolve(SyncCore.ENGINE_PATH + ".new")));
        assertTrue(first.contains("already failed probation"), String.valueOf(first));
        assertFalse(Files.readString(dir.resolve(".bamfos-updater").resolve("last-sync.json"))
                        .contains("some apply on next boot"));
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertFalse(bus.contains("PENDING"));

        Files.delete(dir.resolve("mods/cm.jar")); // deleting a managed mod forces the full sync to run again
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertEquals(0, engineFetches.get());
    }

    /** Check that the file exists first so a missing log fails with a clear assertion instead of a
     *  NoSuchFileException. */
    private Map<?, ?> lastSync() throws IOException {
        Path f = dir.resolve(".bamfos-updater").resolve("last-sync.json");
        assertTrue(Files.isRegularFile(f));
        return (Map<?, ?>) MiniJson.parse(Files.readString(f, StandardCharsets.UTF_8));
    }

    /** Both offline (no-carrier) and packages-current boots must clear engine probation so the shim does
     * not roll back a working engine. */
    @Test
    void noCarrierAndPackagesCurrent_bothClearEngineProbation() throws IOException {
        onProbation();
        props("server_host=127.0.0.1\nserver_port=1\n"); // closed port: no broadcast to converge to
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(probation()));

        onProbation();
        props("server_host=127.0.0.1\nserver_port=" + slpPort + "\n");
        bcc("vNEW"); // Matches the broadcast version, with the caches and ledger from a completed sync.
        Fx.write(dir, ".bamfos-updater/manifest-current.json", "{}".getBytes(StandardCharsets.UTF_8));
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.save(Ledger.pathIn(dir));
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(probation()));
    }

    /** ClientDoor must pass clearProbation=true into SyncCore (unlike /bamfos sync, which runs a separate
     * jar-in-jar engine) so a normal sync clears probation. */
    @Test
    void aRealSyncClearsEngineProbation_cuzThisIsThePromotedEngineDoingIt() throws IOException {
        onProbation();
        bcc("vOLD"); // Behind the broadcast version, so this boot runs a full sync instead of short-circuiting.
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertFalse(Files.exists(probation()));
    }

    private Path probation() {
        return dir.resolve(".bamfos-updater").resolve("engine").resolve("engine-probation");
    }

    private void onProbation() throws IOException {
        Files.createDirectories(probation().getParent());
        Files.writeString(probation(), "a promoted sha the shim wrote");
    }

    @Test
    void noCarrier_bootContinues() throws IOException {
        props("server_host=127.0.0.1\nserver_port=1\n"); // closed port
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    @Test
    void vanillaServer_noBetterStatus_skips() throws IOException {
        statusJson = "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},\"description\":{\"text\":\"x\"}}";
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    @Test
    void misconfiguredServer_changeMe_skips() throws IOException {
        statusJson = status("CHANGE_ME");
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    @Test
    void noLocalVersion_skips() {
        // bcc-common.toml absent: no local version means no safe compare, boot as-is
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    @Test
    void tagPin_beatsBroadcast_noPingNeeded() throws IOException {
        props("server_host=127.0.0.1\nserver_port=1\ntag=vNEW\n"); // pin set, SLP port CLOSED
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
    }

    @Test
    void kickBreadcrumb_quarantinesCulprit_andWritesHistory() throws IOException {
        bcc("vNEW"); // Keep pack up to date so we verify Q2 still runs when no sync is needed.
        Files.createDirectories(dir.resolve("mods"));
        Files.write(dir.resolve("mods/chipped-x.jar"), Fx.jar("chippedx"));
        Files.writeString(dir.resolve(".bamfos-updater").resolve("last-disconnect.json"),
                "{\"server\":\"127.0.0.1\",\"reason\":\"Channel chippedx:main is missing on the server side\",\"fingerprint\":\"fp77\",\"at\":1}");

        assertEquals(0, new ClientDoor(base).run(dir));
        assertTrue(Files.exists(dir.resolve("mods/chipped-x.jar.disabled")));
        assertFalse(Files.exists(dir.resolve(".bamfos-updater/last-disconnect.json")));
        String hist = Files.readString(dir.resolve(".bamfos-updater").resolve("disconnect-history.json"));
        assertTrue(hist.contains("fp77"));
        assertTrue(hist.contains("quarantined mods/chipped-x.jar"));
    }

    @Test
    void serversDatFallback_carriesHostAndPort() throws IOException {
        props(""); // Without server_host, fall back to the first entry in servers.dat.
        Files.write(dir.resolve("servers.dat"), Fx.serversDat("127.0.0.1:" + slpPort));
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
    }

    // ---- fixture builders ----

    private void props(String extra) throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(p.getParent());
        Files.writeString(p, "repo=o/r\ngithub_token=tok\n" + extra);
    }

    private void bcc(String version) throws IOException {
        Path p = dir.resolve("config").resolve("bcc-common.toml");
        Files.createDirectories(p.getParent());
        Files.writeString(p, "modpackVersion = \"" + version + "\"\n");
    }

    private static String status(String version) {
        return "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},\"players\":{\"max\":20,\"online\":0},"
                + "\"description\":{\"text\":\"x\"},\"betterStatus\":{\"name\":\"BAMFOS\",\"version\":\"" + version + "\"}}";
    }

    private static Map<String, Object> entry(String path, byte[] bytes, String side, String asset, String modid) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("path", path);
        e.put("sha256", HashUtils.sha256(bytes));
        e.put("size", (long) bytes.length);
        e.put("side", side);
        e.put("policy", "managed");
        e.put("source", new LinkedHashMap<>(Map.of("type", "release-asset", "asset", asset)));
        if (modid != null) {
            e.put("modids", List.of(modid));
        }
        return e;
    }

    @SafeVarargs
    private static byte[] manifest(String version, Map<String, Object>... files) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", 1L);
        m.put("pack", new LinkedHashMap<>(Map.of("id", "bamfos", "version", version,
                "mc", "1.21.1", "neoforge", "21.1.235", "updater_abi", 1L)));
        m.put("requires_relaunch", false);
        m.put("removed_modids", List.of());
        m.put("files", List.of(files));
        return JsonWriter.write(m).getBytes(StandardCharsets.UTF_8);
    }

    private static void json(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    /** Abort messages give actionable text for known faults and stay neutral for unknown errors rather
     * than falsely blaming the network. */
    @Test
    void abortLine_namesOnlyTheCausesItCanProve() {
        assertEquals("integrity: two enabled jars claim one modid, sync aborted",
                ClientDoor.abortLine(new Reconciler.DupeModidAbort("cm.jar vs cm-2.jar")));
        assertEquals("updater: repository unreachable, boot continues",
                ClientDoor.abortLine(new java.net.UnknownHostException("api.github.com")));
        assertEquals("updater: repository unreachable, boot continues",
                ClientDoor.abortLine(new IOException("wrapped", new java.net.ConnectException("refused"))));
        assertEquals("updater: repository unreachable, boot continues",
                ClientDoor.abortLine(new HttpFetcher.TransportFault("the download stalled, no bytes for 30000ms")));
        assertEquals("updater: no release named v0.2.4 on the channel, boot continues",
                ClientDoor.abortLine(new GithubReleases.ReleaseMissing("SixxHands/bamfos-pack", "v0.2.4",
                        "https://api.github.com/repos/SixxHands/bamfos-pack/releases/tags/v0.2.4")));
        assertEquals("updater: the channel has no published release yet, boot continues",
                ClientDoor.abortLine(new GithubReleases.ReleaseMissing("SixxHands/bamfos-pack", "latest",
                        "https://api.github.com/repos/SixxHands/bamfos-pack/releases/latest")));
        assertEquals("updater: sync aborted, boot continues",
                ClientDoor.abortLine(new IOException("There is not enough space on the disk")));
        assertEquals("updater: sync aborted, boot continues",
                ClientDoor.abortLine(new IllegalStateException("something new")));
        // A bad repo= value (like one with a space) throws IllegalArgumentException wrapping URISyntaxException.
        // Don't misclassify that as "unreachable" when the config file itself is broken.
        assertEquals("updater: sync aborted, boot continues",
                ClientDoor.abortLine(new IllegalArgumentException("bad repo",
                        new java.net.URISyntaxException("SixxHands/bamfos pack", "Illegal character in path"))));
        assertEquals("updater: sync aborted, boot continues",
                ClientDoor.abortLine(new java.net.MalformedURLException("no protocol: bamfos-pack")));
    }

    private static void skipFrame(DataInputStream in) throws IOException {
        in.readFully(new byte[readVarInt(in)]);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed mid-varint");
            }
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
            shift += 7;
        }
    }
}
