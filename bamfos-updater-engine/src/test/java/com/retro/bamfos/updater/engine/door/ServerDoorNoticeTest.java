package com.retro.bamfos.updater.engine.door;

import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.ping.Slp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Checks that ServerDoor enables the PortHold notice during sync and stops it afterward, using latches
 * to ping while the release lookup is running. */
class ServerDoorNoticeTest {

    @TempDir
    Path dir;

    private HttpServer github;
    private final CountDownLatch syncing = new CountDownLatch(1);
    private final CountDownLatch pinged = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/repos/o/r/releases/tags/vT", ex -> {
            syncing.countDown();
            try {
                pinged.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(404, -1); // ServerDoor handles this failure cleanly; that's all this test needs.
            ex.close();
        });
        github.start();
    }

    @AfterEach
    void stop() {
        pinged.countDown();
        github.stop(0);
    }

    @Test
    void holdsTheServerPortForTheLengthOfTheSyncAndHandsItBack() throws Exception {
        int port = freePort();
        Files.writeString(dir.resolve("server.properties"),
                "server-port=" + port + "\nserver-ip=127.0.0.1\n");
        Files.createDirectories(dir.resolve(".bamfos-updater"));
        Files.writeString(dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties"),
                "repo=o/r\ngithub_token=tok\n");
        // Simulate the version file written by the watchdog before stopping the server.
        Files.writeString(dir.resolve(".bamfos-updater").resolve("watchdog-state.properties"),
                "last_target=v0.2.6\nlast_restart_at=1753500000000\nlast_target_attempts=1\n");

        int[] code = new int[1];
        Thread door = new Thread(() -> code[0] =
                new ServerDoor("http://127.0.0.1:" + github.getAddress().getPort()).run(dir, "vT", false));
        door.start();

        assertTrue(syncing.await(10, TimeUnit.SECONDS));
        Map<?, ?> root = (Map<?, ?>) MiniJson.parse(Slp.status("127.0.0.1", port));
        Map<?, ?> description = (Map<?, ?>) root.get("description");
        assertEquals("Updating the server to v0.2.6!\nBack in a minute", description.get("text"));
        pinged.countDown();

        door.join(15_000);
        assertFalse(door.isAlive());
        assertEquals(0, code[0]);
        // The port must be free again before the wrapper starts the real server on the next line.
        assertThrows(IOException.class, () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            }
        }, "the notice was still answering on " + port + " after the door returned");
    }

    /** Finds a free ephemeral port for the test server to bind. */
    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
