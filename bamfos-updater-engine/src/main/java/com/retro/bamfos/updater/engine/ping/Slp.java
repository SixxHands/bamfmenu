package com.retro.bamfos.updater.engine.ping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// Raw Minecraft Server List Ping over plain sockets (no Minecraft classes at locator time; 2500 ms total budget, one try).
public final class Slp {

    public static final int BUDGET_MS = 2500;

    public static String status(String host, int port) throws IOException {
        long deadline = System.nanoTime() + BUDGET_MS * 1_000_000L;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), BUDGET_MS);
            s.setSoTimeout(remaining(deadline));
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0);                       // packet id: handshake
            writeVarInt(hs, 767);                     // protocol 1.21.1
            byte[] hostB = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, hostB.length);
            hs.write(hostB);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 1);                       // next state: status
            writeFramed(out, hs.toByteArray());
            writeFramed(out, new byte[]{0});          // status request

            readVarInt(in, deadline);                 // frame length
            readVarInt(in, deadline);                 // packet id
            int len = readVarInt(in, deadline);
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

    private static int readVarInt(InputStream in, long deadline) throws IOException {
        int v = 0;
        int shift = 0;
        while (true) {
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
