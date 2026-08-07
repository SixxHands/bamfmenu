package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.bus.BootBus;
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
                "IDENT\to/r", // IDENT only reports the repo name from config.
                "LOG\tupdater: packages current (vNEW), nothing to do",
                // When the boot doesn't need GitHub, the status row should show IDLE (instead of OK on IDENT).
                "CHANNEL\tidle",
                "END\tskip"),
                Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log")));
    }

    /** Emit IDENT before taking the OS lock so a second instance doesn't leave the row stuck on
     * "Detecting ...". */
    @Test
    void secondInstance_identsBeforeTheLockLine() throws IOException {
        bcc("vNEW");
        try (InstanceLock held = InstanceLock.acquire(dir).orElseThrow()) {
            assertEquals(0, new ClientDoor(base).run(dir));
        }

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        int ident = indexOfPrefix(bus, "IDENT\t");
        int held = bus.indexOf("LOG\tupdater: instance lock held, sync skipped");
        assertTrue(held >= 0, String.valueOf(bus));
        assertTrue(ident >= 0 && ident < held, String.valueOf(bus));
        assertEquals("idle", channel());
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    /** A losing second instance must append to bus.log rather than truncate it, or the winning instance's
     * live tail reader loses its offset and never sees END. */
    @Test
    void secondInstance_neverTruncatesTheBusUnderALiveReader() throws IOException {
        bcc("vNEW");
        Path busFile = dir.resolve(".bamfos-updater").resolve("bus.log");
        Files.createDirectories(busFile.getParent());
        Files.writeString(busFile, "SYNC\to/r\tvOLD\tvNEW\n"); // instance A, mid in-session op, feed live
        try (InstanceLock held = InstanceLock.acquire(dir).orElseThrow()) {
            assertEquals(0, new ClientDoor(base).run(dir));
        }

        List<String> lines = Files.readAllLines(busFile);
        assertEquals("SYNC\to/r\tvOLD\tvNEW", lines.get(0), String.valueOf(lines));
        assertTrue(lines.contains("LOG\tupdater: instance lock held, sync skipped"), String.valueOf(lines));
    }

    /** Status line must be emitted separately from IDENT. */
    private String channel() throws IOException {
        for (String line : Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"))) {
            if (line.startsWith("CHANNEL\t")) {
                return line.substring("CHANNEL\t".length());
            }
        }
        return fail("every boot exit must record a channel status, but none was written");
    }

    /** Verify Update Channel verdicts land only after real status checks, not as a fake OK on IDENT.
     * PINNED and rate-limit verdicts are covered in their own fixture tests below. */
    @Test
    void theChannelVerdict_saysWhatGithubDid_neverThatTheDoorRan() throws IOException {
        // Already up to date: local version matches broadcast, so we skip GitHub.
        bcc("vNEW");
        Fx.write(dir, ".bamfos-updater/manifest-current.json", "{}".getBytes(StandardCharsets.UTF_8));
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.save(Ledger.pathIn(dir));
        assertEquals(0, new ClientDoor(base).run(dir));
        assertEquals("idle", channel());

        // Behind the broadcast version, so a full sync runs against GitHub.
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertEquals("ok", channel());

        // Missing release tag: GitHub returned 404, which shouldn't be reported as OK.
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base + "/gone").run(dir));
        assertEquals("fail", channel());

        // No server reachable: row uses the same no-carrier wording as the log line.
        props("server_host=127.0.0.1\nserver_port=1\n"); // closed port
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base).run(dir));
        assertEquals("no-carrier", channel());
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
                        .contains("file(s) held back"));
        assertFalse(Files.readString(dir.resolve(".bamfos-updater").resolve("last-sync.json"))
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

    /** A deferred row with no journal shouldn't emit PENDING (REBOOT TO APPLY), even though last-sync
     * records pending. The control case checks that an actual staged sync still emits PENDING. */
    @Test
    void heldRow_neverPromisesReboot() throws IOException {
        bcc("vOLD");
        byte[] theirBuild = "the player's own build of cm".getBytes(StandardCharsets.UTF_8);
        Fx.write(dir, "mods/cm.jar", theirBuild); // edited AND the pack moved it: this rule, so a .bak is owed
        Ledger led = new Ledger();
        led.installedVersion = "vOLD";
        led.files.put("mods/cm.jar", new Ledger.Entry("the-sha-we-placed-back-then"));
        led.save(Ledger.pathIn(dir));
        Files.createDirectories(dir.resolve("mods/cm.jar.bak-vOLD/wedge")); // Simulate an AV file lock, with shim and engine already current so pendingSelf can't trigger PENDING.
        Fx.write(dir, SyncCore.SHIM_CLIENT_PATH, SHIM_NEW);
        Fx.write(dir, SyncCore.ENGINE_PATH, ENGINE_NEW);

        assertEquals(0, new ClientDoor(base).run(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.stream().anyMatch(l -> l.startsWith("SYNCED\t")), String.valueOf(bus));
        assertFalse(bus.contains("PENDING"), String.valueOf(bus));
        assertEquals("pending", lastSync().get("result"));
        assertEquals(Boolean.TRUE, lastSync().get("held"));

        // Same locked file, except this boot also stages an update that actually needs a reboot.
        Fx.write(dir, SyncCore.ENGINE_PATH, "an engine older than the release".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, new ClientDoor(base).run(dir));

        assertTrue(Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log")).contains("PENDING"));
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

    /** Step 0 must emit literal progress lines on the bus (matched by UpdaterFeed in the other jar) so
     * large factory-reset replays do not look hung. */
    @Test
    void stepZero_drawsItsThreePhasesOnTheBus() throws IOException {
        Fx.write(dir, ".bamfos-updater/staging/there.part", CM);
        Fx.write(dir, "mods/old.jar", "the version being replaced".getBytes(StandardCharsets.UTF_8));
        Journal j = new Journal();
        j.targetVersion = "vNEW";
        j.ops.add(new Journal.Op("delete", "", "mods/old.jar", ""));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/there.part", "mods/new.jar",
                HashUtils.sha256(CM)));
        j.save(Journal.pathIn(dir));

        assertFalse(SyncCore.stepZero(dir, BootBus.at(dir)));

        String bus = Files.readString(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.contains("APPLY\tvNEW\t1\t1"), String.valueOf(bus));
        assertTrue(bus.contains("ASTEP\tremove\tmods/old.jar\t1\t1"), bus);
        assertTrue(bus.contains("ASTEP\tinstall\tmods/new.jar\t1\t1"), bus);
        assertTrue(bus.contains("ASTEP\tverify\t"), String.valueOf(bus));
        assertTrue(bus.contains("ADONE\tok"), String.valueOf(bus));
    }

    /** Step 0 must verify written bytes after replay; two moves onto one destination both succeed, so
     * read-back is the only way to catch the wrong bytes offline. */
    @Test
    void stepZero_saysPartialAndForcesARederiveWhenAFileDoesNotReadBack() throws IOException {
        byte[] other = "different bytes entirely".getBytes(StandardCharsets.UTF_8);
        Fx.write(dir, ".bamfos-updater/staging/a.part", CM);
        Fx.write(dir, ".bamfos-updater/staging/b.part", other);
        Journal j = new Journal();
        j.targetVersion = "vNEW";
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/a.part", "mods/x.jar", HashUtils.sha256(CM)));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/b.part", "mods/x.jar", HashUtils.sha256(other)));
        j.save(Journal.pathIn(dir));

        String out = captureOut(() -> assertTrue(SyncCore.stepZero(dir, BootBus.at(dir))));

        assertTrue(out.contains("are not what the journal placed"), out);
        assertTrue(out.contains("mods/x.jar"), String.valueOf(out));
        assertTrue(Files.readString(dir.resolve(".bamfos-updater").resolve("bus.log")).contains("ADONE\tpartial"));
    }

    /** Verify step 0 persists the incremented attempt count across three boots against a wedged target
     * so stuck ops give up instead of looping forever. */
    @Test
    void aStuckOpCountsItsBoots_andIsGivenUpOnAtTheThird() throws IOException {
        Fx.write(dir, ".bamfos-updater/staging/held.part", CM);
        Files.createDirectories(dir.resolve("mods/locked.jar/wedge")); // portable stand-in for an AV hold
        Files.write(dir.resolve("mods/locked.jar/wedge/f"), CM);
        Journal j = new Journal();
        j.targetVersion = "vNEW";
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/held.part", "mods/locked.jar",
                HashUtils.sha256(CM)));
        j.save(Journal.pathIn(dir));

        SyncCore.stepZero(dir);
        assertEquals(1, Journal.load(Journal.pathIn(dir)).ops.get(0).attempts());
        SyncCore.stepZero(dir);
        assertEquals(2, Journal.load(Journal.pathIn(dir)).ops.get(0).attempts());

        String out = captureOut(() -> SyncCore.stepZero(dir));

        assertTrue(out.contains("gave up on"), String.valueOf(out));
        assertTrue(out.contains("mods/locked.jar"), String.valueOf(out));
        assertFalse(Files.exists(Journal.pathIn(dir)));
    }

    /** Sweep stranded empty (ops:[]) journal.json files during step 0 so they stop reading as pending. */
    @Test
    void aStrandedEmptyJournal_isSweptAtStepZero() throws IOException {
        Files.createDirectories(Journal.pathIn(dir).getParent());
        Files.writeString(Journal.pathIn(dir), "{\"target_version\":\"vNEW\",\"ops\":[]}");

        assertFalse(SyncCore.stepZero(dir));

        assertFalse(Files.exists(Journal.pathIn(dir)));
    }

    /** Apply pins to kept journal ops through the door so REPLACE_EXISTING replay cannot overwrite a file
     * the user just pinned. */
    @Test
    void aJournaledOpOntoAPathPinnedSince_isDropped_neverReplayed() throws IOException {
        byte[] theirs = "the player's own build".getBytes(StandardCharsets.UTF_8);
        props("server_host=127.0.0.1\nserver_port=" + slpPort + "\npin=mods/held.jar\n");
        bcc("vNEW");
        Fx.write(dir, ".bamfos-updater/manifest-current.json", "{}".getBytes(StandardCharsets.UTF_8));
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.save(Ledger.pathIn(dir));
        Fx.write(dir, "mods/held.jar", theirs);
        Fx.write(dir, ".bamfos-updater/staging/p1", CM);
        Journal j = new Journal();
        j.targetVersion = "vNEW";
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/p1", "mods/held.jar", HashUtils.sha256(CM)));
        j.save(Journal.pathIn(dir));

        assertEquals(0, new ClientDoor(base).run(dir));

        assertArrayEquals(theirs, Files.readAllBytes(dir.resolve("mods/held.jar")));
        assertFalse(Files.exists(Journal.pathIn(dir)));
    }

    /** An unproven step-0 read-back must veto the packages-current shortcut even when versions look converged. */
    @Test
    void aJournalThatDoesNotReadBack_vetoesThePackagesCurrentShortcut() throws IOException {
        bcc("vNEW");
        Fx.write(dir, ".bamfos-updater/manifest-current.json", "{}".getBytes(StandardCharsets.UTF_8));
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.save(Ledger.pathIn(dir));
        byte[] other = "different bytes entirely".getBytes(StandardCharsets.UTF_8);
        Fx.write(dir, ".bamfos-updater/staging/a.part", CM);
        Fx.write(dir, ".bamfos-updater/staging/b.part", other);
        Journal j = new Journal();
        j.targetVersion = "vNEW";
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/a.part", "config/lying.toml",
                HashUtils.sha256(CM)));
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/b.part", "config/lying.toml",
                HashUtils.sha256(other)));
        j.save(Journal.pathIn(dir));

        assertEquals(0, new ClientDoor(base).run(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.contains("LOG\tintegrity: applied files do not match, re-deriving"), String.valueOf(bus));
        assertTrue(bus.stream().noneMatch(l -> l.contains("packages current")), String.valueOf(bus));
        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
    }

    /** Do not emit SYNC (which opens the Updating BAMFOS block and locks DEL) when a backfill plan is empty
     * and no version changes. */
    @Test
    void aSyncThatMovesNothing_neverRaisesThePostBlock() throws IOException {
        bcc("vNEW");
        Fx.write(dir, "mods/cm.jar", CM);
        Fx.write(dir, SyncCore.SHIM_CLIENT_PATH, SHIM_NEW);
        Fx.write(dir, SyncCore.ENGINE_PATH, ENGINE_NEW);
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.files.put("mods/cm.jar", new Ledger.Entry(HashUtils.sha256(CM)));
        led.files.put("config/bcc-common.toml", new Ledger.Entry(HashUtils.sha256(BCC_NEW)));
        led.save(Ledger.pathIn(dir));
        // No cached manifest, so the backfill path runs a full sync even though files are already up to date.

        assertEquals(0, new ClientDoor(base).run(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.stream().noneMatch(l -> l.startsWith("SYNC\t")), String.valueOf(bus));
        assertEquals("END\tok", bus.get(bus.size() - 1));
        assertTrue(Files.exists(dir.resolve(".bamfos-updater/manifest-current.json")));
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
        // Pin the exact success bus literals that UpdaterFeed matches across jars.
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
        assertEquals("ok", channel());
    }

    /** A 404 for the broadcast tag must log a clear missing-release message, avoid the generic fail end
     * state, and skip the transient retry ladder. */
    @Test
    void aBroadcastTagWithNoRelease_isNamed_getsItsOwnEndState_andIsAskedOnce() throws IOException {
        bcc("vOLD"); // the broadcast is vNEW, and this channel has no release for it
        onProbation(); // Engine was also just promoted, since both conditions can happen on the same boot.
        assertEquals(0, new ClientDoor(base + "/gone").run(dir));

        assertFalse(Files.exists(probation()));

        assertEquals(1, goneHits.get());
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.contains("LOG\tupdater: no release named vNEW on the channel, continuing boot"), String.valueOf(bus));
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
        // Even though the sync succeeded, show the pinned state on the status row since there is no separate
        // Release Lock row anymore.
        assertEquals("pinned", channel());
    }

    /** A pinned install already on its target version must take the quiet fast path without calling GitHub
     * (enforced here with a closed port). */
    @Test
    void aPinnedBoxAlreadyOnItsPin_staysQuiet_andAsksGithubNothing() throws IOException {
        props("server_host=127.0.0.1\nserver_port=1\ntag=vNEW\n"); // pin set, SLP closed: pins need no carrier
        bcc("vNEW");
        Fx.write(dir, ".bamfos-updater/manifest-current.json", "{}".getBytes(StandardCharsets.UTF_8));
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.save(Ledger.pathIn(dir));

        assertEquals(0, new ClientDoor("http://127.0.0.1:1").run(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.contains("LOG\tupdater: release lock active, pinned to vNEW"), String.valueOf(bus));
        assertTrue(bus.contains("LOG\tupdater: packages current (vNEW), nothing to do"), String.valueOf(bus));
        assertTrue(bus.stream().noneMatch(l -> l.startsWith("SYNC\t")), String.valueOf(bus));
        assertEquals("pinned", channel());
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

    /** Record a no-change kick history entry even on converged boots so KickWatch escalates repeated
     * unfixable kicks instead of looping restart prompts forever. */
    @Test
    void convergedBox_kickThatQuarantinesNothing_stillRecordsTheCycle() throws IOException {
        bcc("vNEW"); // Version, caches, and ledger all match so we hit the packages-current early return.
        Fx.write(dir, ".bamfos-updater/manifest-current.json", "{}".getBytes(StandardCharsets.UTF_8));
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.save(Ledger.pathIn(dir));
        // the kick names a mod that is not on this disk at all, so there is nothing for Q2 to disable
        Files.writeString(dir.resolve(".bamfos-updater").resolve("last-disconnect.json"),
                "{\"server\":\"127.0.0.1\",\"reason\":\"Channel gravestone:main is missing on the client side\","
                        + "\"fingerprint\":\"fpSKEW\",\"at\":1}");

        assertEquals(0, new ClientDoor(base).run(dir));

        Path hist = dir.resolve(".bamfos-updater").resolve("disconnect-history.json");
        assertTrue(Files.isRegularFile(hist));
        Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) MiniJson.parse(
                Files.readString(hist, StandardCharsets.UTF_8))).get("entries")).get("fpSKEW");
        assertNotNull(entry);
        assertEquals("none", entry.get("last_action"));
        assertNull(entry.get("version_changed"));
    }

    /** Do not record a no-change kick cycle when offline (NO CARRIER); the crumb is consumed, but sync
     * never ran so a retry might still fix it. */
    @Test
    void aBootWhoseSyncNeverGotItsShot_recordsNoCycle() throws IOException {
        props("server_host=127.0.0.1\nserver_port=1\n"); // closed port: no broadcast, no compare, no sync
        bcc("vNEW");
        Files.writeString(dir.resolve(".bamfos-updater").resolve("last-disconnect.json"),
                "{\"server\":\"127.0.0.1\",\"reason\":\"Channel gravestone:main is missing\","
                        + "\"fingerprint\":\"fpDARK\",\"at\":1}");

        assertEquals(0, new ClientDoor(base).run(dir));

        assertEquals("no-carrier", channel());
        assertFalse(Files.exists(dir.resolve(".bamfos-updater").resolve("last-disconnect.json")));
        assertFalse(Files.exists(dir.resolve(".bamfos-updater").resolve("disconnect-history.json")));
    }

    /** Update the channel status on unexpected sync failures (like a missing manifest.json) instead of
     * leaving it stuck on IDLE above a FAILED POST block. */
    @Test
    void aSyncThatBrokeAfterGithubAnswered_neverLeavesTheRowIdle() throws IOException {
        bcc("vOLD");
        assertEquals(0, new ClientDoor(base + "/nomanifest").run(dir));
        assertEquals("fail", channel());
    }

    /** Mid-sync network errors (tested against a closed port) must emit the offline end state instead of
     * generic fail, so the early window does not stack a vague abort line below it. */
    @Test
    void aDeadCarrierMidSync_endsOffline_notFail() throws IOException {
        bcc("vOLD");
        assertEquals(0, new ClientDoor("http://127.0.0.1:1").run(dir));
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertEquals("END\toffline", bus.get(bus.size() - 1), String.valueOf(bus));
        assertTrue(bus.contains("LOG\tupdater: repository unreachable, continuing boot"), String.valueOf(bus));
        assertEquals("no-carrier", channel());
        assertEquals("offline", lastSync().get("result"));
    }

    /** Same-version repair (e.g. restoring a deleted jar) must not print "BAMFOS UPDATED FROM vNEW TO vNEW"
     * or write a version-change finale into last-sync.json. */
    @Test
    void aRepairSyncOnAConvergedBox_neverClaimsAVersionMove() throws IOException {
        bcc("vOLD");
        Fx.write(dir, SyncCore.SHIM_CLIENT_PATH, SHIM_NEW); // both self-entries pre-satisfied, so run 1 is a clean ok
        Fx.write(dir, SyncCore.ENGINE_PATH, ENGINE_NEW);
        assertEquals(0, new ClientDoor(base).run(dir));
        assertEquals("ok", lastSync().get("result"));

        Files.delete(dir.resolve("mods/cm.jar")); // the antivirus
        String out = captureOut(() -> assertEquals(0, new ClientDoor(base).run(dir)));

        assertArrayEquals(CM, Files.readAllBytes(dir.resolve("mods/cm.jar")));
        assertEquals("repaired 1 file(s) on vNEW", lastSync().get("summary"));
        assertFalse(out.contains("vNEW TO vNEW"), String.valueOf(out));
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.contains("SYNCED\tvNEW\tvNEW\t1"), String.valueOf(bus));
        assertTrue(bus.contains("PLAN\t1"), String.valueOf(bus));
    }

    /** When only the updater engine changes on an already-current pack (empty backfill plan), emit the
     * engine reboot notice without a pack-updated finale. */
    @Test
    void aSyncThatMovedOnlyTheUpdatersOwnJars_announcesNoPackChange() throws IOException {
        bcc("vNEW");
        Fx.write(dir, "mods/cm.jar", CM); // every managed file already right, so the plan comes out empty
        Fx.write(dir, SyncCore.SHIM_CLIENT_PATH, SHIM_NEW);
        Ledger led = new Ledger();
        led.installedVersion = "vNEW";
        led.files.put("mods/cm.jar", new Ledger.Entry(HashUtils.sha256(CM)));
        led.save(Ledger.pathIn(dir));

        assertEquals(0, new ClientDoor(base).run(dir));

        assertTrue(Files.exists(dir.resolve(SyncCore.ENGINE_PATH + ".new")));
        assertEquals("staged the updater's own files on vNEW, some apply on next boot", lastSync().get("summary"));
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.contains("PENDING"), String.valueOf(bus));
        assertTrue(bus.stream().noneMatch(l -> l.startsWith("SYNCED\t")), String.valueOf(bus));
    }

    /** BootBus is shared with the dedicated server, so BIOS-specific CAPS lines must stay off stdout
     * while the neutral summary still prints to latest.log. */
    @Test
    void theBiosCapsLines_stayOnTheBus_andNeverReachTheConsole() throws IOException {
        bcc("vOLD"); // Leave shim and engine outdated so the engine stages them and emits PENDING.
        String out = captureOut(() -> assertEquals(0, new ClientDoor(base).run(dir)));

        assertFalse(out.contains("BIOS UPDATE COMPLETE"), String.valueOf(out));
        assertFalse(out.contains("BAMFOS UPDATED FROM"), String.valueOf(out));
        assertTrue(out.contains("[updater] updated vOLD -> vNEW"), String.valueOf(out));
        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.stream().anyMatch(l -> l.startsWith("SYNCED\tvOLD\tvNEW\t")), String.valueOf(bus));
        assertTrue(bus.contains("PENDING"), bus.toString());
    }

    /** Watchdog convergence must check both bcc-common.toml and ledger installed_version so a partial sync
     * still triggers a restart, while an empty installed_version is ignored for existing servers. */
    @Test
    void settledVersion_believesTheLedgerOverBcc_butNeverAnAbsentOne() throws IOException {
        bcc("v3");
        assertEquals("v3", Doors.settledVersion(dir).orElse(""));

        Ledger agreed = new Ledger();
        agreed.installedVersion = "v3";
        agreed.save(Ledger.pathIn(dir));
        assertEquals("v3", Doors.settledVersion(dir).orElse(""));

        Ledger heldBack = new Ledger();
        heldBack.installedVersion = "v2"; // Simulate a partial sync where bcc updated but another managed file failed.
        heldBack.save(Ledger.pathIn(dir));
        assertEquals("v2", Doors.settledVersion(dir).orElse(""));

        assertTrue(Doors.settledVersion(dir.resolve("nope")).isEmpty());
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
        assertEquals("updater: repository unreachable, continuing boot",
                ClientDoor.abortLine(new java.net.UnknownHostException("api.github.com")));
        assertEquals("updater: repository unreachable, continuing boot",
                ClientDoor.abortLine(new IOException("wrapped", new java.net.ConnectException("refused"))));
        assertEquals("updater: repository unreachable, continuing boot",
                ClientDoor.abortLine(new HttpFetcher.TransportFault("the download stalled, no bytes for 30000ms")));
        assertEquals("updater: no release named v0.2.4 on the channel, continuing boot",
                ClientDoor.abortLine(new GithubReleases.ReleaseMissing("SixxHands/bamfos-pack", "v0.2.4",
                        "https://api.github.com/repos/SixxHands/bamfos-pack/releases/tags/v0.2.4")));
        assertEquals("updater: the channel has no published release yet, continuing boot",
                ClientDoor.abortLine(new GithubReleases.ReleaseMissing("SixxHands/bamfos-pack", "latest",
                        "https://api.github.com/repos/SixxHands/bamfos-pack/releases/latest")));
        assertEquals("updater: sync aborted, continuing boot",
                ClientDoor.abortLine(new IOException("There is not enough space on the disk")));
        assertEquals("updater: sync aborted, continuing boot",
                ClientDoor.abortLine(new IllegalStateException("something new")));
        // A bad repo= value (like one with a space) throws IllegalArgumentException wrapping URISyntaxException.
        // Don't misclassify that as "unreachable" when the config file itself is broken.
        assertEquals("updater: sync aborted, continuing boot",
                ClientDoor.abortLine(new IllegalArgumentException("bad repo",
                        new java.net.URISyntaxException("SixxHands/bamfos pack", "Illegal character in path"))));
        assertEquals("updater: sync aborted, continuing boot",
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
