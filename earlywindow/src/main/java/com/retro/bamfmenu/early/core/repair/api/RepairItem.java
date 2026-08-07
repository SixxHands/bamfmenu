package com.retro.bamfmenu.early.core.repair.api;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable row definition for the UPDATE AND REPAIR menu, discovered from a RepairDef folder.
 * `gate` and `status` read the live snapshot so rows reflect the real install state.
 */
public record RepairItem(String id, String label, String help, RepairGate gate,
                         RepairStatus status, RepairEffect effect, Predicate<UpdaterState> alert) {

    /** Standard six-arg constructor used by most row factories below. */
    public RepairItem(String id, String label, String help, RepairGate gate,
                      RepairStatus status, RepairEffect effect) {
        this(id, label, help, gate, status, effect, state -> false);
    }

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

    /**
     * Builds a row that prompts for typed input and runs an action on it.
     * `initial` reads the snapshot instead of capturing a string at boot, since this row changes that value.
     */
    public static RepairItem edit(String id, String label, String help, RepairGate gate,
                                  RepairStatus status, String title,
                                  Function<UpdaterState, String> initial, RepairEffect.EditAction action) {
        return new RepairItem(id, label, help, gate, status,
                new RepairEffect.Edit(title, label, initial, action));
    }

    /**
     * Highlights the row and its status line red when `when` is true.
     * Used for quiet but important states like an active release lock that blocks future updates.
     */
    public RepairItem alerting(Predicate<UpdaterState> when) {
        return new RepairItem(id, label, help, gate, status, effect, when);
    }

    /** True when this row should be highlighted in the list to get the player's attention. */
    public boolean alerts(UpdaterState state) {
        return alert.test(state);
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
