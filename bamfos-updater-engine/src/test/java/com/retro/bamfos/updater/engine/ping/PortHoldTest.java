package com.retro.bamfos.updater.engine.ping;

import com.retro.bamfos.updater.engine.json.MiniJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the down-window notice over real sockets using server-port=0 for ephemeral ports. */
class PortHoldTest {

    @TempDir
    Path dir;

    private PortHold hold;

    /** A test that fails mid-way must not leave a listener behind for the next one. */
    @AfterEach
    void letGo() {
        if (hold != null) {
            hold.release();
        }
    }

    private PortHold open(int port) throws IOException {
        Files.writeString(dir.resolve("server.properties"),
                "server-port=" + port + "\nserver-ip=127.0.0.1\n");
        hold = PortHold.open(dir, "v0.2.6");
        return hold;
    }

    /** End-to-end check with Slp: if Slp can't parse the status response we send here,
     *  a player's client won't be able to parse it either. */
    @Test
    void answersARealStatusPing() throws Exception {
        PortHold h = open(0);
        assertTrue(h.up());
        Map<?, ?> root = (Map<?, ?>) MiniJson.parse(Slp.status("127.0.0.1", h.port()));
        Map<?, ?> description = (Map<?, ?>) root.get("description");
        assertEquals("Updating the server to v0.2.6!\nBack in a minute", description.get("text"));
        assertEquals("", h.release());
    }

    /** A join attempt mid-update should get a login-disconnect notice instead of hanging until client timeout. */
    @Test
    void refusesAJoinInsteadOfLeavingHimHanging() throws Exception {
        PortHold h = open(0);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", h.port()), 2000);
            s.setSoTimeout(3000);
            handshake(s.getOutputStream(), 2); // next state: login
            byte[] packet = readFrame(s.getInputStream());
            assertEquals(0, packet[0]);
            Map<?, ?> reason = (Map<?, ?>) MiniJson.parse(utf(packet));
            assertEquals("Updating the server to v0.2.6!\nGive it a minute and try again.", reason.get("text"));
        }
        assertEquals("", h.release());
    }

    /**
     * Vanilla 1.21.1 sends ServerboundHelloPacket right after the handshake, leaving unread bytes on the socket.
     * Closing immediately sends TCP RST and drops the disconnect text, so verify the server stays open to drain.
     */
    @Test
    void doesNotResetHimAfterTheRefusal_evenThoughHisLoginIsStillUnread() throws Exception {
        PortHold h = open(0);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", h.port()), 2000);
            s.setSoTimeout(3000);
            handshake(s.getOutputStream(), 2);
            loginStart(s.getOutputStream()); // the packet the vanilla client sends without waiting
            byte[] packet = readFrame(s.getInputStream());
            assertEquals(0, packet[0]);

            s.setSoTimeout(150);
            assertThrows(SocketTimeoutException.class, () -> s.getInputStream().read(),
                    "it closed on him with unread bytes pending, which is an RST and not a hang-up");
        }
        assertEquals("", h.release());
    }

    /**
     * Worker pool must be bounded so overflow connections drop immediately instead of exhausting file descriptors.
     */
    @Test
    void hangsUpOnTheOverflowInsteadOfQueueingEverySlowPinger() {
        ExecutorService pool = PortHold.workerPool();
        CountDownLatch parked = new CountDownLatch(1);
        try {
            for (int i = 0; i < 12; i++) { // Fill all four workers and the entire wait queue.
                pool.execute(() -> {
                    try {
                        parked.await();
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {
            }), "an unbounded queue takes this one too, and the hang-up path never runs at all");
        } finally {
            parked.countDown();
            pool.shutdownNow();
        }
    }

    /** Closing PortHold must drop the listener and release the port so the server can bind it right away. */
    @Test
    void letsGoOfThePortAndProvesIt() throws Exception {
        PortHold h = open(0);
        int port = h.port();
        assertEquals("", h.release());
        assertThrows(IOException.class, () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            }
        }, "something still accepts connections on " + port);
        try (ServerSocket next = new ServerSocket()) {
            next.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        }
    }

    /**
     * Must not take a port already in use and must log clearly that a listener is active, distinguishing a
     * still-running server from a stuck TIME_WAIT.
     */
    @Test
    void refusesToTakeAPortSomebodyElseIsListeningOnAndSaysSo() throws Exception {
        try (ServerSocket live = new ServerSocket()) {
            live.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            PortHold h = open(live.getLocalPort());
            assertFalse(h.up());
            assertEquals("something is already listening on port " + live.getLocalPort()
                    + ", and that port belongs to the server, so we never take it", h.why());
            assertEquals("", h.release());
            try (Socket still = new Socket()) {
                still.connect(new InetSocketAddress("127.0.0.1", live.getLocalPort()), 1000);
                assertTrue(still.isConnected());
            }
        }
    }

    /** Without server.properties this isn't a configured server yet, so skip binding a port. */
    @Test
    void staysDownWhereThereIsNoServer() {
        hold = PortHold.open(dir, "v0.2.6");
        assertFalse(hold.up());
        assertEquals("no server.properties here, so this is not a server directory yet", hold.why());
        assertEquals("", hold.release());
    }

    /** Config off switch so an operator can disable the down-window notice without a new release. */
    @Test
    void staysDownWhenTheOperatorSaysSo() throws IOException {
        Path props = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "updating_placeholder=false\n");
        PortHold h = open(0);
        assertFalse(h.up());
        assertEquals("updating_placeholder=false", h.why());
    }

    /**
     * Strip a leading UTF-8 BOM (EF BB BF) when reading server.properties as latin-1, otherwise a
     * first-line server-port key gets corrupted and falls back to 25565.
     */
    @Test
    void bomOnServerProperties_stillReadsTheRealPort() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        raw.writeBytes("server-port=0\nserver-ip=127.0.0.1\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(dir.resolve("server.properties"), raw.toByteArray());
        hold = PortHold.open(dir, "v0.2.6");
        assertTrue(hold.up());
        assertNotEquals(25565, hold.port());
        assertEquals("", hold.release());
    }

    // raw wire helpers

    private static void handshake(OutputStream out, int next) throws IOException {
        ByteArrayOutputStream hs = new ByteArrayOutputStream();
        varInt(hs, 0);
        varInt(hs, Slp.PROTOCOL);
        byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
        varInt(hs, host.length);
        hs.write(host);
        hs.write(0x63);
        hs.write(0xDD);
        varInt(hs, next);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        varInt(framed, hs.size());
        framed.write(hs.toByteArray());
        out.write(framed.toByteArray());
        out.flush();
    }

    /** ServerboundHelloPacket (id 0 in login state): name + uuid. Clients send it right after
     *  the handshake without waiting, so it's usually already buffered when we read the handshake. */
    private static void loginStart(OutputStream out) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        varInt(p, 0);
        byte[] name = "Notch".getBytes(StandardCharsets.UTF_8);
        varInt(p, name.length);
        p.write(name);
        p.write(new byte[16]);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        varInt(framed, p.size());
        framed.write(p.toByteArray());
        out.write(framed.toByteArray());
        out.flush();
    }

    private static void varInt(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int len = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.read();
            assertTrue(b >= 0);
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        byte[] frame = in.readNBytes(len);
        assertEquals(len, frame.length);
        return frame;
    }

    /** The string that follows the packet id: [id][varint length][utf8]. */
    private static String utf(byte[] packet) {
        int i = 1;
        int len = 0;
        for (int shift = 0; ; shift += 7) {
            int b = packet[i++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        return new String(packet, i, len, StandardCharsets.UTF_8);
    }
}
