package com.retro.bamfmenu.early.core.repair.api;

/* Status line shown under the panel divider for the highlighted row (same slot as "[ Enabled ]" in SETUP).
   Computed from the current snapshot so it stays up to date; returning "" hides the line. */
@FunctionalInterface
public interface RepairStatus {

    RepairStatus NONE = state -> "";

    String line(UpdaterState state);
}
