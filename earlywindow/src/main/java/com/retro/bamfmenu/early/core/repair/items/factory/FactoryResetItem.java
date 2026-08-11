package com.retro.bamfmenu.early.core.repair.items.factory;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;

import java.util.List;
import java.util.Map;

/**
 * Restores the install to the shipped release state.
 * Uses two distinct confirm prompts with different button labels to prevent accidental double-ENTER.
 */
public final class FactoryResetItem implements RepairDef {

    /** Exact text for the two confirm prompts, kept as constants so tests can assert the wording verbatim. */
    public static final RepairEffect.Confirm ASK = new RepairEffect.Confirm("Confirm",
            "Are you sure you'd like to Factory Reset? This action will reset all mods, configs and "
                    + "BAMFOS resources to release defaults, including configured game options.", "Yes", "No");

    public static final RepairEffect.Confirm WARN = new RepairEffect.Confirm("Warning",
            "This cannot be undone. Are you sure?", "Proceed", "Cancel");

    /**
     * Right-panel help covering what the confirm prompts omit: edits to shipped resource packs revert
     * and user-added mods are removed, while user-added resource packs survive. Kept short for ~67-col panels.
     */
    public static final String HELP = "Removes all files in BAMFOS and redownloads defaults. Worlds, "
            + "screenshots and server lists are saved. Mods added by you will be deleted. Added Resource "
            + "packs are saved but BAMFOS packs will be deleted and redownloaded. All edits to BAMFOS "
            + "resource packs will be lost.";

    @Override
    public RepairItem item() {
        return new RepairItem("factory", "Factory Reset", HELP,
                RepairGate.FILE_SAFE,
                // Version this reset will target (always the target release, never latest).
                // Use the target reported by the engine instead of the version row, since they
                // disagree when pinned or when a bcc stamp exists without a ledger.
                state -> "Resets to " + state.resetTargetOrUnknown(),
                new RepairEffect.Act("Factory Reset", List.of(ASK, WARN), ops -> read(ops.factoryReset())));
    }

    /**
     * Maps the factoryReset response to a RepairResult, reusing the message from the engine so it matches the logs.
     * An empty map (older engine without factoryReset) is reported as unsupported rather than a failed reset.
     */
    public static RepairResult read(Map<String, Object> answer) {
        if (answer == null || answer.isEmpty() || !(answer.get("code") instanceof Number code)) {
            // REFUSED and never FAILED, exactly as the contract above says: nothing ran, nothing broke,
            // and FAILED's alert red sends a player hunting damage that does not exist
            return new RepairResult(RepairResult.Kind.REFUSED,
                    "This updater is too old for a factory reset. Nothing was changed.");
        }
        String said = answer.get("message") instanceof String s && !s.isBlank()
                ? s : "The reset did not report what it did.";
        return switch (code.intValue()) {
            // RESTART_REQUIRED closes the game so step 0 applies the wipe/replace on next boot.
            // Never word this as "complete" since nothing has been deleted yet.
            case 0 -> new RepairResult(RepairResult.Kind.RESTART_REQUIRED, said);
            // Non-fatal conditions map to REFUSED instead of FAILED: 1 = never synced, 3 = lock active,
            // 5 = throttled, 6 = reset precondition failed (no space, blocked file, etc.).
            case 1, 3, 5, 6 -> new RepairResult(RepairResult.Kind.REFUSED, said);
            default -> new RepairResult(RepairResult.Kind.FAILED, said);
        };
    }

    // Dont try this at home.
    @Override
    public int order() {
        return 90;
    }
}
