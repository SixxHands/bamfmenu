package com.retro.bamfos.updater.engine.util;

import java.util.Locale;

// Compares two version tags for wording only (update vs rollback), never to block a move so rollbacks still work.
public final class TagOrder {

    public enum Move {
        FORWARD,
        BACKWARD,
        SAME,
        /** Returned when two tags can't be reliably ordered as newer or older. */
        SIDEWAYS
    }

    public static Move of(String from, String to) {
        if (from == null || to == null) {
            return Move.SIDEWAYS;
        }
        int[] left = parts(core(from));
        int[] right = parts(core(to));
        if (left == null || right == null) {
            return Move.SIDEWAYS; // "?" is a valid placeholder for `was`, not a version.
        }
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0; // 0-fill so v0.2 and v0.2.0 stay the same version
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return r > l ? Move.FORWARD : Move.BACKWARD;
            }
        }
        // Same numeric version, so compare suffixes: a clean release outranks a prerelease suffix
        // (semver rule, matching our -test tags). Don't guess ordering between two different suffixes.
        String leftSuffix = suffix(from);
        String rightSuffix = suffix(to);
        if (leftSuffix.equals(rightSuffix)) {
            return Move.SAME; // also catches v0.2.6 against a bare 0.2.6, same version spelled two ways
        }
        if (leftSuffix.isEmpty() != rightSuffix.isEmpty()) {
            return rightSuffix.isEmpty() ? Move.FORWARD : Move.BACKWARD;
        }
        return Move.SIDEWAYS;
    }

    /**
     * Action word shown to players ("Pack &lt;noun&gt; v0.2.6"). Only provably BACKWARD moves change wording;
     * unorderable or first-install moves (like ? -&gt; v0.2.6) keep the normal "updated" text.
     */
    public static String noun(String from, String to) {
        return of(from, to) == Move.BACKWARD ? "rollback" : "update";
    }

    /** Past-tense verb for a completed version change, e.g. "&lt;verb&gt; v0.2.5 -&gt; v0.2.6". */
    public static String pastVerb(String from, String to) {
        return of(from, to) == Move.BACKWARD ? "rolled back" : "updated";
    }

    /** Drops a leading v and everything from the first suffix marker, leaving the dotted numbers. */
    private static String core(String tag) {
        String s = tag.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        int cut = s.indexOf('-');
        int plus = s.indexOf('+');
        if (plus >= 0 && (cut < 0 || plus < cut)) {
            cut = plus;
        }
        return cut < 0 ? s : s.substring(0, cut);
    }

    /** Returns the suffix from its delimiter onward (or "") from the raw tag for exact comparison. */
    private static String suffix(String tag) {
        String s = tag.trim();
        int cut = s.indexOf('-');
        int plus = s.indexOf('+');
        if (plus >= 0 && (cut < 0 || plus < cut)) {
            cut = plus;
        }
        return cut < 0 ? "" : s.substring(cut);
    }

    /** null for anything that is not dot-separated digits, because a half-read tag must not become a direction. */
    private static int[] parts(String core) {
        if (core.isEmpty()) {
            return null;
        }
        String[] cell = core.split("\\.", -1);
        int[] out = new int[cell.length];
        for (int i = 0; i < cell.length; i++) {
            if (cell[i].isEmpty() || cell[i].length() > 9) {
                return null; // a 20-digit "version" is a date or a hash, not a version
            }
            for (int j = 0; j < cell[i].length(); j++) {
                if (!Character.isDigit(cell[i].charAt(j))) {
                    return null;
                }
            }
            out[i] = Integer.parseInt(cell[i]);
        }
        return out;
    }

    private TagOrder() {
    }
}
