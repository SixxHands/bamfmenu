package com.retro.bamfmenu.early.core.repair.api;

/* Returns "" if a row can run right now, or the reason string to display when blocked.
   Reads only the snapshot so disabled rows explain why before the user presses ENTER. */
@FunctionalInterface
public interface RepairGate {

    /** No precondition; the row is always allowed to run. */
    RepairGate OPEN = state -> "";

    /**
     * Single-writer gate for file-touching rows while the engine is mid-write.
     * Reuses the same bus-fed sync flag and refusal text as the DEL gate.
     */
    RepairGate FILE_SAFE = state -> state.syncing() ? RepairResult.locked().message() : "";

    String whyNot(UpdaterState state);

    /** Runs gates in order and returns the first refusal reason, combining row-specific and shared checks. */
    static RepairGate all(RepairGate... gates) {
        return state -> {
            for (RepairGate gate : gates) {
                String why = gate.whyNot(state);
                if (!why.isEmpty()) {
                    return why;
                }
            }
            return "";
        };
    }
}
