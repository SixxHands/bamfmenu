package com.retro.bamfos.updater.engine;

import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Test fixtures: in-memory packs, tiny real jars with mods.toml, a map-backed Fetcher. */
public final class Fx {

    public static final Map<String, byte[]> CONTENT = new HashMap<>();

    public static FileEntry entry(String path, byte[] bytes, FileEntry.Side side) {
        return entry(path, bytes, side, FileEntry.Policy.MANAGED, false, null, FileEntry.OnRemove.DELETE, List.of());
    }

    public static FileEntry entry(String path, byte[] bytes, FileEntry.Side side, FileEntry.Policy policy,
                                  boolean installDisabled, String category, FileEntry.OnRemove onRemove,
                                  List<String> modids) {
        CONTENT.put(path + "@" + HashUtils.sha256(bytes), bytes);
        return new FileEntry(path, HashUtils.sha256(bytes), bytes.length, side, policy,
                FileEntry.Clazz.HOT,
                new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET, path, null, null, null, null),
                modids, installDisabled, category, onRemove);
    }

    public static Manifest manifest(String version, List<String> removedModids, FileEntry... files) {
        return new Manifest(1,
                new Manifest.Pack("bamfos", version, "1.21.1", "21.1.235", 1, List.of(1L)),
                false, removedModids, List.of(files));
    }

    /** Serves the exact bytes each entry was built from, the "network" for reconciler tests. */
    public static Reconciler.Fetcher fetcher() {
        return (e, dest) -> {
            byte[] b = CONTENT.get(e.path() + "@" + e.sha256());
            if (b == null) {
                throw new IOException("no fixture bytes for " + e.path());
            }
            Files.createDirectories(dest.getParent());
            Files.write(dest, b);
        };
    }

    /** Builds a minimal jar with a neoforge.mods.toml declaring the given modids. */
    public static byte[] jar(String... modids) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bos)) {
                StringBuilder toml = new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"x\"\n");
                for (String id : modids) {
                    toml.append("[[mods]]\nmodId=\"").append(id).append("\"\n");
                }
                z.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
                z.write(toml.toString().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                // Extra entry so two jars with the same modid can still have different bytes.
                z.putNextEntry(new ZipEntry("marker-" + String.join("-", modids) + ".txt"));
                z.write(String.valueOf(System.identityHashCode(modids)).getBytes());
                z.closeEntry();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Same uncompressed-NBT shape vanilla writes: root compound > list "servers" > {name, ip}. */
    public static byte[] serversDat(String... ips) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            out.writeByte(0x0A);
            out.writeUTF("");
            out.writeByte(0x09);
            out.writeUTF("servers");
            out.writeByte(0x0A);
            out.writeInt(ips.length);
            for (String ip : ips) {
                out.writeByte(0x08);
                out.writeUTF("name");
                out.writeUTF("BAMFOS");
                out.writeByte(0x08);
                out.writeUTF("ip");
                out.writeUTF(ip);
                out.writeByte(0x00);
            }
            out.writeByte(0x00);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void write(Path dir, String rel, byte[] bytes) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    private Fx() {
    }
}
