package com.retro.bamfmenu.early.core.repair.api;

/**
 * Immutable row definition for the UPDATE AND REPAIR menu, discovered from a RepairDef folder.
 * `gate` and `status` read the live snapshot so rows reflect the real install state.
 */
public record RepairItem(String id, String label, String help, RepairGate gate,
                         RepairStatus status, RepairEffect effect) {

    /** A row that acts on the install; `confirm` is the are-you-sure question, "" for none. */
    public static RepairItem act(String id, String label, String help, RepairGate gate,
                                 RepairStatus status, String confirm, RepairAction action) {
        // Reuse the row label as the busy status text while the action runs.
        return new RepairItem(id, label, help, gate, status, new RepairEffect.Act(label, confirm, action));
    }

    /** A row that opens a second screen in the right panel. */
    public static RepairItem page(String id, String label, String help, RepairGate gate,
                                  RepairStatus status, RepairPage page) {
        return new RepairItem(id, label, help, gate, status, new RepairEffect.Page(page));
    }

    /** Why this row cannot run against this snapshot, "" when it can. */
    public String whyNot(UpdaterState state) {
        return gate.whyNot(state);
    }

    /** True when ENTER on this row does something right now. */
    public boolean enabled(UpdaterState state) {
        return whyNot(state).isEmpty();
    }
}
