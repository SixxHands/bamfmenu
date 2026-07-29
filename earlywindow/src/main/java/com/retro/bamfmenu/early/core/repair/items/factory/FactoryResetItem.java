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
            "Are you sure? This action will reset all configs to release defaults, and delete your "
                    + "configured game options.", "Yes", "No");

    public static final RepairEffect.Confirm WARN = new RepairEffect.Confirm("Warning",
            "This cannot be undone. Are you sure?", "Proceed", "Cancel");

    /**
     * Right-panel help covering what the confirm prompts omit: edits to shipped resource packs revert
     * and user-added mods are removed, while user-added resource packs survive. Kept short for ~67-col panels.
     */
    public static final String HELP = "Downloads the whole pack again and puts this install back to release "
            + "defaults. Your worlds, screenshots and server list are safe. Mods you added yourself are "
            + "deleted. Resource packs you added survive, but ones that came with BAMFOS go back to the "
            + "shipped version, so edits to those are lost.";

    @Override
    public RepairItem item() {
        return new RepairItem("factory", "Factory Reset", HELP,
                RepairGate.FILE_SAFE,
                // the version it would reset TO, because a reset targets the installed release and never latest
                state -> "Resets to " + state.installedOrUnknown(),
                new RepairEffect.Act("Factory Reset", List.of(ASK, WARN), ops -> read(ops.factoryReset())));
    }

    /**
     * Maps the factoryReset response to a RepairResult, reusing the message from the engine so it matches the logs.
     * An empty map (older engine without factoryReset) is reported as unsupported rather than a failed reset.
     */
    public static RepairResult read(Map<String, Object> answer) {
        if (answer == null || answer.isEmpty() || !(answer.get("code") instanceof Number code)) {
            return new RepairResult(RepairResult.Kind.FAILED,
                    "This updater is too old for a factory reset. Nothing was changed.");
        }
        String said = answer.get("message") instanceof String s && !s.isBlank()
                ? s : "The reset did not report what it did.";
        return switch (code.intValue()) {
            // DONE and not "reset complete": nothing has been deleted yet, and saying otherwise to somebody
            // who is about to close their game is the one lie this row cannot afford
            case 0 -> new RepairResult(RepairResult.Kind.DONE, said);
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
