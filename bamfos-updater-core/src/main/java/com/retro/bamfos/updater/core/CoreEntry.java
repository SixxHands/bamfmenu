package com.retro.bamfos.updater.core;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/* Entry point called reflectively by the frozen stub (do NOT rename istg). Returns a Map of java.base
 * types ("exit", "ops") so values cross the isolated classloader safely. */
public final class CoreEntry {

    public static Map<String, Object> enter(String[] args) throws Exception {
        Path instanceDir = instanceDir(args);
        Path coreDir = instanceDir.resolve(".bamfos-updater").resolve("core");
        Path engineJar = instanceDir.resolve(".bamfos-updater").resolve("engine")
                .resolve("bamfos-updater-engine.jar");

        // Read the mark before loading the engine; clearing it first would let a crash during load go unrecorded.
        String[] mark = CoreHealth.readMark(coreDir);
        boolean ours = CoreHealth.armedHere(mark);
        String skipped = ours ? CoreHealth.skipped(mark) : "";

        EngineLoader.Engine engine = EngineLoader.ensureAndLoad(engineJar,
                BaselineEngine.supplier(instanceDir));

        // THE SEAM
        if (ours) {
            if (!skipped.isEmpty()) {
                CoreHealth.strike(coreDir, skipped);
            }
            CoreHealth.clearMark(coreDir);
            CoreHealth.prune(coreDir, CoreHealth.running(mark));
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("exit", engine.enter(args));
        BiFunction<String, Object[], Object> ops = ops(engine);
        if (ops != null) {
            answer.put("ops", ops);
        }
        return answer;
    }

    /** The same --dir the doors read. Parsed here because the stub hands its argv straight through and the core
     *  needs the instance root before the engine ever gets a look at it. */
    private static Path instanceDir(String[] args) {
        Path dir = Paths.get(".");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--dir")) {
                dir = Paths.get(args[i + 1]);
            }
        }
        return dir;
    }

    /**
     * Wraps the engine ops gateway in a java.base BiFunction so it crosses both isolated classloaders.
     * Optional for older engines, and returns "error: ..." instead of throwing across the boundary.
     */
    private static BiFunction<String, Object[], Object> ops(EngineLoader.Engine engine) {
        try {
            Class<?> gateway = Class.forName(CoreAbi.OPS_GATEWAY_CLASS, false, engine.loader());
            Method invoke = gateway.getMethod(CoreAbi.OPS_INVOKE_METHOD, String.class, Object[].class);
            return (op, callArgs) -> {
                try {
                    return invoke.invoke(null, op, callArgs);
                } catch (ReflectiveOperationException e) {
                    return "error: " + e;
                }
            };
        } catch (ReflectiveOperationException oldEngine) {
            return null; // pre-ops engine: BIOS renders its not-available state, everything else works
        }
    }

    private CoreEntry() {
    }
}
