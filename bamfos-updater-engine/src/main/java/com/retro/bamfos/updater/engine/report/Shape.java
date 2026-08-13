package com.retro.bamfos.updater.engine.report;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Strict value allowlist gate for trouble reports; anything unrecognized becomes UNKNOWN.
final class Shape {

    /** Valid generation filenames written by CoreCargo. */
    private static final Pattern GEN = Pattern.compile("core-\\d{4}\\.jar");

    /** Bare hex at the three widths this system keeps: our own 12 char fingerprint, a git sha, a sha256. A
     *  credential carries characters outside 0-9a-f, so it cannot arrive through here. */
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{12}|[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");

    /** Matches a release tag or the literal "dev" (used by the manifest during local Gradle runs). */
    private static final Pattern TAG =
            Pattern.compile("dev|v?\\d{1,4}(\\.\\d{1,4}){0,3}(-[A-Za-z0-9]{1,12})?");

    /**
     * Validates a dotted type name. Requiring a dot blocks bare-identifier tokens (like GitHub PATs)
     * from passing through as default-package class names.
     */
    private static final Pattern TYPE = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]{0,39}(\\.[A-Za-z_$][A-Za-z0-9_$]{0,39}){1,9}");

    /** Length cap for validated fields so a huge or corrupt value won't slow down startup. */
    private static final int MAX = 128;

    static final Shape UNKNOWN = new Shape("unknown");
    static final Shape NONE = new Shape("none");

    private final String text;

    private Shape(String text) {
        this.text = text;
    }

    static Shape gen(String raw) {
        return match(GEN, raw);
    }

    /** Keeps 12 hex chars: enough to match a release hash by eye while staying under the 16-char leak window. */
    static Shape sha(String raw) {
        String s = clean(raw);
        return HEX.matcher(s).matches() ? new Shape(s.substring(0, 12)) : UNKNOWN;
    }

    static Shape tag(String raw) {
        return match(TAG, raw);
    }

    /** A throwable's CLASS NAME and never its message. A message carries whatever the JDK felt like
     *  interpolating, and a java.nio one IS an absolute path with a player's username in it. */
    static Shape type(String raw) {
        return match(TYPE, raw);
    }

    /**
     * Matches raw disk text against a compiled-in allowlist and returns our own literal, so hand edits
     * can't inject arbitrary characters into a report.
     */
    static Shape ourWord(String raw, List<String> ours) {
        String s = clean(raw).toLowerCase(Locale.ROOT);
        for (String word : ours) {
            // Exact equals only: substring matching would match "ok" inside "token-dead".
            if (s.equals(word)) {
                return new Shape(word);
            }
        }
        return UNKNOWN;
    }

    /**
     * Prefix variant of ourWord for OS/arch strings with version tails ("Windows 11", "Mac OS X").
     * Still returns our own literal; pass longest prefixes first (e.g. x86_64 before x86).
     */
    static Shape ourPrefix(String raw, List<String> ours) {
        String s = clean(raw).toLowerCase(Locale.ROOT);
        for (String word : ours) {
            if (s.startsWith(word)) {
                return new Shape(word);
            }
        }
        return UNKNOWN;
    }

    /** Formats a numeric field as a string (safe by type, no text validation needed). */
    static Shape number(long value) {
        return new Shape(Long.toString(value));
    }

    /** Returns the validated report string; concatenating a Shape is always safe. */
    @Override
    public String toString() {
        return text;
    }

    private static Shape match(Pattern p, String raw) {
        String s = clean(raw);
        return p.matcher(s).matches() ? new Shape(s) : UNKNOWN;
    }

    private static String clean(String raw) {
        String s = raw == null ? "" : raw.trim();
        return s.length() > MAX ? "" : s;
    }
}
