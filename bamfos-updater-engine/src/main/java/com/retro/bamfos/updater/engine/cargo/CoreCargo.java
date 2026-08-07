package com.retro.bamfos.updater.engine.cargo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

// Writes the embedded core-NNNN.jar out of this engine jar into .bamfos-updater/core/ (tracked outside the manifest and ledger so the reconciler never touches it).
public final class CoreCargo {

    /** The path the build packs the core under, and the one the stub packs its floor copy under. */
    private static final String RESOURCE = "/core/bamfos-updater-core.jar";
    private static final String ENTRY = "core/bamfos-updater-core.jar";

    /** The staged jar's own word about what it carries, stamped into its manifest by the engine build. */
    private static final String GEN_ATTRIBUTE = "Updater-Core-Gen";

    /** Written by CoreHealth in the core module, read here, and the two never link: the filesystem is the bus
     *  between all three layers. Rename it on one side and refused bytes come straight back. */
    private static final String REJECTED = "core-rejected";

    /** Four digits is what makes the stub's LEXICAL sort a numeric one, so a generation of any other shape is
     *  a jar the ladder cannot order. 0000 is the floor the stub writes out of its own resources. */
    private static final Pattern GENERATION = Pattern.compile("\\d{4}");
    private static final String FLOOR = "0000";

    /** The generation THIS engine carries, written out at the top of every run. The belt: it covers a box
     *  whose engine arrived by baseline extract, by hand drop or by factory reset, none of which sync. */
    public static void place(Path instanceDir) {
        try (InputStream in = CoreCargo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return; // a build with no cargo. The jar-content gate catches that, never a player
            }
            write(coreDir(instanceDir), CoreGen.GEN, in);
        } catch (IOException | RuntimeException disk) {
            System.out.println("[updater] core " + CoreGen.GEN + " could not be placed: " + disk);
        }
    }

    /** Reads the core generation from a staged engine .new jar so its core can be placed on this boot,
     *  cutting a core update from three boots down to two. */
    public static void placeFrom(Path instanceDir, Path engineJar) {
        try (JarFile jar = new JarFile(engineJar.toFile())) {
            String gen = jar.getManifest() == null ? null
                    : jar.getManifest().getMainAttributes().getValue(GEN_ATTRIBUTE);
            ZipEntry entry = jar.getEntry(ENTRY);
            if (gen == null || entry == null) {
                // Older engine without cargo (e.g., a rollback target): keep existing
                // generations so the stub runs the newest one.
                return;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                write(coreDir(instanceDir), gen, in);
            }
        } catch (IOException | RuntimeException unreadable) {
            System.out.println("[updater] the staged engine's core could not be placed: " + unreadable);
        }
    }

    /** Clears refused core generations on manual repair only (BIOS repair, /bamfos repair, server repair).
     *  Never call automatically or a broken core will flap forever; place() restores it on the next boot. */
    public static void forgiveRejectedCores(Path instanceDir) {
        Path dir = coreDir(instanceDir);
        try {
            Files.deleteIfExists(dir.resolve(REJECTED));
            Files.deleteIfExists(dir.resolve("core-strikes"));
        } catch (IOException | RuntimeException held) {
            // failing to clear the refused-gen file shouldn't break boot
        }
    }

    private static void write(Path dir, String gen, InputStream bytes) throws IOException {
        if (!GENERATION.matcher(gen).matches() || gen.equals(FLOOR)) {
            System.out.println("[updater] core generation '" + gen + "' is not four digits, or it is the "
                    + "reserved floor, so nothing was placed");
            return;
        }
        Path target = dir.resolve("core-" + gen + ".jar");
        if (Files.exists(target)) {
            return; // already extracted, and the stub may be running it
        }
        if (rejected(dir).contains(target.getFileName().toString())) {
            System.out.println("[updater] core " + gen + " was previously rejected, skipping");
            return;
        }
        Files.createDirectories(dir);
        // include the pid in the temp filename so a double launch doesn't have two writers clobbering the
        // same temp jar
        Path tmp = dir.resolve(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.copy(bytes, tmp, StandardCopyOption.REPLACE_EXISTING);
            // don't pass REPLACE_EXISTING here: if two launches race past the exists check above, let the
            // filesystem reject the second move instead of overwriting
            Files.move(tmp, target);
            System.out.println("[updater] core " + gen + " placed");
        } catch (FileAlreadyExistsException raced) {
            // the sibling won and it wrote the same bytes, because release.py refuses to ship one generation
            // twice with a different core inside it
        } finally {
            sweep(tmp);
        }
    }

    /** Generations marked bad by CoreHealth (first tab-separated field per line). Returns empty if
     *  unreadable so we never crash boot over this file. */
    private static List<String> rejected(Path dir) {
        try {
            Path f = dir.resolve(REJECTED);
            if (!Files.isRegularFile(f)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String gen = line.split("\t")[0].trim();
                if (!gen.isEmpty()) {
                    out.add(gen);
                }
            }
            return out;
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private static void sweep(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException | RuntimeException held) {
            // ignore cleanup failure; the core jar is already in place
        }
    }

    private static Path coreDir(Path instanceDir) {
        return instanceDir.resolve(".bamfos-updater").resolve("core");
    }

    private CoreCargo() {
    }
}
