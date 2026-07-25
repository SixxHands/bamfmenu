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

    /** Looks up the ip for the entry named `name`, returning empty if missing or malformed. */
    public static Optional<String> entryIp(Path serversDat, String name) {
        if (!Files.isRegularFile(serversDat)) {
            return Optional.empty();
        }
        try (var in = Files.newInputStream(serversDat)) {
            var entry = findEntry(Nbt.read(in), name);
            if (entry != null && entry.get("ip") instanceof Nbt.Val ip && ip.v() instanceof String s) {
                return Optional.of(s);
            }
        } catch (IOException | RuntimeException e) {
            // Fall through if unreadable.
        }
        return Optional.empty();
    }

    /**
     * Merges the seed's first entry into servers.dat while keeping the player's other servers and NBT
     * fields intact. Places the full seed if missing; returns the entry name for merge_name. Atomic write.
     */
    public static String mergeFirstEntry(Path local, byte[] seedBytes) throws IOException {
        Nbt.Root seed = Nbt.read(new java.io.ByteArrayInputStream(seedBytes));
        var seedEntry = firstEntry(seed);
        if (seedEntry == null) {
            throw new IOException("seed servers.dat has no server entries");
        }
        String seedName = seedEntry.get("name") instanceof Nbt.Val n && n.v() instanceof String s ? s : "";
        String seedIp = seedEntry.get("ip") instanceof Nbt.Val i && i.v() instanceof String s ? s : "";

        Nbt.Root localTree = null;
        if (Files.isRegularFile(local)) {
            try (var in = Files.newInputStream(local)) {
                localTree = Nbt.read(in);
            } catch (IOException | RuntimeException unreadable) {
                localTree = null; // their file is broken; the seed becomes the file, .bak nothing (it was garbage)
            }
        }
        byte[] out;
        if (localTree == null || serversList(localTree) == null) {
            out = seedBytes;
        } else {
            Nbt.ListVal servers = serversList(localTree);
            var ours = findEntry(localTree, seedName);
            if (ours != null) {
                if (ours.get("ip") instanceof Nbt.Val ip && seedIp.equals(ip.v())) {
                    return seedName; // Already up to date; skip rewriting to preserve mtime.
                }
                ours.put("ip", new Nbt.Val(0x08, seedIp));
            } else {
                if (servers.elemType() != 0x0A) {
                    // vanilla writes elemType 0 (TAG_End) for an EMPTY list; inserting into that
                    // shape would emit a list claiming TAG_End elements with compound bytes after,
                    // which nothing can read back, rebuild the list as a compound list first
                    servers = new Nbt.ListVal(0x0A, new java.util.ArrayList<>());
                    localTree.compound().put("servers", new Nbt.Val(0x09, servers));
                }
                servers.items().add(0, new Nbt.Val(0x0A, seedEntry)); // ours goes first, theirs keep their order
            }
            out = Nbt.write(localTree);
        }
        Files.createDirectories(local.toAbsolutePath().getParent());
        Path tmp = local.resolveSibling(local.getFileName() + ".tmp");
        Files.write(tmp, out);
        Files.move(tmp, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return seedName;
    }

    private static Nbt.ListVal serversList(Nbt.Root root) {
        return root.compound().get("servers") instanceof Nbt.Val v && v.v() instanceof Nbt.ListVal l ? l : null;
    }

    @SuppressWarnings("unchecked")
    private static java.util.LinkedHashMap<String, Nbt.Val> firstEntry(Nbt.Root root) {
        Nbt.ListVal l = serversList(root);
        if (l == null || l.items().isEmpty()) {
            return null;
        }
        return l.items().get(0).v() instanceof java.util.LinkedHashMap<?, ?> m
                ? (java.util.LinkedHashMap<String, Nbt.Val>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static java.util.LinkedHashMap<String, Nbt.Val> findEntry(Nbt.Root root, String name) {
        Nbt.ListVal l = serversList(root);
        if (l == null) {
            return null;
        }
        for (Nbt.Val item : l.items()) {
            if (item.v() instanceof java.util.LinkedHashMap<?, ?> m
                    && m.get("name") instanceof Nbt.Val n && name.equals(n.v())) {
                return (java.util.LinkedHashMap<String, Nbt.Val>) m;
            }
        }
        return null;
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
