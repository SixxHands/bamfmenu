package com.retro.bamfmenu.early.core.repair.api;

/**
 * Screen-side seam for the repair session (same pattern as SecretContext).
 * Repair ops block on HTTP and disk I/O, so the session delegates worker execution, clock, and exit here.
 */
public interface RepairContext {

    /** Runs an action on the page's worker, with `working` naming it while it keeps the window. */
    void run(String working, RepairAction action);

    /** Re-reads the local snapshot (status, optional mods, notes) on the worker. */
    void readState();

    /** Fetches the release list on the worker thread (the only network call). */
    void readReleases(); // up to ~3s of fetcher backoff when offline, so never run on the render thread

    /** Exits back to the BIOS SETUP menu on the row that opened this window. */
    void leave(String notice);

    /** Ends the game so a next-boot action can apply; safe here because no world is open during BIOS. */
    void closeGame();
}
