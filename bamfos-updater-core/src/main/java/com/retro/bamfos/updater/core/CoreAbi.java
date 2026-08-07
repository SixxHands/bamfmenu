package com.retro.bamfos.updater.core;

// Reflective entry names for loading the engine jar. Don't rename these without updating both sides.
final class CoreAbi {

    /** The handoff contract the core reflects into; EngineEntry documents the other side. */
    static final String ENGINE_ENTRY_CLASS = "com.retro.bamfos.updater.engine.EngineEntry";
    static final String ENGINE_ENTRY_METHOD = "enter";

    /** The ops doorway, looked up ABSENCE-TOLERANT: an older engine without it just
     *  leaves the BIOS surface unbound, additive, so no ABI bump. */
    static final String OPS_GATEWAY_CLASS = "com.retro.bamfos.updater.engine.ops.OpsGateway";
    static final String OPS_INVOKE_METHOD = "invoke";

    private CoreAbi() {
    }
}
