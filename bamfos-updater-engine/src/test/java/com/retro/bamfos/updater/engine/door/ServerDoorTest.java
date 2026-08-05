package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.util.HashUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end ServerDoor tests against the fixture: installs, no-ops, rollbacks, offline and
 *  kill -9 recovery, .new shim staging, and persistent bad-token state. */
class ServerDoorTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;

    private static final byte[] CONFIG = "cfg".getBytes(StandardCharsets.UTF_8);
    private static final byte[] M1 = Fx.jar("m1");
    private static final byte[] M2 = Fx.jar("m2");
    private static final byte[] SHIM_NEW = "brand new shim".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENGINE_NEW = "brand new engine".getBytes(StandardCharsets.UTF_8);
    private byte[] zip;
    private final java.util.concurrent.atomic.AtomicInteger goneHits = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        // Point the updater properties at the test fixture repo.
        Path props = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "repo=o/r\ngithub_token=tok\n");
        zip = zipWith("config/a.toml", CONFIG);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // assets by id: 1=manifest vT, 2=zip, 3=m1, 4=shim, 5=manifest vT2, 6=m2, 7=manifest latest(=vT2)
        byte[] manifestT = manifest("vT", entryZip(), entryJar("mods/m1.jar", "m1.jar", M1, "m1"), entryShim(), entryEngine());
        byte[] manifestT2 = manifest("vT2", entryZip(), entryJar("mods/m1.jar", "m1.jar", M1, "m1"),
                entryJar("mods/m2.jar", "m2.jar", M2, "m2"), entryShim(), entryEngine());
        // Incremental manifest where one entry references a deleted source release.
        byte[] manifestT3 = manifest("vT3", entryBackref());
        server.createContext("/repos/o/r/releases/tags/vT", ex -> json(ex,
                "{\"tag_name\":\"vT\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1},{\"name\":\"data.zip\",\"id\":2},{\"name\":\"m1.jar\",\"id\":3},{\"name\":\"shim.jar\",\"id\":4},{\"name\":\"engine.jar\",\"id\":7}]}"));
        server.createContext("/repos/o/r/releases/tags/vT2", ex -> json(ex,
                "{\"tag_name\":\"vT2\",\"assets\":[{\"name\":\"manifest.json\",\"id\":5},{\"name\":\"data.zip\",\"id\":2},{\"name\":\"m1.jar\",\"id\":3},{\"name\":\"m2.jar\",\"id\":6},{\"name\":\"shim.jar\",\"id\":4},{\"name\":\"engine.jar\",\"id\":7}]}"));
        server.createContext("/repos/o/r/releases/tags/vT3", ex -> json(ex,
                "{\"tag_name\":\"vT3\",\"assets\":[{\"name\":\"manifest.json\",\"id\":8},{\"name\":\"old.jar\",\"id\":3}]}"));
        server.createContext("/repos/o/r/releases/assets/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/repos/o/r/releases/assets/".length());
            ex.getResponseHeaders().add("Location", base + "/s3/" + id);
            ex.sendResponseHeaders(302, -1);
        });
        server.createContext("/s3/", ex -> {
            if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            byte[] body = switch (ex.getRequestURI().getPath().substring(4)) {
                case "1" -> manifestT;
                case "2" -> zip;
                case "3" -> M1;
                case "4" -> SHIM_NEW;
                case "5" -> manifestT2;
                case "6" -> M2;
                case "7" -> ENGINE_NEW;
                case "8" -> manifestT3;
                case "9" -> "{ this is not a manifest".getBytes(StandardCharsets.UTF_8);
                default -> new byte[0];
            };
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/dead/repos/", ex -> ex.sendResponseHeaders(401, -1));
        // Valid release missing manifest.json: this is a release/engine error (not a network outage) and must NOT
        // clear engine probation.
        server.createContext("/nomanifest/repos/", ex -> json(ex,
                "{\"tag_name\":\"vT\",\"assets\":[{\"name\":\"data.zip\",\"id\":2}]}"));
        // Release with an unreadable/newer manifest schema; not a network error.
        server.createContext("/badmanifest/repos/o/r/releases/tags/vT", ex -> json(ex,
                "{\"tag_name\":\"vT\",\"assets\":[{\"name\":\"manifest.json\",\"id\":9}]}"));
        server.createContext("/badmanifest/repos/o/r/releases/assets/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/badmanifest/repos/o/r/releases/assets/".length());
            ex.getResponseHeaders().add("Location", base + "/s3/" + id);
            ex.sendResponseHeaders(302, -1);
        });
        // Tag with no GitHub release; counted to confirm a permanent 404 skips the retry ladder.
        server.createContext("/gone/repos/", ex -> {
            goneHits.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
        });
        // Simulated GitHub rate limit: 403 with rate-limit headers (distinct from /dead/ auth failures).
        server.createContext("/limited/repos/", ex -> {
            ex.getResponseHeaders().add("x-ratelimit-remaining", "0");
            ex.sendResponseHeaders(403, -1);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void materializes_noops_thenRollsBackDownward() throws IOException {
        Files.writeString(dir.resolve(ServerDoor.SHIM_PATH), "old shim on disk");
        Files.createDirectories(dir.resolve(ServerDoor.ENGINE_PATH).getParent());
        Files.writeString(dir.resolve(ServerDoor.ENGINE_PATH), "the loaded engine"); // us, running
        ServerDoor door = new ServerDoor(base);

        assertEquals(0, door.run(dir, "vT", false));
        assertArrayEquals(CONFIG, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertArrayEquals(M1, Files.readAllBytes(dir.resolve("mods/m1.jar")));
        assertArrayEquals(SHIM_NEW, Files.readAllBytes(dir.resolve(ServerDoor.SHIM_PATH + ".new")));
        assertEquals("old shim on disk", Files.readString(dir.resolve(ServerDoor.SHIM_PATH)));
        assertArrayEquals(ENGINE_NEW, Files.readAllBytes(dir.resolve(ServerDoor.ENGINE_PATH + ".new")));
        assertEquals("the loaded engine", Files.readString(dir.resolve(ServerDoor.ENGINE_PATH)));

        assertEquals(0, door.run(dir, "vT", false)); // no-op re-run
        assertEquals(0, door.run(dir, "vT2", false)); // upgrade
        assertArrayEquals(M2, Files.readAllBytes(dir.resolve("mods/m2.jar")));
        assertEquals(0, door.run(dir, "vT", false)); // Rollback uses the same sync path to move to an older version.
        assertFalse(Files.exists(dir.resolve("mods/m2.jar")));
    }

    @Test
    void serverSide_announcesDiscoveryAndSync_thenAlreadyCurrent() throws IOException {
        // pre-satisfy the two self-entries so the run is a clean update (nothing staged), and the re-run is a
        // TRUE no-op, this is the exact console the player sees on the wrapper
        Files.writeString(dir.resolve(ServerDoor.SHIM_PATH), new String(SHIM_NEW, StandardCharsets.UTF_8));
        Files.createDirectories(dir.resolve(ServerDoor.ENGINE_PATH).getParent());
        Files.writeString(dir.resolve(ServerDoor.ENGINE_PATH), new String(ENGINE_NEW, StandardCharsets.UTF_8));
        ServerDoor door = new ServerDoor(base);

        assertEquals(0, door.run(dir, "vT", false));
        Path log = dir.resolve(".bamfos-updater").resolve("server-sync.log");
        String logText = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(logText.contains("new release vT discovered"), logText);
        assertTrue(logText.contains("updating server ? -> vT"), logText);
        assertTrue(logText.contains("server synced to vT"), logText);

        Path last = dir.resolve(".bamfos-updater").resolve("last-sync.json");
        String summary = (String) ((Map<?, ?>) MiniJson.parse(Files.readString(last, StandardCharsets.UTF_8))).get("summary");
        assertTrue(summary.startsWith("updated ? -> vT"), summary);

        assertEquals(0, door.run(dir, "vT", false)); // everything (incl. self-entries) matches now
        assertTrue(Files.readString(log, StandardCharsets.UTF_8).contains("server already current (vT)"));
        assertEquals("already current (vT)",
                ((Map<?, ?>) MiniJson.parse(Files.readString(last, StandardCharsets.UTF_8))).get("summary"));
    }

    /** Fetching a missing file while already on the target tag is a repair, not a new release, so
     * server-sync.log must not print "new release discovered" or "vT -> vT". */
    @Test
    void sameVersionRepair_announcesARepair_neverANewRelease() throws IOException {
        Files.writeString(dir.resolve(ServerDoor.SHIM_PATH), new String(SHIM_NEW, StandardCharsets.UTF_8));
        Files.createDirectories(dir.resolve(ServerDoor.ENGINE_PATH).getParent());
        Files.writeString(dir.resolve(ServerDoor.ENGINE_PATH), new String(ENGINE_NEW, StandardCharsets.UTF_8));
        ServerDoor door = new ServerDoor(base);
        assertEquals(0, door.run(dir, "vT", false)); // Converge first so the install is actually on vT.

        Path log = dir.resolve(".bamfos-updater").resolve("server-sync.log");
        Files.writeString(log, ""); // Check only lines from this run since the initial sync above logged "new release".
        Files.delete(dir.resolve("mods/m1.jar")); // the row a player's antivirus takes
        assertEquals(0, door.run(dir, "vT", false));

        String logText = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(logText.contains("repairing 1 file(s) on vT"), logText);
        assertFalse(logText.contains("new release"), String.valueOf(logText));
        assertFalse(logText.contains("vT -> vT"), String.valueOf(logText));
        assertArrayEquals(M1, Files.readAllBytes(dir.resolve("mods/m1.jar")));
    }

    /** Pins must prevent staging self-entries (shim and engine .new) too, since run-server.bat moves
     * staged files unconditionally before the engine runs. */
    @Test
    void pinnedSelfEntries_areNeverStaged() throws IOException {
        Files.writeString(dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties"),
                "repo=o/r\ngithub_token=tok\npin=" + ServerDoor.SHIM_PATH + "," + ServerDoor.ENGINE_PATH + "\n");
        ServerDoor door = new ServerDoor(base);
        assertEquals(0, door.run(dir, "vT", false));

        assertFalse(Files.exists(dir.resolve(ServerDoor.SHIM_PATH + ".new")));
        assertFalse(Files.exists(dir.resolve(ServerDoor.ENGINE_PATH + ".new")));
        // Pinning the updater jars still lets ordinary pack rows update.
        assertArrayEquals(M1, Files.readAllBytes(dir.resolve("mods/m1.jar")));
    }

    @Test
    void offline_exitsZeroAndTouchesNothing() {
        ServerDoor door = new ServerDoor("http://127.0.0.1:1"); // closed port
        assertEquals(0, door.run(dir, "vT", false));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    @Test
    void killMidSync_nextRunConvergesOfflineViaStepZero() throws IOException {
        // Replay the leftover journal and staged files before resolving, so recovery works offline.
        Files.createDirectories(dir.resolve(".bamfos-updater/staging"));
        Files.write(dir.resolve(".bamfos-updater/staging/p1"), M1);
        Journal j = new Journal();
        j.targetVersion = "vT";
        j.ops.add(new Journal.Op("move", ".bamfos-updater/staging/p1", "mods/m1.jar", HashUtils.sha256(M1)));
        j.save(Journal.pathIn(dir));
        ServerDoor door = new ServerDoor("http://127.0.0.1:1"); // and we are OFFLINE
        assertEquals(0, door.run(dir, "vT", false));
        assertArrayEquals(M1, Files.readAllBytes(dir.resolve("mods/m1.jar")));
        assertFalse(Files.exists(Journal.pathIn(dir)));
    }

    @Test
    void tokenDeath_isPersistentAndLoud_thenClearsOnSuccess() throws IOException {
        assertEquals(0, new ServerDoor(base + "/dead").run(dir, "vT", false));
        Path marker = dir.resolve(".bamfos-updater").resolve(ServerDoor.TOKEN_DEAD_MARKER);
        assertTrue(Files.exists(marker));
        assertEquals(0, new ServerDoor(base).run(dir, "vT", false));
        assertFalse(Files.exists(marker));
    }

    /** Server rate-limits must not write the token-dead marker and must log to server-sync.log so the
     * log does not end on an unfinished update. */
    @Test
    void rateLimited_neverWritesTheTokenDeadMarker_andClosesTheLoudLog() throws IOException {
        assertEquals(0, new ServerDoor(base + "/limited").run(dir, "vT", false));
        assertFalse(Files.exists(dir.resolve(".bamfos-updater").resolve(ServerDoor.TOKEN_DEAD_MARKER)));
        String log = Files.readString(dir.resolve(".bamfos-updater").resolve("server-sync.log"),
                StandardCharsets.UTF_8);
        assertTrue(log.contains("github rate limited (403)"), String.valueOf(log));
        assertTrue(log.contains("launching"), String.valueOf(log));
        assertEquals("limited", lastSync().get("result"));
    }

    @Test
    void liveLockHolder_skipsSyncEntirely() throws IOException {
        try (InstanceLock held = InstanceLock.acquire(dir).orElseThrow()) {
            assertEquals(0, new ServerDoor(base).run(dir, "vT", false));
        }
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    /* Write a last-sync record directly instead of trying to lock a file cross-platform.
       The retry decision only reads this record, so seeding it directly tests that logic. */
    private void poseLastSync(String result, boolean held) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("at", System.currentTimeMillis());
        root.put("tag", "vT");
        root.put("result", result);
        root.put("summary", "posed");
        root.put("held", held);
        Files.writeString(dir.resolve(".bamfos-updater").resolve("last-sync.json"), JsonWriter.write(root),
                StandardCharsets.UTF_8);
    }

    /** Server self-heal: automatically re-derive on the next boot when last-sync recorded held files. */
    @Test
    void aRecordSayingFilesWereHeldBack_makesTheNextORDINARYBootRepair() throws IOException {
        ServerDoor door = new ServerDoor(base);
        assertEquals(0, door.run(dir, "vT", false));
        Files.writeString(dir.resolve("config/a.toml"), "mangled by something");
        poseLastSync("pending", true);

        // repair=false here: self-heal must trigger automatically without an explicit repair flag.
        assertEquals(0, door.run(dir, "vT", false));

        assertArrayEquals(CONFIG, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertTrue(Files.readString(serverLogPath(), StandardCharsets.UTF_8).contains("full hash check"));
    }

    /** Healthy "pending" states (staged sync or self-update) must not trigger self-heal re-hashing on
     * the next boot. */
    @Test
    void aPendingThatIsJustStagedFiles_leavesTheNextBootORDINARY() throws IOException {
        ServerDoor door = new ServerDoor(base);
        assertEquals(0, door.run(dir, "vT", false));
        Files.writeString(dir.resolve("config/a.toml"), "mangled by something");
        poseLastSync("pending", false);

        assertEquals(0, door.run(dir, "vT", false));

        assertEquals("mangled by something", Files.readString(dir.resolve("config/a.toml")));
    }

    /** Network errors shouldn't escalate to repair since the sync never touched local files. */
    @Test
    void anOfflineRecordDoesNotBuyARepairPass() throws IOException {
        ServerDoor door = new ServerDoor(base);
        assertEquals(0, door.run(dir, "vT", false));
        Files.writeString(dir.resolve("config/a.toml"), "mangled by something");
        poseLastSync("offline", false);

        assertEquals(0, door.run(dir, "vT", false));

        assertEquals("mangled by something", Files.readString(dir.resolve("config/a.toml")));
    }

    @Test
    void repair_restoresManuallyCorruptedManagedFile() throws IOException {
        ServerDoor door = new ServerDoor(base);
        assertEquals(0, door.run(dir, "vT", false));
        Files.writeString(dir.resolve("config/a.toml"), "mangled by something");
        assertEquals(0, door.run(dir, "vT", false)); // normal sync: this rule skips -> still mangled
        assertEquals("mangled by something", Files.readString(dir.resolve("config/a.toml")));
        assertEquals(0, door.run(dir, "vT", true)); // Repair flag triggers a full hash audit.
        assertArrayEquals(CONFIG, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertTrue(Files.exists(dir.resolve("config/a.toml.bak-vT")));
    }

    /** A valid manifest clears engine probation on the server door; an engine-side fault leaves it armed
     * so the shim rolls back on the next boot. */
    @Test
    void aSyncThatReachedTheManifest_clearsEngineProbation() throws IOException {
        onProbation();
        assertEquals(0, new ServerDoor(base).run(dir, "vT", false));
        assertFalse(Files.exists(probation()));
    }

    @Test
    void aFaultTheEngineCanOnlyPinOnItself_leavesTheEngineOnProbation() throws IOException {
        onProbation();
        assertEquals(0, new ServerDoor(base + "/nomanifest").run(dir, "vT", false));
        assertTrue(Files.isRegularFile(probation()));
    }

    /** External faults (offline, rate-limit, revoked token, active lock) must clear probation so a
     * network blip never rolls back a healthy engine. */
    @Test
    void offline_throttled_tokenDead_andALockedInstance_allClearEngineProbation() throws IOException {
        onProbation();
        assertEquals(0, new ServerDoor("http://127.0.0.1:1").run(dir, "vT", false)); // closed port
        assertFalse(Files.exists(probation()));

        onProbation();
        assertEquals(0, new ServerDoor(base + "/limited").run(dir, "vT", false));
        assertFalse(Files.exists(probation()));

        onProbation();
        assertEquals(0, new ServerDoor(base + "/dead").run(dir, "vT", false));
        assertFalse(Files.exists(probation()));

        onProbation();
        try (InstanceLock held = InstanceLock.acquire(dir).orElseThrow()) {
            assertEquals(0, new ServerDoor(base).run(dir, "vT", false));
        }
        assertFalse(Files.exists(probation()));
    }

    /** Server sync failures must log to server-sync.log and overwrite last-sync.json; runs a success
     * first to prove a stale "ok" does not survive later failures. */
    @Test
    void aFailedSync_leavesADurableLine_andStopsReportingTheOldSuccess() throws IOException {
        Files.writeString(dir.resolve(ServerDoor.SHIM_PATH), new String(SHIM_NEW, StandardCharsets.UTF_8));
        Files.createDirectories(dir.resolve(ServerDoor.ENGINE_PATH).getParent());
        Files.writeString(dir.resolve(ServerDoor.ENGINE_PATH), new String(ENGINE_NEW, StandardCharsets.UTF_8));
        assertEquals(0, new ServerDoor(base).run(dir, "vT", false));
        assertEquals("ok", lastSync().get("result"));
        int linesBefore = Files.readAllLines(serverLogPath(), StandardCharsets.UTF_8).size();

        assertEquals(0, new ServerDoor(base + "/nomanifest").run(dir, "vT", false));

        List<String> log = Files.readAllLines(serverLogPath(), StandardCharsets.UTF_8);
        assertTrue(log.size() > linesBefore);
        String line = log.get(log.size() - 1);
        assertTrue(line.contains("sync FAILED"), line);
        assertTrue(line.contains("manifest.json"), String.valueOf(line));
        assertTrue(line.contains("launching"), String.valueOf(line));
        String summary = (String) lastSync().get("summary");
        assertEquals("failed", lastSync().get("result"));
        assertTrue(summary.contains("sync FAILED"), summary);
        assertFalse(summary.contains("updated ? -> vT"), String.valueOf(summary));
    }

    /** Unreachable network must record "offline" and note the upcoming retry rather than logging a hard
     * failure. */
    @Test
    void offline_isRecordedAsOffline_neverAsAFailure() throws IOException {
        assertEquals(0, new ServerDoor("http://127.0.0.1:1").run(dir, "vT", false)); // closed port
        String line = lastLoudLine();
        assertTrue(line.contains("github unreachable"), line);
        assertTrue(line.contains("the next cycle retries"), String.valueOf(line));
        assertFalse(line.contains("FAILED"), String.valueOf(line));
        assertEquals("offline", lastSync().get("result"));
    }

    /** A missing-release 404 must log both tag and channel (since private-repo access errors also 404)
     * and stop after one request without retrying. */
    @Test
    void aTagWithNoRelease_isNamedInTheDurableLog_andAskedExactlyOnce() throws IOException {
        onProbation(); // Test both conditions together: an engine was just promoted and the channel has no matching tag.
        assertEquals(0, new ServerDoor(base + "/gone").run(dir, "vT", false));
        assertFalse(Files.exists(probation()));
        assertEquals(1, goneHits.get());
        String line = lastLoudLine();
        assertTrue(line.contains("no release named \"vT\""), line);
        assertTrue(line.contains("o/r"), String.valueOf(line));
        assertEquals("no-release", lastSync().get("result"));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    /** Clear probation only after ManifestParser.parse succeeds, so an older engine that cannot parse a
     * newer schema rolls back instead of wedging on every boot. */
    @Test
    void aManifestThisEngineCannotREAD_leavesTheEngineOnProbation() throws IOException {
        onProbation();
        assertEquals(0, new ServerDoor(base + "/badmanifest").run(dir, "vT", false));
        assertTrue(Files.isRegularFile(probation()));
        assertEquals("failed", lastSync().get("result"));
    }

    /** GitHub 404s the "latest" alias when only prereleases exist; error text must reflect that rather
     * than claiming a literal tag named "latest" is missing. */
    @Test
    void aChannelWithNoPublishedRelease_isNotReportedAsAMissingTagNamedLatest() throws IOException {
        assertEquals(0, new ServerDoor(base + "/gone").run(dir, "", false));
        String line = lastLoudLine();
        assertTrue(line.contains("has no published release yet"), line);
        assertFalse(line.contains("no release named"), String.valueOf(line));
        assertTrue(line.contains("o/r"), String.valueOf(line));
        assertEquals("no-release", lastSync().get("result"));
    }

    /** A 404 on an incremental backref source release must not log "nothing to sync to" or record
     * "no-release" after the target release already resolved. */
    @Test
    void aDeadBackrefRelease_readsAsAFailedSync_neverAsAChannelWithNothingOnIt() throws IOException {
        assertEquals(0, new ServerDoor(base).run(dir, "vT3", false));
        List<String> log = Files.readAllLines(serverLogPath(), StandardCharsets.UTF_8);
        String line = log.get(log.size() - 1);
        assertTrue(line.contains("sync FAILED"), line);
        assertTrue(line.contains("vDEAD"), String.valueOf(line));
        assertFalse(line.contains("nothing to sync to"), String.valueOf(line));
        assertTrue(log.stream().anyMatch(l -> l.contains("updating server ? -> vT3")), String.valueOf(log));
        assertEquals("failed", lastSync().get("result"));
    }

    /** Explicit repair mode clears refused-engine strikes so operators can recover from transient false
     * positives; genuinely broken bytes re-earn refusal in two boots. */
    @Test
    void repair_forgetsARefusedEngine_soThoseBytesGetAnotherChance() throws IOException {
        Path engineDir = dir.resolve(".bamfos-updater").resolve("engine");
        Files.createDirectories(engineDir);
        Files.writeString(engineDir.resolve("engine-rejected"), "abc123\t1700000000000" + System.lineSeparator());
        Files.writeString(engineDir.resolve("engine-strikes"), "def456\t1" + System.lineSeparator());

        assertEquals(0, new ServerDoor(base).run(dir, "vT", true));

        assertFalse(Files.exists(engineDir.resolve("engine-rejected")));
        assertFalse(Files.exists(engineDir.resolve("engine-strikes")));
    }

    private Path serverLogPath() {
        return dir.resolve(".bamfos-updater").resolve("server-sync.log");
    }

    /** Reads the last line of the error log. Fails with an assertion if the file wasn't created instead
     * of throwing a raw NoSuchFileException. */
    private String lastLoudLine() throws IOException {
        assertTrue(Files.isRegularFile(serverLogPath()));
        List<String> lines = Files.readAllLines(serverLogPath(), StandardCharsets.UTF_8);
        return lines.get(lines.size() - 1);
    }

    private Map<?, ?> lastSync() throws IOException {
        Path f = dir.resolve(".bamfos-updater").resolve("last-sync.json");
        assertTrue(Files.isRegularFile(f));
        return (Map<?, ?>) MiniJson.parse(Files.readString(f, StandardCharsets.UTF_8));
    }

    private Path probation() {
        return dir.resolve(".bamfos-updater").resolve("engine").resolve("engine-probation");
    }

    /** Creates the promotion marker file written by the shim. The engine only deletes it and never reads
     * its contents, so placeholder bytes are fine. */
    private void onProbation() throws IOException {
        Files.createDirectories(probation().getParent());
        Files.writeString(probation(), "a promoted sha the shim wrote");
    }

    // Fixture builders that emit manifest JSON matching the real parser format.

    private Map<String, Object> entryZip() {
        return entry("config/a.toml", HashUtils.sha256(CONFIG), CONFIG.length, "both",
                Map.of("type", "release-zip", "asset", "data.zip", "inner", "config/a.toml"), null);
    }

    private Map<String, Object> entryJar(String path, String asset, byte[] bytes, String modid) {
        Map<String, Object> e = entry(path, HashUtils.sha256(bytes), bytes.length, "server",
                Map.of("type", "release-asset", "asset", asset), null);
        e.put("modids", List.of(modid));
        return e;
    }

    /** Builds an entry pointing at a deleted release (missing asset failure, not an empty channel). */
    private Map<String, Object> entryBackref() {
        return entry("mods/old.jar", HashUtils.sha256(M1), M1.length, "server",
                Map.of("type", "release-asset", "asset", "old.jar", "release", "vDEAD"), null);
    }

    private Map<String, Object> entryShim() {
        return entry(ServerDoor.SHIM_PATH, HashUtils.sha256(SHIM_NEW), SHIM_NEW.length, "server",
                Map.of("type", "release-asset", "asset", "shim.jar"), null);
    }

    private Map<String, Object> entryEngine() {
        return entry(ServerDoor.ENGINE_PATH, HashUtils.sha256(ENGINE_NEW), ENGINE_NEW.length, "both",
                Map.of("type", "release-asset", "asset", "engine.jar"), null);
    }

    private static Map<String, Object> entry(String path, String sha, int size, String side,
                                             Map<String, ?> source, String unused) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("path", path);
        e.put("sha256", sha);
        e.put("size", (long) size);
        e.put("side", side);
        e.put("policy", "managed");
        e.put("source", new LinkedHashMap<>(source));
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
