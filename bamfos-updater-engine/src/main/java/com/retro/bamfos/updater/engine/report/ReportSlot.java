package com.retro.bamfos.updater.engine.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

// Picks the output path in crash-reports/, creating the directory if needed.
final class ReportSlot {

    static final String PREFIX = "bamfos-updater-";
    static final String SUFFIX = ".txt";

    /** Minimal failure note written by the core when it couldn't load an engine, pending expansion. */
    private static final String NOTE_SUFFIX = "-note.txt";

    private static final int KEEP = 5;

    /** path is where it really went; display is the dir-relative form, and every told-the-player string is
     *  built from THIS and never from a hardcoded folder, or a player gets sent to an empty one. */
    record Written(Path path, String display) {
    }

    /** Writes a new report, adding a suffix if a file already exists for the same second.
     *  Returns empty if neither output directory was writable. */
    static Optional<Written> put(Path instanceDir, String fileName, String body) {
        for (Path dir : slots(instanceDir)) {
            Path target = free(dir, fileName);
            if (write(target, body)) {
                return Optional.of(new Written(target, display(instanceDir, target)));
            }
        }
        return Optional.empty();
    }

    /** Rewrites a file we already own, in place, no de-collide: this is the repeat path bumping a header on a
     *  report a player may already be looking at. */
    static Optional<Written> rewrite(Path instanceDir, Path target, String body) {
        return write(target, body)
                ? Optional.of(new Written(target, display(instanceDir, target)))
                : Optional.empty();
    }

    /** Lists reports newest-first so the UI and pruning agree on which report is latest. */
    static List<Path> ours(Path instanceDir) {
        List<Path> out = new ArrayList<>();
        for (Path dir : slots(instanceDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> listing = Files.list(dir)) {
                listing.filter(ReportSlot::isOurs).forEach(out::add);
            } catch (IOException | RuntimeException none) {
                // a folder we cannot list keeps nothing we are allowed to delete anyway
            }
        }
        out.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        return out;
    }

    /** Keeps the newest KEEP report files by mtime and deletes older ones, ignoring other files. */
    static void prune(Path instanceDir) {
        List<Path> mine = ours(instanceDir);
        for (int i = KEEP; i < mine.size(); i++) {
            try {
                Files.deleteIfExists(mine.get(i));
            } catch (IOException | RuntimeException held) {
                // Best effort: a locked report file should never block a boot.
            }
        }
    }

    static Optional<Path> pendingCoreNote(Path instanceDir) {
        for (Path p : ours(instanceDir)) {
            if (p.getFileName().toString().endsWith(NOTE_SUFFIX)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** Matches only our report filenames (exact prefix and suffix) so other files are ignored. */
    private static boolean isOurs(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p) && name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    /**
     * Confirms a remembered path from report-state is our own report inside one of our slot dirs.
     * Normalizes first so a hand-edited or truncated `..` path can't target files outside the slots.
     */
    static boolean isOursInASlot(Path instanceDir, Path p) {
        try {
            Path real = p.toAbsolutePath().normalize();
            if (!isOurs(real)) {
                return false;
            }
            Path parent = real.getParent();
            for (Path slot : slots(instanceDir)) {
                if (parent != null && parent.equals(slot.toAbsolutePath().normalize())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException unresolvable) {
            return false;
        }
    }

    private static List<Path> slots(Path instanceDir) {
        return List.of(instanceDir.resolve("crash-reports"),
                instanceDir.resolve(".bamfos-updater").resolve("reports"));
    }

    private static Path free(Path dir, String fileName) {
        Path first = dir.resolve(fileName);
        if (!Files.exists(first)) {
            return first;
        }
        String stem = fileName.substring(0, fileName.length() - SUFFIX.length());
        for (int n = 2; n <= 9; n++) {
            Path next = dir.resolve(stem + "-" + n + SUFFIX);
            if (!Files.exists(next)) {
                return next;
            }
        }
        return first; // Cap collisions at 9 per second to avoid spinning in a loop.
    }

    /** Writes via a temp file and plain move (not ATOMIC_MOVE, which can throw on Windows; reports are best-effort). */
    private static boolean write(Path target, String body) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(part, body, StandardCharsets.UTF_8);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException unwritable) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException | RuntimeException stuck) {
                // Best-effort cleanup of the temp .part file.
            }
            return false;
        }
    }

    /** Forward slashes both sides, because this string is read by a player and pasted into chat. */
    static String display(Path instanceDir, Path file) {
        try {
            return instanceDir.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (RuntimeException elsewhere) {
            return file.getFileName().toString();
        }
    }

    private ReportSlot() {
    }
}
