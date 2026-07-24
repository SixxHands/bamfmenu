package com.retro.bamfos.updater.engine;

// Reflective entry point called by the core across an isolated classloader. Do NOT rename this class or enter(String[]).
public final class EngineEntry {

    /** Engine build version stamped by gradle into the jar manifest for logging. */
    public static String version() {
        String v = EngineEntry.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /**
     * Runs the engine. args carry the door's context (--server --dir ... etc.); M0 only proves the
     * handoff, so this logs identity and returns. Return code is the shim's exit code on the server
     * door -- 0 always except corrupted-local-state.
     */
    public static int enter(String[] args) {
        System.out.println("[updater] engine " + version() + " up, args=" + String.join(" ", args));
        return 0;
    }

    private EngineEntry() {
    }
}
