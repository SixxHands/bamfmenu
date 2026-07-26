package com.retro.bamfmenu.early.core.repair.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable updater snapshot captured on a worker and read during render.
 * `loaded` distinguishes the initial disk read (`reading()`) from real zero/empty values.
 */
public record UpdaterState(boolean loaded, String installed, String pinned, boolean tokenDead,
                           String lastSyncResult, long lastSyncAt, long nowMillis, boolean syncing,
                           int disabledCount, int quarantinedCount, boolean statusUnreadable,
                           String notes, List<Mod> optional, ReleaseList releases) {

    /** An optional or quarantined managed jar from listOptional(). `missing` is true when neither the
     *  enabled nor .disabled file exists on disk, so the UI doesn't show a false on/off state. */
    public record Mod(String path, boolean disabled, boolean missing, boolean quarantined, String reason) {

        /** File name without the parent directory so it fits in a 36-column cell. */
        public String name() {
            int slash = path.lastIndexOf('/');
            return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
        }
    }

    /** Initial loading state before the worker returns the first snapshot. */
    public static UpdaterState reading() {
        return new UpdaterState(false, "", "", false, "", 0L, 0L, false, 0, 0, false, "", List.of(), null);
    }

    /**
     * Folds the three local ops maps into one state without throwing; missing keys default to empty for older engines.
     * An empty or `{error}` status map marks the state unreadable so a fault never masks a real `tag=` pin as unpinned.
     */
    public static UpdaterState of(Map<String, Object> status, List<Map<String, Object>> optional,
                                  String notes, long nowMillis) {
        List<Mod> mods = new ArrayList<>();
        for (Map<String, Object> row : optional) {
            mods.add(new Mod(str(row.get("path")), Boolean.TRUE.equals(row.get("disabled")),
                    Boolean.TRUE.equals(row.get("missing")),
                    Boolean.TRUE.equals(row.get("quarantined")), str(row.get("reason"))));
        }
        return new UpdaterState(true, str(status.get("version")), str(status.get("pinned")),
                Boolean.TRUE.equals(status.get("token_dead")), str(status.get("last_sync_result")),
                num(status.get("last_sync_at")), nowMillis, false,
                (int) num(status.get("disabled_count")), (int) num(status.get("quarantined_count")),
                status.isEmpty() || status.containsKey("error"), notes == null ? "" : notes,
                List.copyOf(mods), null);
    }

    /** Returns a copy of this state with the fetched release list attached. */
    public UpdaterState withReleases(ReleaseList list) {
        return new UpdaterState(loaded, installed, pinned, tokenDead, lastSyncResult, lastSyncAt,
                nowMillis, syncing, disabledCount, quarantinedCount, statusUnreadable, notes,
                optional, list);
    }

    /** Updates the wall clock and lock flag without re-reading the full snapshot. */
    public UpdaterState live(long millis, boolean engineSyncing) {
        return new UpdaterState(loaded, installed, pinned, tokenDead, lastSyncResult, lastSyncAt,
                millis, engineSyncing, disabledCount, quarantinedCount, statusUnreadable, notes,
                optional, releases);
    }

    /** True when a tag= release lock keeps this install off the server's broadcast. */
    public boolean isPinned() {
        return !pinned.isEmpty();
    }

    /** Returns the installed version, or a fallback label if unknown. */
    public String installedOrUnknown() {
        return installed.isEmpty() ? "unknown" : installed;
    }

    /** Formats the last sync result and elapsed time as a single cell, or "never". */
    public String lastSyncCell() {
        if (lastSyncAt <= 0L || lastSyncResult.isEmpty()) {
            return "never";
        }
        return lastSyncResult + ", " + ago(nowMillis - lastSyncAt);
    }

    /* Rounded to coarse units so BIOS rows show "3d ago" instead of "71h ago".
       Negative deltas (if the system clock jumped backwards) clamp to "just now". */
    private static String ago(long millis) {
        long minutes = millis / 60_000L;
        if (minutes < 1L) {
            return "just now";
        }
        if (minutes < 60L) {
            return minutes + "m ago";
        }
        long hours = minutes / 60L;
        return hours < 24L ? hours + "h ago" : (hours / 24L) + "d ago";
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
