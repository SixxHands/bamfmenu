package com.retro.bamfos.updater.engine.ops;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.util.HashUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ensures factoryReset closes its last FILE bus line with FDONE on every exit, matching stagedSync's END
 * guarantee. Replays .bamfos-updater/bus.log like UpdaterFeed so a failed or finished reset never leaves
 * a stale download line stuck on the BIOS screen.
 */
class ResetBusLineTest {

    @TempDir
    Path dir;

    /** Uses two jars so fetching actually runs: the first succeeds and the second fails. */
    private static final byte[] FIRST = Fx.jar("firstmod");
    private static final byte[] SECOND = Fx.jar("secondmod");

    private HttpServer gh;
    private String api;
    private boolean throttleTheSecondJar;

    /** Sets up a synced v0.2.4 install and mock GitHub; each test sets the response for the second jar. */
    @BeforeEach
    void aBoxAndAGithub() throws IOException {
        Ledger led = new Ledger();
        led.installedVersion = "v0.2.4";
        led.save(Ledger.pathIn(dir));
        Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());

        gh = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/tags/v0.2.4", ex -> body(ex,
                ("{\"tag_name\":\"v0.2.4\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1},"
                        + "{\"name\":\"first.jar\",\"id\":2},{\"name\":\"second.jar\",\"id\":3}]}")
                        .getBytes(StandardCharsets.UTF_8)));
        gh.createContext("/repos/o/r/releases/assets/", ex -> {
            String asset = ex.getRequestURI().getPath();
            if (asset.endsWith("/1")) {
                body(ex, manifestJson());
            } else if (asset.endsWith("/2")) {
                body(ex, FIRST);
            } else if (throttleTheSecondJar) {
                // GitHub rate limit response: 403 with exhausted hourly quota. HttpFetcher treats this
                // as RateLimited from the header and doesn't retry, so the reset stops here.
                ex.getResponseHeaders().add("x-ratelimit-remaining", "0");
                ex.sendResponseHeaders(403, -1);
                ex.close();
            } else {
                body(ex, SECOND);
            }
        });
        gh.start();
        api = "http://127.0.0.1:" + gh.getAddress().getPort();
    }

    @AfterEach
    void stopGithub() {
        gh.stop(0);
    }

    /** End-to-end failure path: first jar succeeds, second fails, and the finally block must still emit FDONE. */
    @Test
    void factoryReset_closesItsLastFileLine_whenGithubThrottlesMidDownload() throws IOException {
        throttleTheSecondJar = true;

        Map<String, Object> r = new UpdaterOps(dir, api).factoryReset();

        assertEquals(5L, r.get("code"), String.valueOf(r.get("message")));
        List<String> lines = busLines();
        assertEquals(List.of("first.jar", "second.jar"), fileNames(lines), String.valueOf(lines));
        assertEquals("FDONE\tsecond.jar", lines.get(lines.size() - 1), String.valueOf(lines));
        assertEquals("", liveActivity(lines));
    }

    /** Success path: arming a reset must also close its last FILE line so the BIOS doesn't show a live download. */
    @Test
    void factoryReset_closesItsLastFileLine_onTheExitThatArmedSuccessfully() throws IOException {
        Map<String, Object> r = new UpdaterOps(dir, api).factoryReset();

        assertEquals(0L, r.get("code"), String.valueOf(r.get("message")));
        assertEquals(2L, r.get("places"));
        List<String> lines = busLines();
        assertEquals(List.of("first.jar", "second.jar"), fileNames(lines), String.valueOf(lines));
        assertEquals("", liveActivity(lines), String.valueOf(lines));
    }

    /** Replays FILE and FDONE in bus.log like UpdaterFeed; an empty result means no active line was left open. */
    private static String liveActivity(List<String> lines) {
        String current = "";
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            if (f[0].equals("FILE")) {
                current = f.length > 1 ? f[1] : "";
            } else if (f[0].equals("FDONE")) {
                current = "";
            }
        }
        return current;
    }

    /** Returns filenames in FILE event order so tests can verify reset reported progress before stopping. */
    private static List<String> fileNames(List<String> lines) {
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            if (f[0].equals("FILE")) {
                names.add(f[1]);
            }
        }
        return names;
    }

    private List<String> busLines() throws IOException {
        return Files.readAllLines(dir.resolve(".bamfos-updater").resolve(BootBus.FILE_NAME));
    }

    /** Release manifest in order; placement follows manifest order so second.jar is last. */
    private static byte[] manifestJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", 1L);
        m.put("pack", new LinkedHashMap<>(Map.of("id", "bamfos", "version", "v0.2.4",
                "mc", "1.21.1", "neoforge", "21.1.235", "updater_abi", 1L)));
        m.put("requires_relaunch", false);
        m.put("removed_modids", List.of());
        m.put("files", List.of(fileEntry("mods/first.jar", "first.jar", FIRST),
                fileEntry("mods/second.jar", "second.jar", SECOND)));
        return JsonWriter.write(m).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> fileEntry(String path, String asset, byte[] bytes) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("path", path);
        e.put("sha256", HashUtils.sha256(bytes));
        e.put("size", (long) bytes.length);
        e.put("side", "both");
        e.put("policy", "managed");
        e.put("source", Map.of("type", "release-asset", "asset", asset));
        return e;
    }

    private static void body(HttpExchange ex, byte[] b) throws IOException {
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }
}
