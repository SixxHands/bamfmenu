package com.retro.bamfrestore;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/* Runs the staged world swap on boot before anything opens the world. We rename the old world aside
 * instead of deleting it, and park the marker on error. If a failure lands between the two renames
 * and we can't roll back, we abort boot so Minecraft doesn't generate a fresh empty world over it. */
public final class BootRestore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String MARKER = "bamfrestore-pending.txt";
    static final String MARKER_FAILED = "bamfrestore-pending.failed.txt";
    static final String MARKER_LAST = "bamfrestore-last.txt";
    static final String BACKUP_DIR = "simplebackups";

    /** Required free disk space before restoring, as a multiple of the zip size. */
    private static final long SPACE_FACTOR = 3;

    /** Thrown only when continuing would start a blank world; the wrapper will retry. */
    static final class HaltBoot extends RuntimeException {
        HaltBoot(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static void applyPendingIfAny(Path gameDir) {
        try {
            Path marker = gameDir.resolve(MARKER);
            if (!Files.isRegularFile(marker)) {
                LOGGER.info("[BAMFRESTORE] no rollback armed, booting normally");
                return;
            }
            String fileName = Files.readString(marker).trim();
            LOGGER.warn("[BAMFRESTORE] rollback armed: {}", fileName);
            String failure = apply(gameDir, fileName);
            if (failure == null) {
                Files.deleteIfExists(marker);
                Files.deleteIfExists(gameDir.resolve(MARKER_FAILED));
                LOGGER.warn("[BAMFRESTORE] rollback done, marker cleared, booting into the restored world");
            } else {
                park(marker, gameDir, failure);
            }
        } catch (HaltBoot halt) {
            // leave marker file so the next boot retries the swap
            LOGGER.error("[BAMFRESTORE] REFUSING TO BOOT: {}", halt.getMessage());
            throw halt;
        } catch (Throwable t) {
            // whatever else broke, the world is still in place, so boot it
            LOGGER.error("[BAMFRESTORE] rollback failed unexpectedly, booting the world as it is", t);
            try {
                park(gameDir.resolve(MARKER), gameDir, t.toString());
            } catch (IOException ignored) {
            }
        }
    }

    // null = success, otherwise an error message
    private static String apply(Path gameDir, String fileName) throws IOException {
        Path zip = resolveBackup(gameDir, fileName);
        if (zip == null) {
            return "marker names a file we refuse to touch: " + fileName;
        }
        if (!Files.isRegularFile(zip)) {
            return "backup not found: " + zip;
        }

        long zipSize = Files.size(zip);
        long usable = Files.getFileStore(gameDir).getUsableSpace();
        if (usable < zipSize * SPACE_FACTOR) {
            return "not enough disk: need " + (zipSize * SPACE_FACTOR) + " bytes free, have " + usable;
        }

        String level = levelName(gameDir);
        Path world = gameDir.resolve(level);
        Path incoming = gameDir.resolve(level + "_bamfrestore_incoming");
        deleteTree(incoming); // leftovers from a crashed earlier attempt

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                names.add(en.nextElement().getName());
            }
            Optional<String> rootOpt = RestoreBrain.findWorldRoot(names);
            if (rootOpt.isEmpty()) {
                return "no level.dat found in the zip, refusing to guess where the world is";
            }
            String root = rootOpt.get();
            LOGGER.info("[BAMFRESTORE] extracting {} entries, world rooted at '{}'", names.size(), root);

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (RestoreBrain.isJunkEntry(name) || !name.startsWith(root) || name.length() == root.length()) {
                    continue;
                }
                Path target = incoming.resolve(name.substring(root.length())).normalize();
                if (!target.startsWith(incoming)) {
                    deleteTree(incoming);
                    return "zip entry escapes the target dir, refusing the whole file: " + name;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        if (!Files.isRegularFile(incoming.resolve("level.dat"))) {
            deleteTree(incoming);
            return "extraction finished but level.dat is missing, standing down";
        }

        Path parked = null;
        if (Files.isDirectory(world)) {
            parked = uniqueParkedName(gameDir, level);
            Files.move(world, parked);
            LOGGER.warn("[BAMFRESTORE] old world parked at {} (delete it yourself once you are sure)", parked.getFileName());
        }
        try {
            Files.move(incoming, world);
        } catch (IOException e) {
            // put the old world back so we never boot into a fresh empty world
            if (parked != null) {
                try {
                    Files.move(parked, world);
                } catch (IOException back) {
                    throw new HaltBoot("could not place the restored world AND could not put the old "
                            + "one back. nothing is lost: the old world is at " + parked.getFileName()
                            + ", the restored copy at " + incoming.getFileName()
                            + ". fix the file lock (antivirus?) and restart", back);
                }
                return "could not place the restored world, put the old one back untouched: " + e;
            }
            throw new HaltBoot("no world dir existed and the restored one could not be moved in. "
                    + "the restored copy sits at " + incoming.getFileName() + ". restart to retry", e);
        }

        Files.writeString(gameDir.resolve(MARKER_LAST),
                "restored " + fileName + (parked != null ? ", old world parked at " + parked.getFileName() : ""),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return null;
    }

    // validate the backup zip right away and write the pending rollback marker
    public static String arm(Path gameDir, String fileName) {
        Path zip = resolveBackup(gameDir, fileName);
        if (zip == null) {
            return "that is not a plain zip path (name, or subdir/name)";
        }
        if (!Files.isRegularFile(zip)) {
            return "no such backup: " + fileName;
        }
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                names.add(en.nextElement().getName());
            }
            if (RestoreBrain.findWorldRoot(names).isEmpty()) {
                return "that zip has no level.dat, it is not a world backup we can use";
            }
            Files.writeString(gameDir.resolve(MARKER), fileName,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(gameDir.resolve(MARKER_FAILED)); // clear any previous failure marker
            return null;
        } catch (IOException e) {
            return "could not read that zip: " + e.getMessage();
        }
    }

    // resolves a safe relative zip name under simplebackups/, or null if invalid
    private static Path resolveBackup(Path gameDir, String fileName) {
        if (!RestoreBrain.isCleanRelativeZip(fileName)) {
            return null;
        }
        Path dir = gameDir.resolve(BACKUP_DIR);
        Path zip = dir.resolve(fileName).normalize();
        return zip.startsWith(dir) ? zip : null;
    }

    // newest first, scanning one subdir deep where Simple Backups writes zips
    public static List<Path> listBackups(Path gameDir) throws IOException {
        Path dir = gameDir.resolve(BACKUP_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir, 2)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
        }
    }

    public static String levelName(Path gameDir) {
        Path props = gameDir.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            try (InputStream in = Files.newInputStream(props)) {
                Properties p = new Properties();
                p.load(in);
                String name = p.getProperty("level-name", "world").trim();
                if (!name.isBlank()) {
                    return name;
                }
            } catch (IOException ignored) {
                // fall back to default "world"
            }
        }
        return "world";
    }

    private static Path uniqueParkedName(Path gameDir, String level) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path parked = gameDir.resolve(level + "_pre_restore_" + stamp);
        int n = 2;
        while (Files.exists(parked)) {
            parked = gameDir.resolve(level + "_pre_restore_" + stamp + "-" + n++);
        }
        return parked;
    }

    private static void park(Path marker, Path gameDir, String reason) throws IOException {
        LOGGER.error("[BAMFRESTORE] rollback NOT applied: {}", reason);
        if (Files.isRegularFile(marker)) {
            Path failed = gameDir.resolve(MARKER_FAILED);
            Files.writeString(failed, Files.readString(marker) + System.lineSeparator() + reason,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.delete(marker);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private BootRestore() {
    }
}
