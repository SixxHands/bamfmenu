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
        return mergeFirstEntry(local, seedBytes, null);
    }

    /**
     * Looks up our entry by priorName first (handles seed renames) and overlays all seed fields.
     * Propagates read errors (like AV locks) so we retry later instead of wiping the player's list;
     * only falls back to the seed after backing up bytes we actually read and couldn't parse.
     */
    public static String mergeFirstEntry(Path local, byte[] seedBytes, String priorName) throws IOException {
        Nbt.Root seed = Nbt.read(new java.io.ByteArrayInputStream(seedBytes));
        var seedEntry = firstEntry(seed);
        if (seedEntry == null) {
            throw new IOException("seed servers.dat has no server entries");
        }
        String seedName = seedEntry.get("name") instanceof Nbt.Val n && n.v() instanceof String s ? s : "";
        String seedIp = seedEntry.get("ip") instanceof Nbt.Val i && i.v() instanceof String s ? s : "";

        Nbt.Root localTree = null;
        byte[] localBytes = null;
        if (Files.exists(local)) {
            // Read outside the parse catch (and check exists, not isRegularFile) so a directory or AccessDenied
            // throws instead of being treated as a corrupt servers.dat.
            localBytes = Files.readAllBytes(local);
            try {
                localTree = Nbt.read(new java.io.ByteArrayInputStream(localBytes));
            } catch (IOException | RuntimeException unparseable) {
                localTree = null; // File is unreadable/corrupt: back it up and replace it with the seed.
            }
        }
        byte[] out;
        if (localTree == null || serversList(localTree) == null) {
            if (localBytes != null) {
                // Back up an unreadable/unparseable servers.dat before replacing it (e.g. a gzipped file fails
                // uncompressed NBT parsing, and this is the player's whole server list).
                Files.write(local.resolveSibling(local.getFileName() + ".bak-unreadable"), localBytes);
            }
            out = seedBytes;
        } else {
            Nbt.ListVal servers = serversList(localTree);
            var ours = priorName != null && !priorName.isEmpty() ? findEntry(localTree, priorName) : null;
            if (ours == null) {
                ours = findEntry(localTree, seedName);
            }
            if (ours != null) {
                String curName = ours.get("name") instanceof Nbt.Val nm && nm.v() instanceof String s ? s : "";
                String curIp = ours.get("ip") instanceof Nbt.Val ip && ip.v() instanceof String s ? s : "";
                if (curName.equals(seedName) && curIp.equals(seedIp)) {
                    return seedName; // Already up to date; skip rewriting to preserve mtime.
                }
                ours.putAll(seedEntry); // Overwrite all seed fields (name, ip, etc.) while keeping any extra user fields.
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
        // n is derived from an untrusted readInt (sometimes *4 / *8, which can overflow negative):
        // bound it before allocating so a hostile servers.dat can't OOM the boot via firstIp
        if (n < 0 || n > 33_554_432) {
            throw new IOException("servers.dat skip length out of range: " + n);
        }
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
