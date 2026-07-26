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
        server.createContext("/repos/o/r/releases/tags/vT", ex -> json(ex,
                "{\"tag_name\":\"vT\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1},{\"name\":\"data.zip\",\"id\":2},{\"name\":\"m1.jar\",\"id\":3},{\"name\":\"shim.jar\",\"id\":4},{\"name\":\"engine.jar\",\"id\":7}]}"));
        server.createContext("/repos/o/r/releases/tags/vT2", ex -> json(ex,
                "{\"tag_name\":\"vT2\",\"assets\":[{\"name\":\"manifest.json\",\"id\":5},{\"name\":\"data.zip\",\"id\":2},{\"name\":\"m1.jar\",\"id\":3},{\"name\":\"m2.jar\",\"id\":6},{\"name\":\"shim.jar\",\"id\":4},{\"name\":\"engine.jar\",\"id\":7}]}"));
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
                default -> new byte[0];
            };
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/dead/repos/", ex -> ex.sendResponseHeaders(401, -1));
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
    }

    @Test
    void liveLockHolder_skipsSyncEntirely() throws IOException {
        Files.createDirectories(InstanceLock.pathIn(dir).getParent());
        long other = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive())
                .findFirst().orElseThrow().pid();
        Files.writeString(InstanceLock.pathIn(dir), Long.toString(other));
        assertEquals(0, new ServerDoor(base).run(dir, "vT", false));
        assertFalse(Files.exists(dir.resolve("mods")));
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
