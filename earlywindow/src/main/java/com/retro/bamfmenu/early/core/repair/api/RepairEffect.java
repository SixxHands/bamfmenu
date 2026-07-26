package com.retro.bamfmenu.early.core.repair.api;

/* Sealed result of pressing ENTER on a menu row (action, page, or text input).
   Confirmation prompts live on Act as data rather than a separate switch. */
public sealed interface RepairEffect permits RepairEffect.Act, RepairEffect.Page {

    /** Do something to the install, optionally behind an are-you-sure prompt. `working` is the line the
     *  panel shows while it runs, so a player can see WHICH thing the window is busy with. */
    record Act(String working, String confirm, RepairAction action) implements RepairEffect {
    }

    /** Open a second screen in the right panel. */
    record Page(RepairPage page) implements RepairEffect {
    }
}
