package com.retro.bamfmenu.early.core.repair.api;

/* Action run when you press ENTER on a row (e.g. `ops -> RepairResult.ofSync(ops.repair())`).
   Runs on the worker thread so it can block, and returns the status message to display instead
   of mutating page state directly. */
@FunctionalInterface
public interface RepairAction {
    RepairResult run(RepairOps ops);
}
