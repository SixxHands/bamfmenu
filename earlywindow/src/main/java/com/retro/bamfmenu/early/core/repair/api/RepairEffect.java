package com.retro.bamfmenu.early.core.repair.api;

/* Sealed result of pressing ENTER on a menu row (action, page, or text input).
   Confirmation prompts live on Act as data rather than a separate switch. */
public sealed interface RepairEffect permits RepairEffect.Act, RepairEffect.Page, RepairEffect.Edit {

    /** Do something to the install, optionally behind an are-you-sure prompt. `working` is the line the
     *  panel shows while it runs, so a player can see WHICH thing the window is busy with. */
    record Act(String working, String confirm, RepairAction action) implements RepairEffect {
    }

    /** Open a second screen in the right panel. */
    record Page(RepairPage page) implements RepairEffect {
    }

    /**
     * Prompts for typed input, then runs a blocking action on it (`working` is the busy line).
     * `initial` reads the live snapshot on open so edits start from the current value.
     */
    record Edit(String title, String working, java.util.function.Function<UpdaterState, String> initial,
                EditAction action) implements RepairEffect {
    }

    /** Action run when ENTER submits a text field. Runs on the worker thread so it can block;
     *  the value is never null and has no caret character. */
    @FunctionalInterface
    interface EditAction {
        RepairResult run(RepairOps ops, String typed);
    }
}
