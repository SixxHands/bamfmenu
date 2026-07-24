package com.retro.bamfos.updater.boot;

/**
 * The shim's compiled ABI level -- the ONE ordered comparison in the whole system:
 * a manifest whose pack.updater_abi exceeds this is relaunch-class, because the running shim cannot be
 * trusted to process it. Bump ONLY when the shim<->engine handoff contract itself changes shape.
 */
public final class UpdaterAbi {

    public static final int ABI = 1;

    /** The handoff contract the shim reflects into; EngineEntry documents the other side. */
    public static final String ENGINE_ENTRY_CLASS = "com.retro.bamfos.updater.engine.EngineEntry";
    public static final String ENGINE_ENTRY_METHOD = "enter";

    private UpdaterAbi() {
    }
}
