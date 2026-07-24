package com.retro.bamfos.updater.engine.ping;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

// Minimal uncompressed NBT scan to read the first server IP from servers.dat when server_host isn't set.
public final class ServersDat {

    public static Optional<String> firstIp(Path serversDat) {
        if (!Files.isRegularFile(serversDat)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(serversDat))) {
            if (in.readByte() != 0x0A) {
                return Optional.empty(); // not a compound root
            }
            skip(in, in.readUnsignedShort()); // root name
            // walk the root compound for the "servers" list
            while (true) {
                int tag = in.readByte();
                if (tag == 0) {
                    return Optional.empty();
                }
                String name = readString(in);
                if (tag == 0x09 && name.equals("servers")) {
                    if (in.readByte() != 0x0A) {
                        return Optional.empty(); // list of something that isn't compounds
                    }
                    if (in.readInt() < 1) {
                        return Optional.empty();
                    }
                    // first entry compound: find its "ip"
                    while (true) {
                        int t = in.readByte();
                        if (t == 0) {
                            return Optional.empty();
                        }
                        String key = readString(in);
                        if (t == 0x08 && key.equals("ip")) {
                            return Optional.of(readString(in));
                        }
                        skipPayload(in, t);
                    }
                }
                skipPayload(in, tag);
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readUnsignedShort()];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void skip(DataInputStream in, int n) throws IOException {
        in.readFully(new byte[n]);
    }

    /** Skips one payload of the given tag type, enough NBT to walk past what we don't want. */
    private static void skipPayload(DataInputStream in, int tag) throws IOException {
        switch (tag) {
            case 0x01 -> in.readByte();
            case 0x02 -> in.readShort();
            case 0x03 -> in.readInt();
            case 0x04 -> in.readLong();
            case 0x05 -> in.readFloat();
            case 0x06 -> in.readDouble();
            case 0x07 -> skip(in, in.readInt());
            case 0x08 -> skip(in, in.readUnsignedShort());
            case 0x09 -> {
                int elem = in.readByte();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    skipPayload(in, elem);
                }
            }
            case 0x0A -> {
                int t;
                while ((t = in.readByte()) != 0) {
                    skip(in, in.readUnsignedShort());
                    skipPayload(in, t);
                }
            }
            case 0x0B -> skip(in, in.readInt() * 4);
            case 0x0C -> skip(in, in.readInt() * 8);
            default -> throw new IOException("unknown NBT tag " + tag);
        }
    }

    private ServersDat() {
    }
}
