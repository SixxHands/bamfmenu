package com.retro.bamfmenu.early.core.repair.api;

/* Sealed result of pressing ENTER on a menu row (action, page, or text input).
   Confirmation prompts live on Act as data rather than a separate switch. */
public sealed interface RepairEffect permits RepairEffect.Act, RepairEffect.Page, RepairEffect.Edit {

    /**
     * Runs an action on the install, optionally behind one or more confirm dialogs.
     * `working` is shown while busy; multiple confirms let factory reset use two distinct prompts.
     */
    record Act(String working, java.util.List<Confirm> confirms, RepairAction action) implements RepairEffect {

        /** Single confirmation prompt overload.
         *  An empty question skips the prompt and runs immediately. */
        public Act(String working, String confirm, RepairAction action) {
            this(working, confirm.isEmpty() ? java.util.List.of() : java.util.List.of(Confirm.yesNo(confirm)),
                    action);
        }
    }

    /**
     * One confirm dialog: title, body, and button labels (data so multi-step prompts can vary labels).
     * Title and body stay separate because popups size to their longest line and would clip one long string.
     */
    record Confirm(String title, String body, String yes, String no) {

        public static Confirm yesNo(String body) {
            return new Confirm("Confirm", body, "Yes", "No");
        }
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
