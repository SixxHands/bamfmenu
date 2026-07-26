package com.retro.bamfmenu.early.core.repair.api;

import java.util.List;

/**
 * Pure sub-screen for the right panel: maps a snapshot to rows and a chosen row index to an effect.
 * Selection, scrolling, and worker threads live in RepairSession.
 */
public interface RepairPage {

    /** The heading drawn at the top of the panel. */
    String title();

    /** Builds the panel lines for this snapshot, wrapped to `width` columns (leaving 2 for the cursor). */
    List<PageRow> rows(UpdaterState state, int width);

    /** True when this page cannot be read until the release list has been fetched. */
    default boolean needsReleases() {
        return false;
    }

    /**
     * Effect for pressing ENTER on `index`, or null if the row does nothing.
     * Takes `width` because wrapped prose changes row counts, so index lookup must match the rendered width.
     */
    default RepairEffect.Act activate(int index, UpdaterState state, int width) {
        return null;
    }

    /** The bottom control bar while this page is open. */
    default String hints() {
        return "UP/DOWN  Scroll      ESC  Back      F2  Volume";
    }
}
