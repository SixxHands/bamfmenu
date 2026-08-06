package com.retro.bamfos.updater.engine.ping;

import com.retro.bamfos.updater.engine.config.UpdaterConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Temporarily answers Minecraft server-list pings with an updating MOTD while the pre-JVM server sync runs.
public final class PortHold {

    private static final int BACKLOG = 32;
    private static final int READ_BUDGET_MS = 3000;   // one server-list ping is milliseconds of talking
    private static final int LINGER_MS = 500;         // See serveStatus: wait for the client to disconnect first.
    private static final int PROBE_MS = 300;
    private static final int RELEASE_BUDGET_MS = 2000;
    private static final int MAX_FRAME = 32 * 1024;
    private static final int MAX_STRING = 512;
    private static final int WORKERS = 4;
    private static final int WAITING_ROOM = 8;

    private final ServerSocket socket;
    private final InetAddress addr; // null = the wildcard bind, so the probe talks to loopback
    private final int port;
    private final byte[] statusPacket;
    private final byte[] refusalPacket;
    private final ExecutorService workers;
    private final String why;
    private volatile boolean closing;

    private static PortHold down(String why) {
        System.out.println("[updater] no updating notice: " + why);
        return new PortHold(null, null, -1, null, null, null, why);
    }

    private PortHold(ServerSocket socket, InetAddress addr, int port, byte[] statusPacket,
                     byte[] refusalPacket, ExecutorService workers, String why) {
        this.socket = socket;
        this.addr = addr;
        this.port = port;
        this.statusPacket = statusPacket;
        this.refusalPacket = refusalPacket;
        this.workers = workers;
        this.why = why;
    }

    /** Binds the server port if possible, or returns a no-op hold. Never throws so binding issues don't block sync. */
    public static PortHold open(Path dir, String target) {
        try {
            Properties server = serverProperties(dir);
            if (server == null) {
                return down("no server.properties here, so this is not a server directory yet");
            }
            if ("false".equalsIgnoreCase(UpdaterConfig.rawProps(dir)
                    .getProperty("updating_placeholder", "true").trim())) {
                return down("updating_placeholder=false");
            }
            String host = UpdatingNotice.bindHost(server);
            InetAddress a = host.isEmpty() ? null
                    : Slp.resolveWithin(host, System.nanoTime() + 2_000_000_000L, InetAddress::getByName);
            int want = UpdatingNotice.bindPort(server);
            ServerSocket s;
            try {
                s = bind(a, want);
            } catch (IOException cannot) {
                return down(cannot.getMessage());
            }
            PortHold hold = new PortHold(s, a, s.getLocalPort(),
                    packet(UpdatingNotice.statusJson(target, UpdatingNotice.icon(dir))),
                    packet(UpdatingNotice.refusalJson(target)), workerPool(), "");
            daemon(hold::accept, "bamfos-updater-notice").start();
            System.out.println("[updater] holding port " + hold.port + " with \""
                    + UpdatingNotice.headline(target) + "\" while the sync runs");
            return hold;
        } catch (Throwable t) {
            // Catch Throwable here so a port-hold failure never blocks syncing or server startup.
            return down(t.toString());
        }
    }

