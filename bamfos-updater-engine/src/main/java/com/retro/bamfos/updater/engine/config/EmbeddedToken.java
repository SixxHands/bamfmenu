package com.retro.bamfos.updater.engine.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Decodes the optional read-only token bundled in the boot shim jar so drop-in installs don't need a manual properties file.
public final class EmbeddedToken {

    /** The manifest name, both sides. A hand-dropped build keeps its version, hence the scan. */
    private static final String SHIM = "bamfos-updater-boot.jar";
    private static final String SHIM_PREFIX = "bamfos-updater-boot";

    /** Order matters: these are pieces of one base64 string and must be concatenated in sequence. */
    private static final String[] SHARDS = {"baseline/idx/0", "baseline/idx/1", "baseline/idx/2"};

    /** Mirrors embedToken in bamfos-updater-boot/build.gradle. Change one, change both. */
    private static final byte[] MASK = "7c1f9a4d3e60b285".getBytes(StandardCharsets.ISO_8859_1);

    /** Returns the token embedded in the adjacent shim jar, or "" if missing or unreadable. */
    public static String read(Path instanceDir) {
        Path shim = findShim(instanceDir);
        return shim == null ? "" : fromJar(shim);
    }

    /** Package-private decode helper so tests can point it at a built shim jar directly. */
    public static String fromJar(Path shimJar) {
        try (ZipFile z = new ZipFile(shimJar.toFile())) {
            StringBuilder b64 = new StringBuilder();
            for (String shard : SHARDS) {
                ZipEntry e = z.getEntry(shard);
                if (e == null) {
                    return ""; // built without an embedded token (supported, see build.gradle)
                }
                try (InputStream in = z.getInputStream(e)) {
                    b64.append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                }
            }
            byte[] raw = Base64.getDecoder().decode(b64.toString());
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) (raw[i] ^ MASK[i % MASK.length]);
            }
            return new String(raw, StandardCharsets.ISO_8859_1);
        } catch (IOException | RuntimeException notReadable) {
            return ""; // catch any jar/zip read error so a corrupt shim just skips the embedded token instead of crashing boot
        }
    }

    /** Looks in the server root first, then mods/, checking the exact managed filename before versioned jars. */
    private static Path findShim(Path instanceDir) {
        List<Path> dirs = List.of(instanceDir, instanceDir.resolve("mods"));
        for (Path dir : dirs) {
            Path exact = dir.resolve(SHIM);
            if (Files.isRegularFile(exact)) {
                return exact;
            }
        }
        for (Path dir : dirs) {
            try (Stream<Path> listing = Files.list(dir)) {
                Path hit = listing.filter(EmbeddedToken::looksLikeTheShim).sorted().findFirst().orElse(null);
                if (hit != null) {
                    return hit;
                }
            } catch (IOException | RuntimeException noSuchDir) {
                // a client has no shim at the instance root and a server has no mods/ until it boots once
            }
        }
        return null;
    }

    private static boolean looksLikeTheShim(Path p) {
        String n = p.getFileName().toString();
        // require .jar so disabled shims like bamfos-updater-boot.jar.disabled are ignored
        return n.startsWith(SHIM_PREFIX) && n.endsWith(".jar") && Files.isRegularFile(p);
    }

    private EmbeddedToken() {
    }
}
