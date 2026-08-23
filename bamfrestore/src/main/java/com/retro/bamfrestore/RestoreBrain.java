package com.retro.bamfrestore;

import java.util.List;
import java.util.Optional;

/* Pure rollback helpers (no Minecraft imports so unit tests can run directly). We scan the zip
 * for level.dat instead of guessing the folder layout, since wrong writes will make you relive
 * inception in minecraft but by crashing your game rather than the full feature film. */
public final class RestoreBrain {

    /** backup.log is stored at the zip root when captureLatestLog is enabled; skip it during extraction. */
    public static boolean isJunkEntry(String name) {
        return name.equals("backup.log");
    }

    // Blocks path traversal on command and marker paths. Allows one subdir level for simplebackups/<world>/<name>.zip.
    public static boolean isCleanRelativeZip(String name) {
        if (name == null || name.isBlank() || !name.endsWith(".zip") || name.contains("\\")) {
            return false;
        }
        String[] parts = name.split("/", -1);
        if (parts.length > 2) {
            return false;
        }
        for (String part : parts) {
            if (part.isBlank() || part.equals("..") || part.equals(".")) {
                return false;
            }
        }
        return true;
    }

    // Returns the world root prefix inside the zip ("" at root, "somedir/" if one folder contains level.dat, empty if ambiguous).
    public static Optional<String> findWorldRoot(List<String> entryNames) {
        for (String name : entryNames) {
            if (name.equals("level.dat")) {
                return Optional.of("");
            }
        }
        String root = null;
        for (String name : entryNames) {
            int slash = name.indexOf('/');
            if (slash > 0 && name.substring(slash + 1).equals("level.dat")) {
                if (root != null && !root.equals(name.substring(0, slash + 1))) {
                    return Optional.empty(); // two worlds in one zip, not ours to pick
                }
                root = name.substring(0, slash + 1);
            }
        }
        return Optional.ofNullable(root);
    }

    private RestoreBrain() {
    }
}
