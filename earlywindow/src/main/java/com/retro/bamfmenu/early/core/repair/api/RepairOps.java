package com.retro.bamfmenu.early.core.repair.api;

/**
 * Blocking engine ops surface (HTTP and disk I/O; always call on the worker, never in render).
 * Return codes stay unmapped here so RepairResult translates them in one place and tests can stub them.
 */
public interface RepairOps {

    /** Reads status + optional mods + cached notes into one snapshot, stamped with `nowMillis`. */
    UpdaterState read(long nowMillis);

    /** Fetches available releases for the rollback picker along with the network status code. */
    ReleaseList releases(); // Slow call (up to ~3s offline with backoff), so never call this during render.

    /** Hash-audits every managed file against the installed manifest and stages the fixes. */
    int repair();

    /** Stages a sync to the pin, else to whatever the server broadcasts right now. */
    int syncNow();

    /** Writes the tag= release lock, then stages a sync to it. */
    int syncTo(String tag);

    /** Drops the tag= release lock and stages nothing. */
    int unpin();

    /** Toggles the .disabled suffix on an optional mod through the engine so the ledger stays in sync. */
    boolean setDisabled(String path, boolean disabled);
}
