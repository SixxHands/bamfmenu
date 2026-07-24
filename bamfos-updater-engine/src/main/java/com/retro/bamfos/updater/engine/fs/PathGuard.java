package com.retro.bamfos.updater.engine.fs;

import java.util.Locale;
import java.util.Set;

// Validates every manifest and zip path against Windows path traversal, trailing dots/spaces, and colon ADS tricks before touching disk.
public final class PathGuard {

    public static final class BadPath extends RuntimeException {
        public BadPath(String path, String reason) {
            super("illegal path '" + path + "': " + reason);
        }
    }

    // reserved device names: bare or with any extension, case-insensitive (CON.txt is still CON)
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Validates an install-relative path. Throws BadPath with the exact rule that fired. */
    public static void validate(String path) {
        if (path == null || path.isEmpty()) {
            throw new BadPath(String.valueOf(path), "empty");
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw new BadPath(path, "absolute path");
        }
        if (path.length() >= 2 && path.charAt(1) == ':') {
            throw new BadPath(path, "drive letter");
        }
        if (path.indexOf(':') >= 0) {
            throw new BadPath(path, "colon (NTFS alternate data stream)");
        }
        // Split on both separators so a substring check doesn't false-positive on names like foo..jar.
        String[] segments = path.split("[/\\\\]");
        for (String seg : segments) {
            if (seg.isEmpty()) {
                throw new BadPath(path, "empty segment (doubled or trailing separator)");
            }
            if (seg.equals("..")) {
                throw new BadPath(path, "'..' segment");
            }
            if (seg.endsWith(".") || seg.endsWith(" ")) {
                throw new BadPath(path, "segment ends with dot or space (Windows strips these)");
            }
            String base = seg.toLowerCase(Locale.ROOT);
            int dot = base.indexOf('.');
            if (dot >= 0) {
                base = base.substring(0, dot);
            }
            if (RESERVED.contains(base)) {
                throw new BadPath(path, "reserved device name '" + seg + "'");
            }
        }
    }

    /** Canonical lowercase form used for the case-insensitive duplicate check across a manifest. */
    public static String caseKey(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private PathGuard() {
    }
}
