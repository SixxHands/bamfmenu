package com.retro.bamfos.updater.engine.ping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Raw Minecraft Server List Ping over plain sockets (no Minecraft classes at locator time; 2500 ms total budget, one try).
public final class Slp {

    public static final int BUDGET_MS = 2500;

    /** 1.21.1 version and protocol, shared with PortHold so updating notices don't show as INCOMPATIBLE. */
    public static final int PROTOCOL = 767;
    public static final String MC_VERSION = "1.21.1";

    public static String status(String host, int port) throws IOException {
        return status(host, port, InetAddress::getByName);
    }

    /** Same as ping(), with an injectable DNS resolver so tests can verify this call site respects the
     *  timeout budget (not just resolveWithin). */
    static String status(String host, int port, Lookup lookup) throws IOException {
        long deadline = System.nanoTime() + BUDGET_MS * 1_000_000L;
        InetAddress addr = resolveWithin(host, deadline, lookup);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), remaining(deadline));
            s.setSoTimeout(remaining(deadline));
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0);                       // packet id: handshake
            writeVarInt(hs, PROTOCOL);
            byte[] hostB = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, hostB.length);
            hs.write(hostB);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 1);                       // next state: status
            writeFramed(out, hs.toByteArray());
            writeFramed(out, new byte[]{0});          // status request

            readVarInt(s, in, deadline);              // frame length
            readVarInt(s, in, deadline);              // packet id
            int len = readVarInt(s, in, deadline);
            // Length comes from the remote server (or a MITM), so cap it to avoid OOM on new byte[len].
            // Normal status responses are a few hundred KB at most.
            if (len < 0 || len > 4_000_000) {
                throw new IOException("SLP status length out of range: " + len);
            }
            byte[] json = new byte[len];
            int off = 0;
            while (off < len) {
                s.setSoTimeout(remaining(deadline));
                int n = in.read(json, off, len - off);
                if (n < 0) {
                    throw new IOException("connection closed mid-status");
                }
                off += n;
            }
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    /** DNS lookup hook, injectable so tests can simulate a slow resolver. */
    interface Lookup {
        InetAddress of(String host) throws IOException;
    }

    /**
     * Resolves a hostname inside our ping budget. InetSocketAddress resolves eagerly outside socket
     * timeouts and OS getaddrinfo ignores interrupts, so we run it on an abandonable daemon thread.
     */
    static InetAddress resolveWithin(String host, long deadline, Lookup lookup) throws IOException {
        ExecutorService dns = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bamfos-updater-slp-dns");
            t.setDaemon(true);
            return t;
        });
        try {
            return dns.submit(() -> lookup.of(host)).get(remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            throw new IOException("SLP budget exhausted resolving " + host);
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof IOException io ? io
                    : new IOException("cannot resolve " + host, failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); // Preserve the interrupt flag so callers can react to it.
            throw new IOException("interrupted resolving " + host);
        } finally {
            dns.shutdownNow();
        }
    }

    private static int remaining(long deadline) throws IOException {
        int ms = (int) ((deadline - System.nanoTime()) / 1_000_000L);
        if (ms <= 0) {
            throw new IOException("SLP budget exhausted");
        }
        return ms;
    }

    private static void writeFramed(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, payload.length);
        b.write(payload);
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

    /** Re-arms SO_TIMEOUT from the shared deadline on every read so a slow peer can't stall boot past budget. */
    private static int readVarInt(Socket s, InputStream in, long deadline) throws IOException {
        int v = 0;
        int shift = 0;
        while (true) {
            s.setSoTimeout(remaining(deadline));
            int b = in.read();
            if (b < 0) {
                throw new IOException("connection closed mid-varint");
            }
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 35) {
                throw new IOException("varint too long");
            }
        }
    }

    private Slp() {
    }
}
