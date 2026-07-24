package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.manifest.FileEntry;

import java.util.ArrayList;
import java.util.List;

/** What one sync will do, decided entirely before any byte moves. plan emits this; apply
 *  executes it. The split is what makes every table row testable in milliseconds. */
public final class SyncPlan {

    public enum Kind {
        ADOPT,        // A2: bytes already correct -> ledger only
        INSTALL,      // B with nothing on disk (fresh managed file)
        REPLACE,      // B: disk matches ledger -> swap in new bytes
        REPLACE_BAK,  // C: user edited a file the pack also changed -> .bak first, pack wins
        RESTORE,      // A': user deleted a managed file -> put it back
        SEED,         // D first install of a seed-once file
        DELETE,       // E with on_remove=delete
        ORPHAN,       // E with on_remove=orphan -> forget in ledger, leave the file
        QUARANTINE    // join-guard Q1: untracked jar on the kill list -> rename .disabled
    }

    /** entry is null for ledger-only actions (DELETE/ORPHAN/QUARANTINE); path is the manifest path. */
    public record Action(Kind kind, String path, FileEntry entry, boolean targetDisabled) {
    }

    public final List<Action> actions = new ArrayList<>();

    public List<Action> downloads() {
        return actions.stream().filter(a -> switch (a.kind()) {
            case INSTALL, REPLACE, REPLACE_BAK, RESTORE, SEED -> true;
            default -> false;
        }).toList();
    }

    public boolean isNoop() {
        return actions.stream().allMatch(a -> a.kind() == Kind.ADOPT);
    }
}
