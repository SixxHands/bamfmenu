package com.retro.bamfos.updater.boot;

/**
 * Frozen stub ABI names. FML keeps this jar loaded for the entire session, so renaming keys breaks existing installs.
 * Engine-specific names are in CoreAbi with their original string values unchanged.
 */
public final class UpdaterAbi {

    /** Compiled ABI level for the boot stub. Unused for now; only bump if the handoff signature changes. */
    public static final int ABI = 1;

    /** Entry method the boot stub calls via reflection; see CoreEntry for the implementation. */
    static final String CORE_ENTRY_CLASS = "com.retro.bamfos.updater.core.CoreEntry";
    static final String CORE_ENTRY_METHOD = "enter";

    private UpdaterAbi() {
    }
}
