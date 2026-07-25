package com.retro.bamfos.updater.engine.ops;

import java.nio.file.Path;

// String-keyed reflective entry point bound to UpdaterBridge. Returns plain JDK types and "error: ..." strings instead of throwing across classloaders.
public final class OpsGateway {

    private static volatile UpdaterOps ops;

    /** Bound once by EngineEntry in client mode before the updater runs, and kept for the session. */
    public static void bind(Path dir, String apiBase) {
        ops = new UpdaterOps(dir, apiBase);
    }

    public static Object invoke(String op, Object[] args) {
        UpdaterOps o = ops;
        if (o == null) {
            return "error: ops not bound";
        }
        try {
            return switch (op) {
                case "status" -> o.status();
                case "syncNow" -> o.syncNow();
                case "syncTo" -> o.syncTo(String.valueOf(args[0]));
                case "repair" -> o.repair();
                case "listOptional" -> o.listOptional();
                case "setDisabled" -> o.setDisabled(String.valueOf(args[0]), Boolean.TRUE.equals(args[1]));
                case "releaseNotes" -> o.releaseNotes();
                default -> "error: unknown op " + op;
            };
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private OpsGateway() {
    }
}
