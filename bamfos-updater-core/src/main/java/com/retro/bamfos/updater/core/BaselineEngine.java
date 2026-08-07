package com.retro.bamfos.updater.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Reads the bundled baseline engine bytes out of the stub jar (can't embed it in core since engine already embeds core). Reads into a byte<whatever> so we don't keep a ZipFile handle open on Windows.
final class BaselineEngine {

    /** Resource path where the baseline engine jar is packed inside the stub and core jars. */
    private static final String ENTRY = "baseline/bamfos-updater-engine.jar";

    /** The manifest name, both sides. A hand-dropped build keeps its version, hence the scan. */
    private static final String STUB = "bamfos-updater-boot.jar";
    private static final String STUB_PREFIX = "bamfos-updater-boot";

    /** Checks the core jar first, then the stub jar next to this install. Loaded lazily so normal
     *  boots don't pay the read cost unless the engine slot is missing or corrupt. */
    static Supplier<InputStream> supplier(Path instanceDir) {
        return () -> {
            InputStream own = EngineLoader.embeddedBaseline().get();
            return own != null ? own : fromStub(instanceDir);
        };
    }

    /** Reads the whole entry into a byte array instead of returning a stream so we don't keep a file
     *  handle open on mods/bamfos-updater-boot.jar when the server wrapper tries to swap it. */
    static InputStream fromStub(Path instanceDir) {
        Path stub = findStub(instanceDir);
        if (stub == null) {
            return null;
        }
        try (ZipFile z = new ZipFile(stub.toFile())) {
            ZipEntry e = z.getEntry(ENTRY);
            if (e == null) {
                return null; // an older stub, or one built without the baseline: nothing to fall back to
            }
            try (InputStream in = z.getInputStream(e)) {
                return new ByteArrayInputStream(in.readAllBytes());
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Server root first, then mods/: the exact managed name in each, then a versioned hand-dropped one.
     *  Same walk EmbeddedToken.findShim does, because a player who drops the release asset in by hand keeps its
     *  version in the name and a lookup that only knows the managed name finds nothing on exactly that box. */
    private static Path findStub(Path instanceDir) {
        List<Path> dirs = List.of(instanceDir, instanceDir.resolve("mods"));
        for (Path dir : dirs) {
            Path exact = dir.resolve(STUB);
            if (Files.isRegularFile(exact)) {
                return exact;
            }
        }
        for (Path dir : dirs) {
            try (Stream<Path> listing = Files.list(dir)) {
                Path hit = listing.filter(BaselineEngine::looksLikeTheStub).sorted().findFirst().orElse(null);
                if (hit != null) {
                    return hit;
                }
            } catch (IOException | RuntimeException noSuchDir) {
                // a client has no stub at the instance root and a server has no mods/ until it boots once
            }
        }
        return null;
    }

    private static boolean looksLikeTheStub(Path p) {
        String n = p.getFileName().toString();
        // Require .jar suffix so disabled (.jar.disabled) and staged (.jar.new) jars are ignored.
        return n.startsWith(STUB_PREFIX) && n.endsWith(".jar") && Files.isRegularFile(p);
    }

    private BaselineEngine() {
    }
}
