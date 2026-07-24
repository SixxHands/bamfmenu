package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.json.JsonWriter;
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

/** M5 end-to-end: the client door converges to what the SERVER BROADCASTS -- an in-test SLP server
 *  speaks the real varint wire so the whole ping path runs, no mocks between us and the socket. */
class ClientDoorTest {

    @TempDir
    Path dir;

    private HttpServer http;
    private String base;
    private ServerSocket slp;
    private int slpPort;
    private volatile String statusJson;

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
        http.createContext("/repos/o/r/releases/tags/vNEW", ex -> json(ex,
                "{\"tag_name\":\"vNEW\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1},{\"name\":\"bcc.toml\",\"id\":2},{\"name\":\"cm.jar\",\"id\":3},{\"name\":\"srv.jar\",\"id\":4},{\"name\":\"shim.jar\",\"id\":5},{\"name\":\"engine.jar\",\"id\":6}]}"));
        http.createContext("/repos/o/r/releases/assets/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/repos/o/r/releases/assets/".length());
            ex.getResponseHeaders().add("Location", base + "/s3/" + id);
            ex.sendResponseHeaders(302, -1);
        });
        http.createContext("/s3/", ex -> {
            byte[] body = switch (ex.getRequestURI().getPath().substring(4)) {
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
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods")));
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

        // the loop closes: bcc now says vNEW, so the same broadcast is a noop
        Files.delete(dir.resolve("mods/cm.jar"));
        assertEquals(0, new ClientDoor(base).run(dir));
        assertFalse(Files.exists(dir.resolve("mods/cm.jar")));
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