    /**
     * Bounded queue + 4 workers (not newFixedThreadPool, whose queue is unbounded) so port-scanner
     * bursts reject and close immediately instead of queueing up and exhausting file descriptors.
     */
    static ExecutorService workerPool() {
        return new ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WAITING_ROOM),
                r -> daemon(r, "bamfos-updater-notice-worker"));
    }

    public boolean up() {
        return socket != null;
    }

    public int port() {
        return port;
    }

    /** Empty while keeping the port open, otherwise the reason it couldn't bind (matches the console log). */
    public String why() {
        return why;
    }

    /**
     * Stops listening and verifies nothing still answers on the port (returns empty if confirmed, else
     * a warning). Uses a refused connect rather than rebinding, which false-alarms on TIME_WAIT.
     */
    public String release() {
        if (socket == null) {
            return "";
        }
        closing = true;
        try {
            socket.close(); // Unblocks accept() and releases the port.
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        long deadline = System.nanoTime() + RELEASE_BUDGET_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!answers(addr, port)) {
                System.out.println("[updater] port " + port + " released, the server can have it");
                return "";
            }
            sleep(50);
        }
        return "the updating notice would NOT let go of port " + port + ", so the server may fail to bind "
                + "it. Stop the wrapper and check for a leftover java process on that port.";
    }

    // ------------------------------------------------------------------ binding

    /**
     * Tries a plain bind first; falls back to SO_REUSEADDR (needed on Linux while kicked players sit in
     * TIME_WAIT) only after a connect probe confirms no live listener is on the port.
     */
    private static ServerSocket bind(InetAddress addr, int port) throws IOException {
        try {
            return bind(addr, port, false);
        } catch (IOException taken) {
            if (port != 0 && answers(addr, port)) {
                throw new IOException("something is already listening on port " + port
                        + ", and that port belongs to the server, so we never take it");
            }
            try {
                return bind(addr, port, true);
            } catch (IOException stillTaken) {
                throw new IOException("could not hold port " + port + ": " + stillTaken);
            }
        }
    }

    private static ServerSocket bind(InetAddress addr, int port, boolean reuse) throws IOException {
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(reuse);
            s.bind(new InetSocketAddress(addr, port), BACKLOG);
            return s;
        } catch (IOException | RuntimeException failed) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw failed instanceof IOException io ? io : new IOException(failed);
        }
    }

    /** Checks if anything is listening on the address (uses loopback when bound to wildcard). */
    private static boolean answers(InetAddress addr, int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(addr == null ? InetAddress.getLoopbackAddress() : addr, port),
                    PROBE_MS);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    // Wire protocol

    private void accept() {
        while (!closing) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException stopped) {
                if (closing) {
                    return;
                }
                sleep(50); // a transient accept error must not end the hold, nor spin a core
                continue;
            }
            try {
                // the read deadline goes on HERE and not down in serve(), because a socket sitting in the
                // waiting room has not reached serve() yet and would be parked with no deadline at all
                client.setSoTimeout(READ_BUDGET_MS);
                workers.execute(() -> serve(client));
            } catch (RejectedExecutionException | IOException busy) {
                closeQuietly(client); // shutting down, or four slow pingers already: hang up, never queue
            }
        }
    }

    private void serve(Socket client) {
        try (Socket s = client) {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            int next = handshakeIntent(readFrame(in));
            if (next == 1) {
                serveStatus(s, in, out);
            } else if (next == 2 || next == 3) {
                // Send a disconnect packet on login attempts so clients see a busy message instead of hanging on
                // "Logging in..." until timeout.
                out.write(refusalPacket);
                out.flush();
                hangUpAfterHim(s, in);
            }
        } catch (IOException | RuntimeException halfSpoken) {
            // Ignore scanners, legacy 0xFE pings, and early disconnects.
        }
    }

    /** Sends status response and pong, then waits for the client to close (see hangUpAfterHim). */
    private void serveStatus(Socket s, InputStream in, OutputStream out) throws IOException {
        byte[] request = readFrame(in);
        if (request.length == 0 || request[0] != 0) {
            return;
        }
        out.write(statusPacket);
        out.flush();
        byte[] ping = readFrame(in);
        if (ping.length == 9 && ping[0] == 1) {
            writeFramed(out, ping); // the pong is the ping echoed back, id and payload both
        }
        hangUpAfterHim(s, in);
    }

    /**
     * Drains until the client closes (up to LINGER_MS) so the client eats TIME_WAIT and we don't close
     * with unread login bytes (which sends an RST and turns our refusal into "Connection reset").
     */
    private static void hangUpAfterHim(Socket s, InputStream in) throws IOException {
        s.setSoTimeout(LINGER_MS);
        while (in.read() >= 0) {
            // drain until he closes
        }
    }

    /** The next-state field, or -1 for anything that is not a handshake. */
    private static int handshakeIntent(byte[] frame) throws IOException {
        Cursor c = new Cursor(frame);
        if (c.varInt() != 0) {
            return -1;
        }
        c.varInt();     // protocol version, and we answer the same either way
        c.string();     // target hostname
        c.u16();        // and the port
        return c.varInt();
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > MAX_FRAME) {
            // attacker controlled, same rule Slp keeps on the way in: a huge value here is a new byte[]
            throw new IOException("frame length out of range: " + len);
        }
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                throw new IOException("closed mid-frame");
            }
            off += n;
        }
        return b;
    }

    private static int readVarInt(InputStream in) throws IOException {
        int v = 0;
        for (int shift = 0; shift <= 35; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed mid-varint");
            }
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IOException("varint too long");
    }

    /** A whole packet, framed and ready to write: [len][id 0][string json]. Both things this class ever
     *  sends are id 0 (status response in the status state, login disconnect in the login state). */
    private static byte[] packet(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0);
        writeVarInt(payload, body.length);
        payload.write(body, 0, body.length);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, payload.size());
        byte[] p = payload.toByteArray();
        framed.write(p, 0, p.length);
        return framed.toByteArray();
    }

    private static void writeFramed(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, payload.length);
        b.write(payload, 0, payload.length);
        out.write(b.toByteArray());
        out.flush();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /** Parses the handshake from already-buffered bytes so this never blocks on I/O. */
    private static final class Cursor {
        private final byte[] b;
        private int i;

        Cursor(byte[] b) {
            this.b = b;
        }

        int varInt() throws IOException {
            int v = 0;
            for (int shift = 0; shift <= 35; shift += 7) {
                if (i >= b.length) {
                    throw new IOException("varint past the end of the frame");
                }
                int x = b[i++] & 0xFF;
                v |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) {
                    return v;
                }
            }
            throw new IOException("varint too long");
        }

        void string() throws IOException {
            int len = varInt();
            if (len < 0 || len > MAX_STRING || i + len > b.length) {
                throw new IOException("string length out of range: " + len);
            }
            i += len;
        }

        void u16() throws IOException {
            if (i + 2 > b.length) {
                throw new IOException("port past the end of the frame");
            }
            i += 2;
        }
    }

    // Helpers

    /** Reads server.properties itself rather than through UpdaterConfig, because that file is Minecraft's and
     *  this one only ever wants two keys out of it. Null when there is none. */
    private static Properties serverProperties(Path dir) {
        Path f = dir.resolve("server.properties");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        Properties p = new Properties();
        try {
            String text = Files.readString(f, StandardCharsets.ISO_8859_1);
            // Strip UTF-8 BOM (EF BB BF when read as latin-1): Notepad adds it on save, which would corrupt the first key (often server-port) and fall back to 25565.
            if (text.length() >= 3 && text.charAt(0) == 0xEF && text.charAt(1) == 0xBB && text.charAt(2) == 0xBF) {
                text = text.substring(3);
            }
            p.load(new StringReader(text));
        } catch (IOException | RuntimeException unreadable) {
            return p; // Fall back to the default port 25565 if server.properties is missing or unreadable.
        }
        return p;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true); // Daemon thread so the shim process can exit cleanly.
        return t;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
