package com.retro.bamfos.updater.watchdog;

import com.retro.bamfos.updater.engine.json.MiniJson;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

// Storm-guard state persistence and clamped config reads (kept MC-free so JUnit tests can pin timestamps and bounds directly).
final class WatchdogState {

    static Properties load(Path dir) {
        Properties p = new Properties();
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("watchdog-state.properties");
            if (Files.isRegularFile(f)) {
                p.load(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
        }
        return p;
    }

    /** Records the restart we are ABOUT to take, count included. The +1 lives here, not in the @Mod class:
     *  the cooldown alone only spaces the bounces out, this count is the only thing that ever ends them, and
     *  an off-by-one there is a box that restart-storms forever with nothing red anywhere. */
    static void recordRestart(Path dir, String target, long at, int attemptsSoFar) {
        save(dir, target, at, attemptsSoFar + 1);
    }

    /** Persists state atomically (tmp + ATOMIC_MOVE) before halt(false) so a sudden kill cannot leave a
     *  truncated file that resets the storm guard. */
    static void save(Path dir, String target, long at, int attempts) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("watchdog-state.properties");
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, "last_target=" + target + "\nlast_restart_at=" + at
                    + "\nlast_target_attempts=" + attempts + "\n");
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // losing the record only forgets one storm-guard cycle, never fatal
        }
    }

    /**
     * Reads the pre-JVM shim's summary from last-sync.json if it is within this boot's freshness window.
     * Kept outside the @Mod class so unit tests can inject `now`.
     */
    static Optional<String> preBootSummary(Path dir, long now, long freshnessMs) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("last-sync.json");
            if (!Files.isRegularFile(f)) {
                return Optional.empty();
            }
            Object parsed = MiniJson.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> m)) {
                return Optional.empty();
            }
            long age = m.get("at") instanceof Number n ? now - n.longValue() : -1;
            if (m.get("summary") instanceof String s && !s.isEmpty() && age >= 0 && age < freshnessMs) {
                return Optional.of(s);
            }
        } catch (Exception ignored) {
            // a garbled summary is never worth failing a boot over
        }
        return Optional.empty();
    }

    /**
     * Returns true if the pre-JVM shim wrote a fresh mark for this boot, indicating a wrapper loop is active.
     * Defaults to false (announce-only) when absent or stale so direct/panel servers never halt without a relauncher.
     */
    static boolean wrapperRan(Path dir, long now, long freshnessMs) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve("wrapper-run");
            if (!Files.isRegularFile(f)) {
                return false;
            }
            long at = Long.parseLong(Files.readString(f, StandardCharsets.UTF_8).trim());
            long age = now - at;
            // a mark from the FUTURE is a clock that moved, never a wrapper, because believing it would keep
            // auto-restart armed on an unwrappered box for as long as the skew lasts
            return age >= 0 && age < freshnessMs;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    static int intProp(Properties p, String key, int dflt) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(dflt)).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Reads and clamps an int property so bad hand-edits cannot throw in scheduleAtFixedRate and disable
     *  the watchdog. Kept here so unit tests cover the bounds. */
    static int intProp(Properties p, String key, int dflt, int min, int max) {
        return Math.max(min, Math.min(intProp(p, key, dflt), max));
    }

    /** Reads watchdog_poll_seconds clamped to 30s..24h (avoids period <= 0 throws and GitHub hammering).
     *  Bundled with the read so BamfosWatchdog cannot accidentally bypass the bounds. */
    static int pollSeconds(Properties p) {
        return intProp(p, "watchdog_poll_seconds", 300, 30, 86400);
    }

    /** Reads the chat countdown: negatives would halt immediately without warning, and huge values would
     *  block checkOnce for the life of the process. */
    static int countdownSeconds(Properties p) {
        return intProp(p, "watchdog_countdown_seconds", 60, 0, 3600);
    }

    /**
     * Returns the restart attempt count for this tag (resets to 0 when the tag changes so a new release
     * gets fresh attempts, while keeping the count across cooldowns for the same tag).
     */
    static int attemptsFor(Properties state, String latestTag) {
        return latestTag.equals(state.getProperty("last_target", ""))
                ? intProp(state, "last_target_attempts", 0) : 0;
    }

    static long longProp(Properties p, String key) {
        try {
            return Long.parseLong(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private WatchdogState() {
    }
}
