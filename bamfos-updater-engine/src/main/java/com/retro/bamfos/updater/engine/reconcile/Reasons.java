package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.reconcile.SyncPlan.Kind;

// Short human-readable labels for each reconciler action, shared by the boot terminal and BIOS repair UI.
public final class Reasons {

    /** Reason tag for updater self-update jars, which aren't part of SyncPlan. */
    public static final String SELF = "updater itself";

    private Reasons() {
    }

    /** @param repair true when the plan came from repair(), which hash-verifies instead of trusting the ledger */
    public static String of(Kind kind, boolean repair) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case INSTALL -> repair ? "missing" : "new file";
            case REPLACE -> repair ? "sha mismatch" : "newer version";
            // Same either way: the .bak path is what matters when we overwrite a player's edits.
            case REPLACE_BAK -> "edited, backed up";
            case RESTORE -> "missing";
            case SEED -> "first install";
            case DELETE -> repair ? "not in the pack" : "dropped";
            case ORPHAN -> "left in place";
            case QUARANTINE -> "blocks joining";
            case MERGE_SERVERS -> "server list";
            // ADOPT doesn't download anything, so there's no file event to attach a reason to.
            case ADOPT -> "";
        };
    }
}
